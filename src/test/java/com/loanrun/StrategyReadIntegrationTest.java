package com.loanrun;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import com.loanrun.common.contract.*;
import com.loanrun.common.contract.FinanceModels.*;
import com.loanrun.common.error.ApiException;
import com.loanrun.report.domain.ReportJob;
import com.loanrun.report.repository.ReportJobRepository;
import com.loanrun.report.service.ReportJobStore;
import java.net.*;
import java.net.http.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@Testcontainers
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"loanrun.demo-mode=true","server.servlet.session.cookie.secure=false"})
@Import(ReportJobStoreIntegrationTest.TimeConfig.class)
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class StrategyReadIntegrationTest {
    @Container static final PostgreSQLContainer<?> DB=new PostgreSQLContainer<>("postgres:16.16-bookworm");
    @DynamicPropertySource static void database(DynamicPropertyRegistry r){r.add("spring.datasource.url",DB::getJdbcUrl);r.add("spring.datasource.username",DB::getUsername);r.add("spring.datasource.password",DB::getPassword);}
    @LocalServerPort int port;@Autowired ObjectMapper json;@Autowired ReportJobRepository jobs;@Autowired ReportJobStore store;
    @Autowired StrategyQueryPort strategies;@Autowired RevisionPort revisions;@Autowired UserMutationLock locks;
    @Autowired JdbcTemplate jdbc;@Autowired TransactionTemplate transactions;@Autowired ReportJobStoreIntegrationTest.MutableClock clock;
    @BeforeEach void time(){clock.now.set(Instant.parse("2026-11-17T03:00:00Z"));}
    HttpClient browser(){return HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();}
    HttpResponse<String> call(HttpClient client,String method,String path,String body)throws Exception{return client.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+path))
        .header("Origin","http://localhost:5173").header("Content-Type","application/json").method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());}
    JsonNode data(HttpResponse<String> response)throws Exception{return json.readTree(response.body()).path("data");}
    long user(HttpClient client,boolean pro)throws Exception{long user=data(call(client,"GET","/api/users/me/",null)).path("user_id").longValue();if(pro)plan(client,"PRO");return user;}
    void plan(HttpClient client,String plan)throws Exception{assertThat(call(client,"PATCH","/api/settings/subscription/","{\"plan_type\":\""+plan+"\"}").statusCode()).isEqualTo(200);}
    ReportJob create(long user,Long loan){return transactions.execute(s->{locks.lock(user);return jobs.create(user,loan,revisions.current(user),YearMonth.now(clock),"{}",clock.instant());});}
    void bump(long user){transactions.executeWithoutResult(s->{locks.lock(user);revisions.bump(user,"STRATEGY_TEST");});}
    String version(ReportJob job){return "strategy-"+job.id()+"-"+job.revision();}
    StrategyCandidate candidate(ReportJob job,boolean feasible){return new StrategyCandidate(job.id(),version(job),"FAST","SINGLE_PREFERENCE",job.revision(),clock.instant(),
        List.of(new PlannedAmount(7,AllocationType.EXTRA_PAYMENT,800,null)),new SimulationResult(List.of(),YearMonth.now(clock).plusYears(1).atDay(25),1000L,20L,null,1020L,false,"GROSS_INTEREST",List.of("FEE_UNKNOWN")),feasible,List.of("FEE_UNKNOWN"));}
    void complete(ReportJob job,boolean feasible){var view=json.createObjectNode().put("strategy_available",true).put("strategy_version",version(job)).put("strategy_type","SINGLE_PREFERENCE");
        view.putNull("unavailable_reason");view.putArray("candidates").addObject().put("strategy_code","FAST");
        var result=json.createObjectNode().put("summary","테스트에서만 저장한 결과");
        assertThat(store.complete(store.start(job.id()).orElseThrow(),result,view,List.of(candidate(job,feasible)),"FALLBACK")).isTrue();}
    JsonNode get(HttpClient client,String suffix)throws Exception{var response=call(client,"GET","/api/reports/strategy/"+suffix,null);assertThat(response.statusCode()).as(response.body()).isEqualTo(200);assertThat(json.readTree(response.body()).path("code").asText()).isEqualTo("REPORT_STRATEGY_SUCCESS");return data(response);}
    void error(Runnable action,String code){assertThatThrownBy(action::run).isInstanceOfSatisfying(ApiException.class,ex->assertThat(ex.code()).isEqualTo(code));}
    @Test void proAbsentAndInvalidQueriesPreserveFinancialState()throws Exception{
        var client=browser();long user=user(client,false);assertThat(call(client,"GET","/api/reports/strategy/",null).statusCode()).isEqualTo(403);plan(client,"PRO");long revision=revisions.current(user);
        var view=get(client,"");assertThat(view.path("unavailable_reason").asText()).isEqualTo("REPORT_NOT_READY");assertThat(view.path("candidates")).isEmpty();assertThat(view.path("report_id").isNull()).isTrue();
        for(var id:List.of("0","-1","abc"))assertThat(call(client,"GET","/api/reports/strategy/?report_id="+id,null).statusCode()).isEqualTo(400);
        assertThat(call(client,"GET","/api/reports/strategy/?report_id=999999",null).statusCode()).isEqualTo(404);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM report_jobs WHERE user_id=?",Long.class,user)).isZero();assertThat(revisions.current(user)).isEqualTo(revision);
    }
    @Test void defaultSelectsCompletedHouseholdInsteadOfNewerIndividualOrPendingJob()throws Exception{
        var client=browser();long user=user(client,true);var household=create(user,null);complete(household,true);var individual=create(user,7L);complete(individual,true);
        assertThat(get(client,"").path("report_id").asLong()).isEqualTo(household.id());
        assertThat(get(client,"?report_id="+individual.id()).path("report_id").asLong()).isEqualTo(individual.id());
        bump(user);var pending=create(user,null);long revision=revisions.current(user);
        var old=get(client,"");assertThat(old.path("report_id").asLong()).isEqualTo(household.id());assertThat(old.path("unavailable_reason").asText()).isEqualTo("STALE");assertThat(old.path("candidates")).isEmpty();
        assertThat(get(client,"?report_id="+pending.id()).path("unavailable_reason").asText()).isEqualTo("REPORT_NOT_READY");assertThat(revisions.current(user)).isEqualTo(revision);
    }
    @Test void candidatesRoundTripOnlyForCurrentOwnerVersionCodeAndRevision()throws Exception{
        var client=browser();long user=user(client,true);var job=create(user,null);complete(job,true);long revision=revisions.current(user);
        var selected=strategies.requireCurrentCandidate(user,job.id(),version(job),"FAST",revision);
        assertThat(selected.allocations()).containsExactly(new PlannedAmount(7,AllocationType.EXTRA_PAYMENT,800,null));assertThat(selected.simulation().totalFee()).isNull();assertThat(selected.warnings()).containsExactly("FEE_UNKNOWN");
        assertThat(get(client,"").path("strategy_available").asBoolean()).isTrue();assertThat(get(client,"").path("stale").asBoolean()).isFalse();
        error(()->strategies.requireCurrentCandidate(user,job.id(),"wrong","FAST",revision),"STRATEGY_STALE");
        error(()->strategies.requireCurrentCandidate(user,job.id(),version(job),"STABLE",revision),"STRATEGY_STALE");
        error(()->strategies.requireCurrentCandidate(user,job.id(),version(job),"FAST",revision+1),"STRATEGY_STALE");
        var other=browser();long otherUser=user(other,true);assertThat(call(other,"GET","/api/reports/strategy/?report_id="+job.id(),null).statusCode()).isEqualTo(404);
        error(()->strategies.requireCurrentCandidate(otherUser,job.id(),version(job),"FAST",revision),"REPORT_NOT_FOUND");assertThat(revisions.current(user)).isEqualTo(revision);
    }
    @Test void revisionAndMonthChangesInvalidateStoredSnapshotsWithoutRecalculation()throws Exception{
        var client=browser();long user=user(client,true);var job=create(user,null);complete(job,true);long revision=revisions.current(user);clock.now.updateAndGet(t->t.plus(Duration.ofDays(31)));
        assertThat(get(client,"").path("unavailable_reason").asText()).isEqualTo("STALE");error(()->strategies.requireCurrentCandidate(user,job.id(),version(job),"FAST",revision),"STRATEGY_STALE");
        assertThat(revisions.current(user)).isEqualTo(revision);clock.now.set(Instant.parse("2026-11-17T03:00:00Z"));bump(user);
        error(()->strategies.requireCurrentCandidate(user,job.id(),version(job),"FAST",revisions.current(user)),"STRATEGY_STALE");
    }
    @Test void infeasibleCorruptedRevisionAndProDowngradeAreRejected()throws Exception{
        var client=browser();long user=user(client,true);var job=create(user,null);complete(job,false);long revision=revisions.current(user);
        error(()->strategies.requireCurrentCandidate(user,job.id(),version(job),"FAST",revision),"STRATEGY_STALE");
        jdbc.update("UPDATE strategy_candidates SET feasible=true,data_revision=data_revision+1 WHERE report_id=?",job.id());
        error(()->strategies.requireCurrentCandidate(user,job.id(),version(job),"FAST",revision),"STRATEGY_STALE");plan(client,"FREE");
        assertThat(call(client,"GET","/api/reports/strategy/?report_id="+job.id(),null).statusCode()).isEqualTo(403);error(()->strategies.requireCurrentCandidate(user,job.id(),version(job),"FAST",revision),"PRO_REQUIRED");
    }
    @Test void failedAndMissingStrategyPayloadExposeNoCandidates()throws Exception{
        var client=browser();long user=user(client,true);var failed=create(user,null);store.fail(store.start(failed.id()).orElseThrow(),"REPORT_GENERATION_FAILED");
        assertThat(get(client,"?report_id="+failed.id()).path("candidates")).isEmpty();
        error(()->strategies.requireCurrentCandidate(user,failed.id(),"v","FAST",revisions.current(user)),"STRATEGY_STALE");bump(user);var completed=create(user,null);
        assertThat(store.complete(store.start(completed.id()).orElseThrow(),json.createObjectNode(),null,List.of(),"FALLBACK")).isTrue();
        assertThat(get(client,"").path("unavailable_reason").asText()).isEqualTo("REPORT_NOT_READY");
    }
}
