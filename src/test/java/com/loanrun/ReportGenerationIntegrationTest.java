package com.loanrun;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.*;
import com.loanrun.common.contract.*;
import com.loanrun.common.contract.FinanceModels.*;
import com.loanrun.common.contract.PlanPerformanceQueryPort.Performance;
import com.loanrun.common.error.ApiException;
import com.loanrun.report.domain.ReportInput;
import com.loanrun.report.repository.ReportJobRepository;
import com.loanrun.report.service.*;
import java.math.BigDecimal;
import java.net.*;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.*;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.event.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@Testcontainers
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"loanrun.demo-mode=true","server.servlet.session.cookie.secure=false","loanrun.reports.max-attempts-per-hour=3","loanrun.reports.worker-enabled=false"})
@Import({ReportJobStoreIntegrationTest.TimeConfig.class,ReportGenerationIntegrationTest.EventsConfig.class})
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class ReportGenerationIntegrationTest {
    @Container static final PostgreSQLContainer<?> DB=new PostgreSQLContainer<>("postgres:16.16-bookworm");
    @DynamicPropertySource static void database(DynamicPropertyRegistry r){r.add("spring.datasource.url",DB::getJdbcUrl);r.add("spring.datasource.username",DB::getUsername);r.add("spring.datasource.password",DB::getPassword);}
    static class Events {
        final Queue<Long> committed=new ConcurrentLinkedQueue<>();final AtomicBoolean fail=new AtomicBoolean();
        @EventListener public void gate(ReportGenerationService.Requested event){if(fail.get())throw new IllegalStateException("Controlled enqueue failure");}
        @TransactionalEventListener(phase=TransactionPhase.AFTER_COMMIT) public void committed(ReportGenerationService.Requested event){committed.add(event.reportId());}
    }
    @TestConfiguration static class EventsConfig {@Bean Events reportEvents(){return new Events();}}
    @LocalServerPort int port;@Autowired ObjectMapper mapper;@Autowired JdbcTemplate jdbc;@Autowired ReportJobRepository jobs;@Autowired ReportJobStore store;
    @Autowired RevisionPort revisions;@Autowired UserMutationLock locks;@Autowired TransactionTemplate transactions;@Autowired Events events;
    @Autowired ReportJobStoreIntegrationTest.MutableClock clock;
    @MockitoBean LoanQueryPort loans;@MockitoBean PlanQueryPort plans;@MockitoBean BudgetQueryPort budgets;
    @MockitoBean ReportHistoryQueryPort history;@MockitoBean PlanPerformanceQueryPort performance;@MockitoBean RepaymentEngine engine;
    final AtomicLong owner=new AtomicLong();final YearMonth month=YearMonth.of(2026,11);
    LoanTerms loan(){return new LoanTerms(7,owner.get(),"가상",LoanType.CREDIT,100000,100000,month.atDay(17),BigDecimal.valueOf(6),RateType.FIXED,
        RepaymentMethod.EQUAL_PAYMENT,null,null,month.atDay(25),25,month.plusYears(1).atDay(25),1000L,1000L,null,PrepaymentPolicy.KEEP_PAYMENT,FeeMethod.NONE,BigDecimal.ZERO,null,null,0,0,LoanStatus.ACTIVE,null);}
    List<HistoryMonth> observed(Long loanId){return List.of(new HistoryMonth(month.minusMonths(1),1000,0,loanId==null?100:50,0,10,0,0,0,0,true));}
    @BeforeEach void facts(){
        clock.now.set(Instant.parse("2026-11-17T03:00:00Z"));owner.set(0);events.committed.clear();events.fail.set(false);
        when(loans.activeLoans(anyLong(),any())).thenAnswer(i->(long)i.getArgument(0)==owner.get()?List.of(loan()):List.of());
        when(loans.dueSummary(anyLong(),any())).thenAnswer(i->new DueSummary(i.getArgument(1),1000,0,0,1000,0,month.atDay(25),1,0,0,0,0));
        when(loans.paymentSummary(anyLong(),any())).thenAnswer(i->new PaymentSummary(i.getArgument(1),0,0,0,0,0,0,0,true,0,0,Map.of()));
        when(loans.savingSummary(anyLong(),any())).thenAnswer(i->new SavingSummary(i.getArgument(1),0,0,0,0,true,0,Map.of()));
        when(plans.currentPlans(anyLong(),any())).thenReturn(List.of(new PlanSnapshot(7,true,month.plusYears(1).atDay(25),null,10000,0,0,"EXTRA_AMOUNT",null,month.atDay(1),1)));
        when(plans.overallState(anyLong())).thenReturn(new OverallState(false,"NONE","NONE",null));
        when(budgets.capacity(anyLong(),any())).thenReturn(new BudgetCapacity(month,200000L,200000L,0,200000L,0,0,200000L,0,0L,"ASSUMED","OPEN",List.of()));
        when(budgets.financeSnapshot(anyLong(),any())).thenAnswer(i->{YearMonth m=i.getArgument(1);Long actual=null;if(m.equals(month))actual=1000000L;else if(m.equals(month.minusMonths(1)))actual=0L;
            return new FinanceSnapshot(m,IncomeType.REGULAR,1000000L,actual,100000L,100000L,100000L,null,100000L,0L,null,false,"DEFAULT");});
        when(history.history(anyLong(),nullable(Long.class),any())).thenAnswer(i->observed(i.getArgument(1)));
        when(performance.performance(anyLong(),any())).thenReturn(new Performance(null,null,null));
    }
    HttpClient browser(){return HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();}
    HttpResponse<String> call(HttpClient c,String method,String path,String body)throws Exception{return c.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+path))
        .header("Origin","http://localhost:5173").header("Content-Type","application/json").method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());}
    JsonNode json(HttpResponse<String> r)throws Exception{return mapper.readTree(r.body());}
    void plan(HttpClient c,String type)throws Exception{assertThat(call(c,"PATCH","/api/settings/subscription/","{\"plan_type\":\""+type+"\"}").statusCode()).isEqualTo(200);}
    long initialize(HttpClient c,boolean own,boolean pro)throws Exception{long user=json(call(c,"GET","/api/users/me/",null)).path("data").path("user_id").longValue();if(own)owner.set(user);if(pro)plan(c,"PRO");return user;}
    HttpResponse<String> generate(HttpClient c,String body)throws Exception{return call(c,"POST","/api/reports/",body);}
    long count(String table,long user){return jdbc.queryForObject("SELECT count(*) FROM "+table+" WHERE user_id=?",Long.class,user);}
    @Test void concurrentRequestsCommitOneSnapshotAndReusePendingRunningAndCompletedJobs()throws Exception{
        var c=browser();long user=initialize(c,true,true),revision=revisions.current(user);long id;
        try(var executor=Executors.newFixedThreadPool(2)){
            var start=new CountDownLatch(1);Callable<HttpResponse<String>> request=()->{start.await();return generate(c,"{}");};
            var a=executor.submit(request);var b=executor.submit(request);start.countDown();var ar=a.get(20,TimeUnit.SECONDS);var br=b.get(20,TimeUnit.SECONDS);
            assertThat(ar.statusCode()).as(ar.body()).isEqualTo(202);assertThat(br.statusCode()).isEqualTo(202);id=json(ar).path("data").path("report_id").longValue();assertThat(json(br).path("data").path("report_id").longValue()).isEqualTo(id);
        }
        assertThat(count("report_jobs",user)).isEqualTo(1);assertThat(count("report_attempts",user)).isEqualTo(1);assertThat(events.committed).containsExactly(id);
        var input=mapper.readValue(jobs.owned(user,id).inputPayload(),ReportInput.class);assertThat(input.actualIncomes()).containsEntry(month.minusMonths(1),0L).containsEntry(month.minusMonths(2),null);
        assertThat(input.history().getFirst().regularPrincipal()).isEqualTo(100);assertThat(input.revision()).isEqualTo(revision);verifyNoInteractions(engine);
        var lease=store.start(id).orElseThrow();assertThat(json(generate(c,"{}")).path("data").path("status").asText()).isEqualTo("RUNNING");
        assertThat(store.complete(lease,mapper.createObjectNode(),null,List.of(),"FALLBACK")).isTrue();
        when(history.history(anyLong(),nullable(Long.class),any())).thenThrow(ApiException.unavailable());var completed=generate(c,"{}");assertThat(completed.statusCode()).isEqualTo(200);
        assertThat(json(completed).path("data").path("status").asText()).isEqualTo("COMPLETED");assertThat(generate(c,"{\"force\":true}").statusCode()).isEqualTo(400);
        assertThat(events.committed).hasSize(1);assertThat(revisions.current(user)).isEqualTo(revision);
    }
    @Test void loanScopeCapturesItsOwnHistoryAndRejectsForeignOrIncompleteInput()throws Exception{
        var c=browser();long user=initialize(c,true,true);var scoped=generate(c,"{\"loan_id\":7}");assertThat(scoped.statusCode()).isEqualTo(202);
        long id=json(scoped).path("data").path("report_id").longValue();assertThat(mapper.readValue(jobs.owned(user,id).inputPayload(),ReportInput.class).history().getFirst().regularPrincipal()).isEqualTo(50);
        assertThat(generate(c,"{}").statusCode()).isEqualTo(202);assertThat(count("report_jobs",user)).isEqualTo(2);
        assertThat(generate(c,"{\"loan_id\":999}").statusCode()).isEqualTo(404);
        var other=browser();long another=initialize(other,false,true);assertThat(generate(other,"{\"loan_id\":7}").statusCode()).isEqualTo(404);
        var incomplete=generate(other,"{}");assertThat(incomplete.statusCode()).isEqualTo(400);assertThat(json(incomplete).path("code").asText()).isEqualTo("REPORT_INPUT_INCOMPLETE");assertThat(count("report_jobs",another)).isZero();
    }
    @Test void failedRetryRequiresForceAndSixtySecondsWithoutCreatingANewJob()throws Exception{
        var c=browser();long user=initialize(c,true,true);long id=json(generate(c,"{}")).path("data").path("report_id").longValue();store.fail(store.start(id).orElseThrow(),"SERVICE_UNAVAILABLE");
        assertThat(generate(c,"{}").statusCode()).isEqualTo(200);assertThat(generate(c,"{\"force\":true}").statusCode()).isEqualTo(429);
        clock.now.updateAndGet(t->t.plusSeconds(59));assertThat(generate(c,"{\"force\":true}").statusCode()).isEqualTo(429);clock.now.updateAndGet(t->t.plusSeconds(1));
        var retried=generate(c,"{\"force\":true}");assertThat(retried.statusCode()).isEqualTo(202);assertThat(json(retried).path("data").path("report_id").longValue()).isEqualTo(id);
        assertThat(count("report_jobs",user)).isEqualTo(1);assertThat(count("report_attempts",user)).isEqualTo(2);assertThat(events.committed).containsExactly(id,id);
    }
    @Test void perUserLimitSurvivesRevisionAndProChangesButExpiresAfterTheWindow()throws Exception{
        var c=browser();long user=initialize(c,true,true);
        for(int i=0;i<3;i++){transactions.executeWithoutResult(s->{locks.lock(user);revisions.bump(user,"TEST_INPUT_CHANGED");});assertThat(generate(c,"{}").statusCode()).isEqualTo(202);}
        transactions.executeWithoutResult(s->{locks.lock(user);revisions.bump(user,"TEST_INPUT_CHANGED");});assertThat(generate(c,"{}").statusCode()).isEqualTo(429);
        plan(c,"FREE");plan(c,"PRO");assertThat(generate(c,"{}").statusCode()).isEqualTo(429);assertThat(count("report_attempts",user)).isEqualTo(3);
        clock.now.updateAndGet(t->t.plusSeconds(3601));assertThat(generate(c,"{}").statusCode()).isEqualTo(202);
    }
    @Test void sourceOrPreCommitFailureRollsBackJobAttemptsAndDispatch()throws Exception{
        var c=browser();long user=initialize(c,true,true);long revision=revisions.current(user);
        when(history.history(anyLong(),nullable(Long.class),any())).thenThrow(ApiException.unavailable());assertThat(generate(c,"{}").statusCode()).isEqualTo(503);
        doAnswer(i->observed(i.getArgument(1))).when(history).history(anyLong(),nullable(Long.class),any());events.fail.set(true);assertThat(generate(c,"{}").statusCode()).isEqualTo(500);
        assertThat(count("report_jobs",user)).isZero();assertThat(count("report_attempts",user)).isZero();assertThat(events.committed).isEmpty();assertThat(revisions.current(user)).isEqualTo(revision);
        events.fail.set(false);assertThat(generate(c,"{}").statusCode()).isEqualTo(202);assertThat(events.committed).hasSize(1);
    }
    @Test void proAndStrictRequestValidationRejectMutationsBeforeCapture()throws Exception{
        var c=browser();long user=initialize(c,true,false);assertThat(generate(c,"{}").statusCode()).isEqualTo(403);plan(c,"PRO");
        for(String body:List.of("{\"loan_id\":0}","{\"loan_id\":1.5}","{\"force\":null}","{\"force\":\"true\"}","{\"user_id\":1}","{\"force\":true}"))assertThat(generate(c,body).statusCode()).isEqualTo(400);
        assertThat(count("report_jobs",user)).isZero();verifyNoInteractions(history,engine);
    }
}
