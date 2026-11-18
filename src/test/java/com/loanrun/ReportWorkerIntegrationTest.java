package com.loanrun;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loanrun.common.contract.*;
import com.loanrun.common.contract.FinanceModels.*;
import com.loanrun.common.contract.PlanPerformanceQueryPort.Performance;
import com.loanrun.report.domain.*;
import com.loanrun.report.dto.ComparisonSettings;
import com.loanrun.report.repository.ReportJobRepository;
import com.loanrun.report.service.*;
import com.loanrun.risk.domain.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@Testcontainers
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties="loanrun.reports.worker-enabled=true")
@Import(ReportJobStoreIntegrationTest.TimeConfig.class)
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class ReportWorkerIntegrationTest {
    @Container static final PostgreSQLContainer<?> DB=new PostgreSQLContainer<>("postgres:16.16-bookworm");
    @DynamicPropertySource static void database(DynamicPropertyRegistry r){r.add("spring.datasource.url",DB::getJdbcUrl);r.add("spring.datasource.username",DB::getUsername);r.add("spring.datasource.password",DB::getPassword);}
    @Autowired ReportJobRepository jobs;@Autowired ReportJobStore store;@Autowired UserMutationLock locks;@Autowired UserAccessPort access;
    @Autowired RevisionPort revisions;@Autowired TransactionTemplate transactions;@Autowired ApplicationEventPublisher events;@Autowired ObjectMapper mapper;@Autowired JdbcTemplate jdbc;
    @Autowired ReportJobStoreIntegrationTest.MutableClock clock;
    @MockitoBean ReportCalculation calculation;
    final YearMonth month=YearMonth.of(2026,11);
    @BeforeEach void calculation(){clock.now.set(Instant.parse("2026-11-17T03:00:00Z"));when(calculation.calculate(any(),anyLong())).thenAnswer(i->{
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(Thread.currentThread().getName()).startsWith("report-worker-");
        return result();});}
    ReportCalculation.Calculated result(){var node=mapper.createObjectNode().put("summary","서버 집계 설명").put("strategy_available",false).put("strategy_type","NONE");
        node.putNull("pace_analysis");node.putNull("comparison");node.putArray("payoff_scenarios");node.putArray("suggestions");node.putNull("error");
        return new ReportCalculation.Calculated(node,null,List.of(),"FALLBACK");}
    long user(){return jdbc.queryForObject("INSERT INTO demo_users(session_digest,plan_type,created_at,updated_at) VALUES (?,'PRO',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP) RETURNING id",
        Long.class,UUID.randomUUID().toString().replace("-","").repeat(2));}
    ReportInput input(long user){
        var loan=new LoanTerms(7,user,"가상",LoanType.CREDIT,100000,100000,month.atDay(17),BigDecimal.valueOf(6),RateType.FIXED,RepaymentMethod.EQUAL_PAYMENT,null,null,
            month.atDay(25),25,month.plusYears(1).atDay(25),1000L,1000L,null,PrepaymentPolicy.KEEP_PAYMENT,FeeMethod.NONE,BigDecimal.ZERO,null,null,0,0,LoanStatus.ACTIVE,null);
        var finance=new FinanceSnapshot(month,IncomeType.REGULAR,1000000L,null,100000L,100000L,100000L,null,100000L,0L,null,false,"DEFAULT");
        var capacity=new BudgetCapacity(month,200000L,200000L,0,200000L,0,0,200000L,0,0L,"FORECAST","OPEN",List.of());
        var facts=new RiskInputs(user,month.atDay(17),month,List.of(loan),List.of(new PlanSnapshot(7,true,month.plusYears(1).atDay(25),null,10000,0,0,"EXTRA_AMOUNT",null,month.atDay(1),1)),
            finance,capacity,new DueSummary(month,1000,0,0,1000,0,month.atDay(25),1,0,0,0,0),10000L,0L,BigDecimal.ZERO);
        var source=new RiskScenarioInput(facts,new PaymentSummary(month,0,0,0,0,0,0,0,true,0,0,Map.of()),new SavingSummary(month,0,0,0,0,true,0,Map.of()));
        return new ReportInput(user,null,0,month,source,List.of(),Map.of(),ComparisonSettings.defaults(),new OverallState(false,"NONE","NONE",null),new Performance(null,null,null),List.of());
    }
    String encode(ReportInput input){try{return mapper.writeValueAsString(input);}catch(Exception ex){throw new IllegalStateException(ex);}}
    ReportJob create(long user,ReportInput input,boolean publish){return transactions.execute(s->{locks.lock(user);var job=jobs.create(user,null,0,month,encode(input),clock.instant());
        if(publish)events.publishEvent(new ReportGenerationService.Requested(job.id()));return job;});}
    ReportJob await(long id,ReportJob.Status status)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);ReportJob value;
        do{value=jobs.internal(id).orElseThrow();if(value.status()==status)return value;Thread.sleep(20);}while(System.nanoTime()<end);
        assertThat(value.status()).as("worker error: %s",value.errorCode()).isEqualTo(status);return value;}
    @Test void afterCommitDispatchComputesOutsideTransactionsAndDuplicateDeliveryRunsOnce()throws Exception{
        long user=user();var job=transactions.execute(s->{locks.lock(user);var created=jobs.create(user,null,0,month,encode(input(user)),clock.instant());
            events.publishEvent(new ReportGenerationService.Requested(created.id()));events.publishEvent(new ReportGenerationService.Requested(created.id()));return created;});
        var completed=await(job.id(),ReportJob.Status.COMPLETED);assertThat(mapper.readTree(completed.resultPayload()).path("stale").asBoolean()).isFalse();
        verify(calculation,timeout(1000).times(1)).calculate(any(),eq(job.id()));assertThat(revisions.current(user)).isZero();
    }
    @Test void rollbackDoesNotDispatchAnUncommittedJob()throws Exception{
        long user=user();long[] id={0};assertThatThrownBy(()->transactions.executeWithoutResult(s->{locks.lock(user);var job=jobs.create(user,null,0,month,encode(input(user)),clock.instant());
            id[0]=job.id();events.publishEvent(new ReportGenerationService.Requested(job.id()));throw new IllegalStateException("Controlled rollback");})).isInstanceOf(IllegalStateException.class);
        Thread.sleep(100);assertThat(jobs.internal(id[0])).isEmpty();verifyNoInteractions(calculation);
    }
    @Test void missingCalculationAndWrongOwnerPayloadFailWithoutPlaceholderResults()throws Exception{
        long user=user();var noCalculator=create(user,input(user),false);var empty=new StaticListableBeanFactory();
        var missing=new ReportWorker(store,empty.getBeanProvider(ReportCalculation.class),access,mapper);assertThat(missing.run(noCalculator.id())).isFalse();
        assertThat(jobs.owned(user,noCalculator.id()).errorCode()).isEqualTo("SERVICE_UNAVAILABLE");assertThat(jobs.owned(user,noCalculator.id()).resultPayload()).isNull();
        long another=user();var corrupt=create(another,input(user),true);assertThat(await(corrupt.id(),ReportJob.Status.FAILED).errorCode()).isEqualTo("REPORT_INPUT_INVALID");verifyNoInteractions(calculation);
    }
    @Test void calculationFailureStoresOnlyFixedErrorCodeAndRetryTime()throws Exception{
        doThrow(new IllegalStateException("controlled detail must not be stored")).when(calculation).calculate(any(),anyLong());
        long user=user();var failed=await(create(user,input(user),true).id(),ReportJob.Status.FAILED);
        assertThat(failed.errorCode()).isEqualTo("REPORT_GENERATION_FAILED");assertThat(failed.resultPayload()).isNull();assertThat(failed.nextRetryAt()).isEqualTo(clock.instant().plusSeconds(60));
    }
    @Test void revisionChangeDuringComputeIsPersistedAsStale()throws Exception{
        var entered=new CountDownLatch(1);var proceed=new CountDownLatch(1);
        doAnswer(i->{assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();entered.countDown();
            if(!proceed.await(10,TimeUnit.SECONDS))throw new IllegalStateException("Test calculation gate expired");return result();}).when(calculation).calculate(any(),anyLong());
        long user=user();var job=create(user,input(user),true);
        try{assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();transactions.executeWithoutResult(s->{locks.lock(user);revisions.bump(user,"TEST_SOURCE_CHANGED");});}
        finally{proceed.countDown();}
        var completed=await(job.id(),ReportJob.Status.COMPLETED);assertThat(mapper.readTree(completed.resultPayload()).path("stale").asBoolean()).isTrue();
    }
}
