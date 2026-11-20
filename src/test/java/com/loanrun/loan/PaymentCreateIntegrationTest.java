package com.loanrun.loan;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.*;
import com.loanrun.common.contract.*;
import com.loanrun.common.contract.FinanceModels.*;
import com.loanrun.loan.read.LoanSourceRepository;
import com.loanrun.loan.service.LoanRegistrationService;
import com.loanrun.repaymentplan.service.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@Testcontainers
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties="server.servlet.session.cookie.secure=false")
@Import(PaymentCreateIntegrationTest.Config.class)
class PaymentCreateIntegrationTest {
    @Container static final PostgreSQLContainer<?> DB=new PostgreSQLContainer<>("postgres:16.16-bookworm");
    @DynamicPropertySource static void db(DynamicPropertyRegistry r){r.add("spring.datasource.url",DB::getJdbcUrl);r.add("spring.datasource.username",DB::getUsername);r.add("spring.datasource.password",DB::getPassword);}
    @TestConfiguration static class Config {@Bean @Primary Clock testClock(){return Clock.fixed(Instant.parse("2026-03-01T00:00:00Z"),ZoneId.of("Asia/Seoul"));}}
    @MockitoBean BudgetRecalculationPort recalc;
    @MockitoBean com.loanrun.budget.service.BudgetReadService budget;
    @MockitoBean PlanQueryPort overall;
    @Autowired JdbcTemplate jdbc;@Autowired ObjectMapper mapper;@Autowired LoanRegistrationService registration;
    @Autowired LoanSourceRepository sources;@Autowired PlanViewQueryService views;@Autowired RevisionPort revisions;@Autowired Clock clock;
    @LocalServerPort int port;
    @BeforeEach void setup()throws Exception {
        if(jdbc.queryForObject("SELECT to_regclass('payment_due_archives')",String.class)==null)jdbc.execute(Files.readString(Path.of("docs/sql/payment_due_archives_proposal.sql")));
        if(jdbc.queryForObject("SELECT to_regclass('repayment_plan_versions')",String.class)==null)jdbc.execute(Files.readString(Path.of("docs/sql/repayment_plan_versions_proposal.sql")));
        when(budget.capacity(anyLong(),any())).thenAnswer(i->new BudgetCapacity(i.getArgument(1),2000000L,2000000L,0,2000000L,0,0,2000000L,0,0L,"RECORDED","OPEN",List.of()));
        when(budget.monthlyOverrides(anyLong(),any())).thenReturn(List.of());
    }
    HttpClient browser(){return HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();}
    HttpResponse<String> get(HttpClient b,String path)throws Exception{return b.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+path)).GET().build(),HttpResponse.BodyHandlers.ofString());}
    JsonNode json(HttpResponse<String> r)throws Exception{return mapper.readTree(r.body());}
    long owner(HttpClient b)throws Exception{return json(get(b,"/api/users/me/")).path("data").path("user_id").longValue();}
    long loan(long owner,boolean bullet,boolean exempt)throws Exception {
        var body=new LoanPreviewTest().body();body.put("fee_exempt",exempt);
        if(bullet){body.put("repayment_method","BULLET");body.putNull("prepayment_policy");body.put("initial_savings_balance",2000000);}
        return registration.create(owner,body,null).body().path("data").path("loan_id").longValue();
    }
    JsonNode list(HttpClient b,String query)throws Exception {var r=get(b,"/api/repayment-plans/"+query);assertThat(r.statusCode()).as(r.body()).isEqualTo(200);assertThat(json(r).path("code").asText()).isEqualTo("REPAYMENT_PLAN_LIST_SUCCESS");return json(r).path("data");}
    HttpResponse<String> post(HttpClient b,JsonNode body,String key)throws Exception {var r=HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/payments/")).header("Origin","http://localhost:5173").header("Content-Type","application/json");if(key!=null)r.header("Idempotency-Key",key);return b.send(r.POST(HttpRequest.BodyPublishers.ofString(body.toString())).build(),HttpResponse.BodyHandlers.ofString());}
    com.fasterxml.jackson.databind.node.ObjectNode body(long id,boolean extra,long amount)throws Exception {var n=mapper.createObjectNode();n.put("loan_id",id);n.put("paid_at","2026-03-01");n.put("payment_type",extra?"EXTRA":"REGULAR");n.put("amount",amount);if(!extra)n.put("due_item_id",jdbc.queryForObject("SELECT id FROM due_items WHERE loan_id=? ORDER BY due_date,id LIMIT 1",Long.class,id));return n;}
    JsonNode ok(HttpClient b,JsonNode n,String key)throws Exception{var r=post(b,n,key);assertThat(r.statusCode()).as(r.body()).isEqualTo(201);assertThat(json(r).path("code").asText()).isEqualTo("PAYMENT_CREATE_SUCCESS");return json(r).path("data");}
    @Test void regularAllocatesInterestThenPrincipalUpdatesBalanceAndCreatesNextActualDue()throws Exception {
        var b=browser();long user=owner(b),id=loan(user,false,true),rev=revisions.current(user);var original=sources.loan(user,id).initialPlan();var d=ok(b,body(id,false,1260000),"regular");
        assertThat(d.path("payment").path("principal_amount").longValue()).isEqualTo(1200000);assertThat(d.path("payment").path("interest_amount").longValue()).isEqualTo(60000);assertThat(d.path("remaining_principal").longValue()).isEqualTo(10800000);assertThat(d.path("updated_schedule").path("status").asText()).isEqualTo("PAID");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM due_items WHERE loan_id=?",Integer.class,id)).isEqualTo(2);assertThat(jdbc.queryForObject("SELECT interest_amount FROM due_items WHERE loan_id=? AND due_date='2026-04-25'",Long.class,id)).isEqualTo(54000);assertThat(revisions.current(user)).isEqualTo(rev+1);assertThat(sources.loan(user,id).initialPlan()).isEqualTo(original);
    }
    @Test void partialAndExtraAreDifferentAndCurrentCycleInterestIsFrozen()throws Exception {
        var b=browser();long user=owner(b),id=loan(user,false,true);var extra=ok(b,body(id,true,100000),"extra");assertThat(extra.path("updated_schedule").path("status").asText()).isEqualTo("UPCOMING");assertThat(extra.path("remaining_principal").longValue()).isEqualTo(11900000);
        var regular=ok(b,body(id,false,50000),"partial");assertThat(regular.path("updated_schedule").path("status").asText()).isEqualTo("PARTIAL");assertThat(regular.path("payment").path("principal_amount").longValue()).isZero();assertThat(jdbc.queryForObject("SELECT interest_amount FROM due_items WHERE loan_id=?",Long.class,id)).isEqualTo(60000);
    }
    @Test void canonicalReplayAndDistinctIdenticalRecordsArePreserved()throws Exception {
        var b=browser();long user=owner(b),id=loan(user,false,true);var n=body(id,true,100);var first=ok(b,n,"once");long rev=revisions.current(user);assertThat(json(post(b,n,"once")).path("data")).isEqualTo(first);assertThat(revisions.current(user)).isEqualTo(rev);
        ok(b,n,"twice");assertThat(jdbc.queryForObject("SELECT count(*) FROM payments WHERE loan_id=?",Integer.class,id)).isEqualTo(2);n.put("amount",101);assertThat(post(b,n,"once").statusCode()).isEqualTo(409);
    }
    @Test void explicitActualComponentsIncludeConfirmedExtraAndDoNotReuseOldBankEstimate()throws Exception {
        var b=browser();long user=owner(b),id=loan(user,false,true);var n=body(id,false,1360000);n.put("principal_amount",1200000);n.put("extra_amount",100000);n.put("interest_amount",60000);n.put("fee_amount",0);
        var d=ok(b,n,"actual");assertThat(d.path("remaining_principal").longValue()).isEqualTo(10700000);assertThat(d.path("payment").path("fee_amount").longValue()).isZero();assertThat(d.path("updated_schedule").path("status").asText()).isEqualTo("PAID");
    }
    @Test void invalidOrUnconfirmedExcessFutureAndForeignRequestsNeverWrite()throws Exception {
        var b=browser();var other=browser();long user=owner(b),id=loan(user,false,true);owner(other);long rev=revisions.current(user);
        assertThat(post(b,body(id,false,1300000),"excess").statusCode()).isEqualTo(400);var future=body(id,true,100);future.put("paid_at","2026-03-02");assertThat(post(b,future,"future").statusCode()).isEqualTo(400);assertThat(post(other,body(id,true,100),"foreign").statusCode()).isEqualTo(404);assertThat(post(b,body(id,true,100),null).statusCode()).isEqualTo(400);
        var partial=body(id,true,100);partial.put("principal_amount",0);assertThat(post(b,partial,"components").statusCode()).isEqualTo(400);assertThat(revisions.current(user)).isEqualTo(rev);assertThat(jdbc.queryForObject("SELECT count(*) FROM payments WHERE loan_id=?",Integer.class,id)).isZero();
    }
    @Test void unknownFeeNeedsActualComponentsAndOverPrincipalIsRejected()throws Exception {
        var b=browser();long user=owner(b),id=loan(user,false,false);assertThat(post(b,body(id,true,100),"unknown").statusCode()).isEqualTo(400);var n=body(id,true,100);n.put("principal_amount",0);n.put("extra_amount",90);n.put("interest_amount",0);n.put("fee_amount",10);ok(b,n,"known");
        n.put("amount",12000011);n.put("extra_amount",12000001);assertThat(post(b,n,"over").statusCode()).isEqualTo(400);
    }
    @Test void bulletWithdrawalUsesItsOwnAssetsAndCannotAssumeSameDayDepositOrder()throws Exception {
        var b=browser();long user=owner(b),id=loan(user,true,true);var n=body(id,true,100);n.put("savings_used_amount",100);var d=ok(b,n,"asset");assertThat(d.path("remaining_principal").longValue()).isEqualTo(11999900);assertThat(d.path("updated_plan").path("savings_balance").longValue()).isEqualTo(1999900);
        var other=loan(user,true,true);jdbc.update("UPDATE loans SET initial_savings_balance=0 WHERE id=?",other);jdbc.update("INSERT INTO repayment_savings(user_id,loan_id,saved_at,amount,created_at) VALUES (?,?,'2026-03-01',100,CURRENT_TIMESTAMP)",user,other);var conflict=body(other,true,100);conflict.put("savings_used_amount",100);assertThat(post(b,conflict,"order").statusCode()).isEqualTo(409);
    }
    @Test void budgetFailureRollsBackActualAllocationBalanceDueRevisionAndKey()throws Exception {
        var b=browser();long user=owner(b),id=loan(user,false,true),rev=revisions.current(user);var before=sources.loanSnapshot(user,id,LocalDate.of(2026,3,1));doThrow(com.loanrun.common.error.ApiException.unavailable()).when(recalc).recalculateFrom(user,YearMonth.of(2026,3));var n=body(id,false,1260000);
        assertThat(post(b,n,"rollback").statusCode()).isEqualTo(503);assertThat(sources.loanSnapshot(user,id,LocalDate.of(2026,3,1))).isEqualTo(before);assertThat(revisions.current(user)).isEqualTo(rev);doNothing().when(recalc).recalculateFrom(user,YearMonth.of(2026,3));ok(b,n,"rollback");
    }
    @Test void actualRegularCompositionCanCorrectEstimateAndIncludesItsFee()throws Exception {
        var b=browser();long user=owner(b),id=loan(user,false,true);var n=body(id,false,1260000);n.put("principal_amount",1199990);n.put("extra_amount",0);n.put("interest_amount",60000);n.put("fee_amount",10);
        var d=ok(b,n,"actual-fee");assertThat(d.path("remaining_principal").longValue()).isEqualTo(10800010);assertThat(d.path("payment").path("fee_amount").longValue()).isEqualTo(10);assertThat(d.path("updated_schedule").path("status").asText()).isEqualTo("PAID");
    }

    @Test void fullExtraCompletesLoanRemovesOnlyUnconfirmedFutureAndReplaysAfterClosure()throws Exception {
        var b=browser();long user=owner(b),id=loan(user,false,true);var n=body(id,true,12000000);var d=ok(b,n,"close");assertThat(d.path("remaining_principal").longValue()).isZero();assertThat(sources.loan(user,id).storedStatus()).isEqualTo(LoanStatus.PAID);assertThat(jdbc.queryForObject("SELECT count(*) FROM due_items WHERE loan_id=?",Integer.class,id)).isZero();
        assertThat(json(post(b,n,"close")).path("data")).isEqualTo(d);assertThat(post(b,body(id,true,1),"after").statusCode()).isEqualTo(409);
    }
    @Test void fullExtraCannotErasePastUnfulfilledContractualObligations()throws Exception {
        var b=browser();long user=owner(b),id=loan(user,false,true);jdbc.update("UPDATE due_items SET due_date='2026-02-25' WHERE loan_id=?",id);var before=sources.loanSnapshot(user,id,LocalDate.of(2026,3,1));
        assertThat(post(b,body(id,true,12000000),"past").statusCode()).isEqualTo(409);assertThat(sources.loanSnapshot(user,id,LocalDate.of(2026,3,1))).isEqualTo(before);
    }

    @Test void indistinguishableCorrectionAndPaymentTimestampCannotCreateAnUnreplayableLedger()throws Exception {
        var b=browser();long user=owner(b),id=loan(user,false,true);jdbc.update("INSERT INTO loan_balance_snapshots(user_id,loan_id,effective_date,amount,reason,created_at) VALUES (?,?,'2026-03-01',12000000,'CORRECTION',?)",user,id,java.sql.Timestamp.from(clock.instant()));
        assertThat(post(b,body(id,true,100),"ambiguous-correction").statusCode()).isEqualTo(409);assertThat(jdbc.queryForObject("SELECT count(*) FROM payments WHERE loan_id=?",Integer.class,id)).isZero();
    }

}
