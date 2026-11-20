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
@Import(PlanSaveIntegrationTest.Config.class)
class PlanSaveIntegrationTest {
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
    @Test void savesImmutableVersionAndRealQueryThenReplaysWithoutBumping()throws Exception {
        var b=browser();long user=owner(b),id=loan(user,false,true),revision=revisions.current(user);var original=sources.loan(user,id).initialPlan();var p=prepared(b,id);
        var r=request(b,"PUT","/api/repayment-plans/",p,"save-one");assertThat(r.statusCode()).as(r.body()).isEqualTo(200);assertThat(json(r).path("code").asText()).isEqualTo("REPAYMENT_PLAN_SAVE_SUCCESS");
        assertThat(revisions.current(user)).isEqualTo(revision+1);assertThat(queries.currentPlans(user,LocalDate.of(2026,3,1)).getFirst().monthlyExtraCash()).isEqualTo(400000);
        assertThat(sources.loan(user,id).initialPlan()).isEqualTo(original);assertThat(jdbc.queryForObject("SELECT count(*) FROM repayment_plan_versions WHERE loan_id=?",Integer.class,id)).isEqualTo(1);
        assertThat(json(request(b,"PUT","/api/repayment-plans/",p,"save-one"))).isEqualTo(json(r));assertThat(revisions.current(user)).isEqualTo(revision+1);
        ((com.fasterxml.jackson.databind.node.ObjectNode)p.path("plans").get(0)).put("extra_monthly_payment",500000);assertThat(request(b,"PUT","/api/repayment-plans/",p,"save-one").statusCode()).isEqualTo(409);
    }
    @Test void staleAndMissingKeyCannotWrite()throws Exception {
        var b=browser();long user=owner(b),id=loan(user,false,true);var p=prepared(b,id);assertThat(request(b,"PUT","/api/repayment-plans/",p,null).statusCode()).isEqualTo(400);assertThat(json(request(b,"PUT","/api/repayment-plans/",input(id),"missing-preview")).path("code").asText()).isEqualTo("PLAN_VALIDATION_ERROR");
        jdbc.update("UPDATE demo_users SET revision=revision+1 WHERE id=?",user);var r=request(b,"PUT","/api/repayment-plans/",p,"stale");assertThat(r.statusCode()).isEqualTo(409);assertThat(json(r).path("code").asText()).isEqualTo("PLAN_PREVIEW_STALE");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM repayment_plan_versions WHERE loan_id=?",Integer.class,id)).isZero();
    }
    @Test void budgetFailureRollsBackVersionsStateRevisionAndIdempotency()throws Exception {
        var b=browser();long user=owner(b),id=loan(user,false,true),revision=revisions.current(user);var p=prepared(b,id);doThrow(com.loanrun.common.error.ApiException.unavailable()).when(recalc).recalculateFrom(user,YearMonth.of(2026,3));
        assertThat(request(b,"PUT","/api/repayment-plans/",p,"rollback").statusCode()).isEqualTo(503);assertThat(revisions.current(user)).isEqualTo(revision);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM repayment_plan_versions WHERE loan_id=?",Integer.class,id)).isZero();assertThat(jdbc.queryForObject("SELECT count(*) FROM plan_application_events WHERE user_id=?",Integer.class,user)).isZero();
        doNothing().when(recalc).recalculateFrom(user,YearMonth.of(2026,3));assertThat(request(b,"PUT","/api/repayment-plans/",p,"rollback").statusCode()).isEqualTo(200);
    }
    @Test void foreignPreviewAndChangedTokenAreRejected()throws Exception {
        var b=browser();var other=browser();long user=owner(b),id=loan(user,false,true);owner(other);var p=prepared(b,id);
        assertThat(request(other,"PUT","/api/repayment-plans/",p,"foreign").statusCode()).isEqualTo(404);((com.fasterxml.jackson.databind.node.ObjectNode)p).put("preview_version","bad");assertThat(request(b,"PUT","/api/repayment-plans/",p,"bad").statusCode()).isEqualTo(409);
    }
    @Autowired RepaymentEngine engine;@Autowired com.loanrun.loan.service.CurrentLoanTermsQueryService current;
    @Test void proStrategyAppliesAtomicallyAndDirectEditKeepsOverallAsManual()throws Exception {
        var b=browser();long user=owner(b),a=loan(user,false,true),second=loan(user,false,true);var body=mapper.readTree("{\"report_id\":1,\"strategy_code\":\"INTEREST_SAVING\",\"strategy_version\":\"v1\"}");
        assertThat(request(b,"PUT","/api/repayment-plans/",((com.fasterxml.jackson.databind.node.ObjectNode)body.deepCopy()).put("preview_version","bad"),"free").statusCode()).isEqualTo(403);
        jdbc.update("UPDATE demo_users SET plan_type='PRO' WHERE id=?",user);long revision=revisions.current(user);var allocations=List.of(new PlannedAmount(a,AllocationType.EXTRA_PAYMENT,400000,1),new PlannedAmount(second,AllocationType.EXTRA_PAYMENT,0,2));
        var simulation=engine.simulate(new SimulationInput(List.of(current.current(user,a).terms(),current.current(user,second).terms()),LocalDate.of(2026,3,1),1200,Map.of(),Map.of(),null,"NONE"));
        when(candidates.requireCurrentCandidate(user,1,"v1","INTEREST_SAVING",revision)).thenReturn(new StrategyCandidate(1,"v1","INTEREST_SAVING","MULTI",revision,clock.instant(),allocations,simulation,true,List.of()));
        var preview=request(b,"POST","/api/repayment-plans/preview/",body,null);assertThat(preview.statusCode()).as(preview.body()).isEqualTo(200);((com.fasterxml.jackson.databind.node.ObjectNode)body).put("preview_version",json(preview).path("data").path("preview_version").asText());
        var saved=request(b,"PUT","/api/repayment-plans/",body,"strategy");assertThat(saved.statusCode()).as(saved.body()).isEqualTo(200);assertThat(queries.overallState(user).exists()).isTrue();assertThat(queries.plannedAllocations(user,YearMonth.of(2026,3)).getFirst().priority()).isEqualTo(1);
        var direct=prepared(b,a);assertThat(request(b,"PUT","/api/repayment-plans/",direct,"direct").statusCode()).isEqualTo(200);assertThat(queries.overallState(user).strategyCode()).isEqualTo("MANUAL");assertThat(queries.overallState(user).exists()).isTrue();
        jdbc.update("UPDATE demo_users SET plan_type='FREE' WHERE id=?",user);long currentRevision=revisions.current(user);
        var denied=request(b,"PUT","/api/repayment-plans/",body,"strategy");assertThat(denied.statusCode()).as(denied.body()).isEqualTo(403);assertThat(json(denied).path("code").asText()).isEqualTo("PRO_REQUIRED");
        var replay=request(b,"PUT","/api/repayment-plans/",direct,"direct");assertThat(replay.statusCode()).as(replay.body()).isEqualTo(200);var data=json(replay).path("data");assertThat(data.path("strategy_code").isNull()).isTrue();assertThat(data.path("overall_plan_exists").asBoolean()).isFalse();for(var row:data.path("plans"))assertThat(row.path("priority").isNull()).isTrue();for(var row:data.path("allocations"))assertThat(row.path("priority").isNull()).isTrue();assertThat(revisions.current(user)).isEqualTo(currentRevision);
    }

}
