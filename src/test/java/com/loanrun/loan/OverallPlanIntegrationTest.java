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
@Import(OverallPlanIntegrationTest.Config.class)
class OverallPlanIntegrationTest {
    @Container static final PostgreSQLContainer<?> DB=new PostgreSQLContainer<>("postgres:16.16-bookworm");
    @DynamicPropertySource static void db(DynamicPropertyRegistry r){r.add("spring.datasource.url",DB::getJdbcUrl);r.add("spring.datasource.username",DB::getUsername);r.add("spring.datasource.password",DB::getPassword);}
    @TestConfiguration static class Config {@Bean @Primary Clock testClock(){return Clock.fixed(Instant.parse("2026-03-01T00:00:00Z"),ZoneId.of("Asia/Seoul"));}}
    @MockitoBean BudgetRecalculationPort recalc;
    @MockitoBean com.loanrun.budget.service.BudgetReadService budget;
    @MockitoBean StrategyQueryPort candidates;
    @MockitoBean PlanQueryPort overall;
    @Autowired JdbcTemplate jdbc;@Autowired ObjectMapper mapper;@Autowired LoanRegistrationService registration;
    @Autowired LoanSourceRepository sources;@Autowired PlanViewQueryService views;@Autowired RevisionPort revisions;@Autowired Clock clock;
    @LocalServerPort int port;
    @BeforeEach void setup()throws Exception {
        if(jdbc.queryForObject("SELECT to_regclass('payment_due_archives')",String.class)==null)jdbc.execute(Files.readString(Path.of("docs/sql/payment_due_archives_proposal.sql")));
        when(overall.overallState(anyLong())).thenAnswer(i->queries.overallState(i.getArgument(0)));
        when(overall.plannedAllocations(anyLong(),any())).thenAnswer(i->queries.plannedAllocations(i.getArgument(0),i.getArgument(1)));
        if(jdbc.queryForObject("SELECT to_regclass('plan_application_events')",String.class)==null)jdbc.execute(Files.readString(Path.of("docs/sql/plan_application_events_proposal.sql")));
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
    @Autowired SavedPlanQueryService queries;
    HttpResponse<String> request(HttpClient b,String method,String path,JsonNode body,String key)throws Exception {
        var r=HttpRequest.newBuilder(URI.create("http://localhost:"+port+path)).header("Origin","http://localhost:5173").header("Content-Type","application/json");if(key!=null)r.header("Idempotency-Key",key);
        return b.send(r.method(method,HttpRequest.BodyPublishers.ofString(body.toString())).build(),HttpResponse.BodyHandlers.ofString());
    }
    com.fasterxml.jackson.databind.node.ObjectNode input(long id)throws Exception{return (com.fasterxml.jackson.databind.node.ObjectNode)mapper.readTree("{\"plans\":[{\"loan_id\":"+id+",\"mode\":\"EXTRA_AMOUNT\",\"extra_monthly_payment\":400000}]}");}
    JsonNode prepared(HttpClient b,long id)throws Exception {var p=input(id);var r=request(b,"POST","/api/repayment-plans/preview/",p,null);assertThat(r.statusCode()).as(r.body()).isEqualTo(200);p.put("preview_version",json(r).path("data").path("preview_version").asText());return p;}
    @Autowired com.loanrun.repaymentplan.repository.PlanApplicationRepository applications;
    JsonNode overall(HttpClient b)throws Exception{var r=get(b,"/api/plans/overall/");assertThat(r.statusCode()).as(r.body()).isEqualTo(200);assertThat(json(r).path("code").asText()).isEqualTo("OVERALL_PLAN_SUCCESS");return json(r).path("data");}
    long pro(HttpClient b)throws Exception{long user=owner(b);jdbc.update("UPDATE demo_users SET plan_type='PRO' WHERE id=?",user);return user;}
    @Test void freeIsRejectedAndEmptyProIsSingleLoan()throws Exception {
        var b=browser();assertThat(get(b,"/api/plans/overall/").statusCode()).isEqualTo(403);pro(b);var d=overall(b);assertThat(d.path("state").asText()).isEqualTo("SINGLE_LOAN");assertThat(d.path("remaining_principal").longValue()).isZero();assertThat(d.path("allocations").size()).isZero();
    }
    @Test void oneLoanAndUnappliedMultiHaveExactStatesAndOwnedAggregates()throws Exception {
        var b=browser();long user=pro(b);loan(user,false,true);assertThat(overall(b).path("state").asText()).isEqualTo("SINGLE_LOAN");loan(user,true,true);
        var other=browser();loan(owner(other),false,true);var d=overall(b);assertThat(d.path("state").asText()).isEqualTo("EMPTY");assertThat(d.path("remaining_principal").longValue()).isEqualTo(24000000);assertThat(d.path("monthly_saving_amount").longValue()).isEqualTo(1000000);assertThat(d.path("allocations").size()).isEqualTo(2);
    }
    @Test void availableStatePrioritiesAndNeedsReviewUsePersistedApplication()throws Exception {
        var b=browser();long user=pro(b),a=loan(user,false,true),second=loan(user,false,true),revision=revisions.current(user);
        applications.append(user,revision,true,"INTEREST_SAVING",List.of(new PlannedAmount(a,AllocationType.EXTRA_PAYMENT,0,1),new PlannedAmount(second,AllocationType.EXTRA_PAYMENT,0,2)),clock.instant());
        var d=overall(b);assertThat(d.path("state").asText()).isEqualTo("AVAILABLE");assertThat(d.path("strategy_code").asText()).isEqualTo("INTEREST_SAVING");assertThat(d.path("needs_review").asBoolean()).isFalse();assertThat(d.path("allocations").get(0).path("priority").intValue()).isEqualTo(1);
        jdbc.update("UPDATE demo_users SET revision=revision+1 WHERE id=?",user);assertThat(overall(b).path("needs_review").asBoolean()).isTrue();
    }
    @Test void repeatedReadPreservesSourcesAndRejectsQueryInjection()throws Exception {
        var b=browser();long user=pro(b),id=loan(user,false,true),revision=revisions.current(user);var before=sources.loanSnapshot(user,id,LocalDate.of(2026,3,1));assertThat(overall(b)).isEqualTo(overall(b));assertThat(revisions.current(user)).isEqualTo(revision);assertThat(sources.loanSnapshot(user,id,LocalDate.of(2026,3,1))).isEqualTo(before);assertThat(get(b,"/api/plans/overall/?user_id=1").statusCode()).isEqualTo(400);
    }
    @Test void nextCycleGoalsAreNotReplacedWithTheCurrentCalendarMonthsOldTargets()throws Exception {
        var b=browser();long user=pro(b),a=loan(user,false,true);loan(user,false,true);long due=jdbc.queryForObject("SELECT id FROM due_items WHERE loan_id=?",Long.class,a);
        var payment=mapper.createObjectNode();payment.put("loan_id",a);payment.put("paid_at","2026-03-01");payment.put("amount",1260000);payment.put("payment_type","REGULAR");payment.put("due_item_id",due);var paid=request(b,"POST","/api/payments/",payment,"pay-first");assertThat(paid.statusCode()).as(paid.body()).isEqualTo(201);
        var saved=request(b,"PUT","/api/repayment-plans/",prepared(b,a),"next-cycle");assertThat(saved.statusCode()).as(saved.body()).isEqualTo(200);var d=overall(b);assertThat(d.path("monthly_extra_amount").longValue()).isEqualTo(400000);long allocation=0;for(var row:d.path("allocations"))allocation+=row.path("amount").longValue();assertThat(allocation).isEqualTo(400000);
        assertThat(queries.plannedAllocations(user,YearMonth.of(2026,3)).stream().filter(x->x.loanId()==a).findFirst().orElseThrow().cashAmount()).isZero(); // This month's old target is independently preserved.
    }

}
