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
@Import(PaymentUpdateIntegrationTest.Config.class)
class PaymentUpdateIntegrationTest {
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
    HttpResponse<String> patch(HttpClient b,long id,String body,String key)throws Exception {var r=HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/payments/"+id+"/")).header("Origin","http://localhost:5173").header("Content-Type","application/json");if(key!=null)r.header("Idempotency-Key",key);return b.send(r.method("PATCH",HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());}
    @Test void amountChangeRecomputesAutoComponentsAndPreservesFrozenDue()throws Exception {
        var b=browser();long user=owner(b),loan=loan(user,false,true);var created=ok(b,body(loan,false,1260000),"create");long id=created.path("payment").path("payment_id").longValue(),rev=revisions.current(user);
        var r=patch(b,id,"{\"amount\":50000}","update");assertThat(r.statusCode()).as(r.body()).isEqualTo(200);var d=json(r).path("data");assertThat(d.path("payment").path("principal_amount").longValue()).isZero();assertThat(d.path("payment").path("interest_amount").longValue()).isEqualTo(50000);assertThat(d.path("remaining_principal").longValue()).isEqualTo(12000000);assertThat(d.path("schedule_status").asText()).isEqualTo("PARTIAL");assertThat(d.path("recalculated").asBoolean()).isTrue();assertThat(revisions.current(user)).isEqualTo(rev+1);
        assertThat(jdbc.queryForObject("SELECT interest_amount FROM due_items WHERE loan_id=? AND due_date='2026-03-25'",Long.class,loan)).isEqualTo(60000);assertThat(json(patch(b,id,"{\"amount\":50000}","update"))).isEqualTo(json(r));
    }
    @Test void noOpHasNoBudgetOrRevisionSideEffectsAndKeyIsRequired()throws Exception {
        var b=browser();long user=owner(b),loan=loan(user,false,true),id=ok(b,body(loan,true,100),"create").path("payment").path("payment_id").longValue(),rev=revisions.current(user);clearInvocations(recalc);
        assertThat(patch(b,id,"{}",null).statusCode()).isEqualTo(400);var r=patch(b,id,"{\"amount\":100}","noop");assertThat(r.statusCode()).as(r.body()).isEqualTo(200);assertThat(json(r).path("data").path("recalculated").asBoolean()).isFalse();assertThat(json(r).path("data").path("report_stale").asBoolean()).isFalse();assertThat(revisions.current(user)).isEqualTo(rev);verifyNoInteractions(recalc);
    }
    @Test void explicitActualFieldsRequireAllFourAndAmountCannotExceedPrincipal()throws Exception {
        var b=browser();long user=owner(b),loan=loan(user,false,true),id=ok(b,body(loan,true,100),"create").path("payment").path("payment_id").longValue();
        assertThat(patch(b,id,"{\"extra_amount\":90}","partial").statusCode()).isEqualTo(400);assertThat(patch(b,id,"{\"amount\":null}","null").statusCode()).isEqualTo(400);assertThat(patch(b,id,"{\"loan_id\":1}","owner").statusCode()).isEqualTo(400);
        var r=patch(b,id,"{\"principal_amount\":0,\"extra_amount\":90,\"interest_amount\":0,\"fee_amount\":10}","actual");assertThat(r.statusCode()).as(r.body()).isEqualTo(200);assertThat(json(r).path("data").path("remaining_principal").longValue()).isEqualTo(11999910);
        assertThat(patch(b,id,"{\"amount\":12000001}","over").statusCode()).isEqualTo(400);
    }
    @Test void fullPaymentReductionReactivatesAndRestoresExactArchivedOriginalDue()throws Exception {
        var b=browser();long user=owner(b),loan=loan(user,false,true);var due=jdbc.queryForMap("SELECT * FROM due_items WHERE loan_id=?",loan);long id=ok(b,body(loan,true,12000000),"close").path("payment").path("payment_id").longValue();
        var r=patch(b,id,"{\"amount\":100000}","reopen");assertThat(r.statusCode()).as(r.body()).isEqualTo(200);assertThat(sources.loan(user,loan).storedStatus()).isEqualTo(LoanStatus.ACTIVE);assertThat(json(r).path("data").path("remaining_principal").longValue()).isEqualTo(11900000);assertThat(jdbc.queryForMap("SELECT * FROM due_items WHERE loan_id=?",loan)).isEqualTo(due);
    }
    @Test void ownershipSnapshotLockAndFailedRecalculationCannotChangeAnySource()throws Exception {
        var b=browser();var other=browser();long user=owner(b),loan=loan(user,false,true),id=ok(b,body(loan,true,100),"create").path("payment").path("payment_id").longValue();owner(other);assertThat(patch(other,id,"{}","foreign").statusCode()).isEqualTo(404);
        var before=sources.loanSnapshot(user,loan,LocalDate.of(2026,3,1));long rev=revisions.current(user);doThrow(com.loanrun.common.error.ApiException.unavailable()).when(recalc).recalculateFrom(user,YearMonth.of(2026,3));assertThat(patch(b,id,"{\"amount\":200}","rollback").statusCode()).isEqualTo(503);assertThat(sources.loanSnapshot(user,loan,LocalDate.of(2026,3,1))).isEqualTo(before);assertThat(revisions.current(user)).isEqualTo(rev);
        doNothing().when(recalc).recalculateFrom(user,YearMonth.of(2026,3));jdbc.update("INSERT INTO loan_balance_snapshots(user_id,loan_id,effective_date,amount,reason,created_at) VALUES (?,?,'2026-03-01',11999900,'CORRECTION',?)",user,loan,java.sql.Timestamp.from(clock.instant().plusSeconds(1)));assertThat(patch(b,id,"{}","locked").statusCode()).isEqualTo(409);
    }
    @Test void withdrawalDateMoveCannotAssumeThatSameDaySavingPrecededIt()throws Exception {
        var b=browser();long user=owner(b),loan=loan(user,true,true);jdbc.update("UPDATE loans SET created_at='2026-02-26T00:00:00Z',snapshot_date='2026-02-26',initial_savings_balance=0 WHERE id=?",loan);jdbc.update("UPDATE loan_balance_snapshots SET effective_date='2026-02-26' WHERE loan_id=?",loan);jdbc.update("INSERT INTO repayment_savings(user_id,loan_id,saved_at,amount,created_at) VALUES (?,?,'2026-02-28',100,CURRENT_TIMESTAMP)",user,loan);
        var n=body(loan,true,100);n.put("savings_used_amount",100);long id=ok(b,n,"withdraw").path("payment").path("payment_id").longValue();var before=sources.loanSnapshot(user,loan,LocalDate.of(2026,3,1));
        var r=patch(b,id,"{\"paid_at\":\"2026-02-28\"}","move");assertThat(r.statusCode()).as(r.body()).isEqualTo(409);assertThat(sources.loanSnapshot(user,loan,LocalDate.of(2026,3,1))).isEqualTo(before);
    }

}
