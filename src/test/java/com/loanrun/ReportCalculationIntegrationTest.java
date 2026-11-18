package com.loanrun;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import com.fasterxml.jackson.databind.*;
import com.loanrun.common.contract.*;
import com.loanrun.common.contract.FinanceModels.*;
import com.loanrun.common.contract.PlanPerformanceQueryPort.Performance;
import com.loanrun.common.error.ApiException;
import com.loanrun.report.client.ExplanationClient;
import com.loanrun.report.domain.*;
import com.loanrun.report.repository.ReportJobRepository;
import com.loanrun.report.service.*;
import com.loanrun.risk.service.RiskSimulationService;
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
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.*;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@Testcontainers
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"loanrun.demo-mode=true","server.servlet.session.cookie.secure=false",
    "loanrun.reports.worker-enabled=true","loanrun.reports.recovery-interval-ms=3600000","OPENAI_ENABLED=false"})
@Import(ReportJobStoreIntegrationTest.TimeConfig.class)
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class ReportCalculationIntegrationTest {
    @Container static final PostgreSQLContainer<?> DB=new PostgreSQLContainer<>("postgres:16.16-bookworm");
    @DynamicPropertySource static void database(DynamicPropertyRegistry r){r.add("spring.datasource.url",DB::getJdbcUrl);r.add("spring.datasource.username",DB::getUsername);r.add("spring.datasource.password",DB::getPassword);}
    @LocalServerPort int port;@Autowired ObjectMapper json;@Autowired ReportJobRepository jobs;@Autowired JdbcTemplate jdbc;
    @Autowired RevisionPort revisions;@Autowired ReportCalculation calculation;@Autowired RiskSimulationService risks;@Autowired StrategyCalculator strategies;
    @Autowired ReportJobStoreIntegrationTest.MutableClock clock;
    @MockitoBean LoanQueryPort loans;@MockitoBean PlanQueryPort plans;@MockitoBean BudgetQueryPort budgets;
    @MockitoBean ReportHistoryQueryPort history;@MockitoBean PlanPerformanceQueryPort performance;
    @MockitoSpyBean RepaymentEngine engine;@MockitoSpyBean ExplanationClient explanations;
    final AtomicLong owner=new AtomicLong();final AtomicBoolean two=new AtomicBoolean(),unobserved=new AtomicBoolean(),zeros=new AtomicBoolean();
    final List<PlannedAmount> overrides=new CopyOnWriteArrayList<>();final YearMonth month=YearMonth.of(2026,11);
    LoanTerms terms(long id){return new LoanTerms(id,owner.get(),"가상 대출 "+id,LoanType.CREDIT,120000,120000,month.atDay(17),BigDecimal.ZERO,RateType.FIXED,
        RepaymentMethod.EQUAL_PRINCIPAL,null,null,month.atDay(25),25,month.plusMonths(11).atDay(25),10000L,null,10000L,PrepaymentPolicy.KEEP_PAYMENT,FeeMethod.NONE,BigDecimal.ZERO,null,null,0,0,LoanStatus.ACTIVE,null);}
    List<LoanTerms> active(long user){return user!=owner.get()?List.of():two.get()?List.of(terms(1),terms(2)):List.of(terms(1));}
    @BeforeEach void source(){
        owner.set(0);two.set(false);unobserved.set(false);zeros.set(false);overrides.clear();clock.now.set(Instant.parse("2026-11-17T03:00:00Z"));
        doAnswer(i->{assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();return i.callRealMethod();}).when(engine).simulate(any());
        doAnswer(i->{assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();return i.callRealMethod();}).when(explanations).explain(any());
        doAnswer(i->active(i.getArgument(0))).when(loans).activeLoans(anyLong(),any());
        doAnswer(i->active(i.getArgument(0)).stream().filter(l->l.loanId()==(long)i.getArgument(1)).findFirst().orElseThrow(()->new ApiException(org.springframework.http.HttpStatus.NOT_FOUND,"LOAN_NOT_FOUND","대출을 찾을 수 없습니다.")))
            .when(loans).terms(anyLong(),anyLong(),any());
        doAnswer(i->new DueSummary(month,two.get()?20000:10000,0,0,two.get()?20000:10000,0,month.atDay(25),two.get()?2:1,0,0,0,0)).when(loans).dueSummary(anyLong(),any());
        doReturn(new PaymentSummary(month,0,0,0,0,0,0,0,true,0,0,Map.of())).when(loans).paymentSummary(anyLong(),any());
        doReturn(new SavingSummary(month,0,0,0,0,true,0,Map.of())).when(loans).savingSummary(anyLong(),any());
        doAnswer(i->active(i.getArgument(0)).stream().map(l->new PlanSnapshot(l.loanId(),true,month.plusMonths(18).atDay(25),null,l.loanId()==1?5000:10000,0,0,"AMOUNT",null,month.atDay(1),1)).toList()).when(plans).currentPlans(anyLong(),any());
        doReturn(new OverallState(false,"NONE","NONE",null)).when(plans).overallState(anyLong());
        doReturn(new Performance(null,null,null)).when(performance).performance(anyLong(),any());
        doReturn(new BudgetCapacity(month,30000L,30000L,0,30000L,0,0,30000L,0,0L,"CONFIRMED","OPEN",List.of())).when(budgets).capacity(anyLong(),any());
        doAnswer(i->{YearMonth m=i.getArgument(1);Long income=null;if(m.equals(month))income=200000L;else if(m.equals(month.minusMonths(1)))income=zeros.get()?0L:100000L;
            long discretionary=two.get()?40000:50000;return new FinanceSnapshot(m,IncomeType.REGULAR,200000L,income,100000L,10000L,discretionary,100L,discretionary,0L,0L,true,"DEFAULT");}).when(budgets).financeSnapshot(anyLong(),any());
        doAnswer(i->overrides).when(budgets).monthlyOverrides(anyLong(),any());
        doAnswer(i->{if(unobserved.get())return List.of();Long selected=i.getArgument(1);boolean individual=selected!=null;
            return List.of(new HistoryMonth(month.minusMonths(1),individual?60000:100000,0,zeros.get()?0:individual?500:1000,zeros.get()?0:individual?100:500,zeros.get()?0:100,0,zeros.get()?0:2,zeros.get()?0:200,zeros.get()?0:50,true));})
            .when(history).history(anyLong(),nullable(Long.class),any());
    }
    HttpClient browser(){return HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();}
    HttpResponse<String> call(HttpClient c,String method,String path,String body)throws Exception{return c.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+path)).header("Origin","http://localhost:5173")
        .header("Content-Type","application/json").method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());}
    JsonNode data(HttpResponse<String> response)throws Exception{return json.readTree(response.body()).path("data");}
    long initialize(HttpClient c,boolean consent)throws Exception{long user=data(call(c,"GET","/api/users/me/",null)).path("user_id").asLong();owner.set(user);
        assertThat(call(c,"PATCH","/api/settings/subscription/","{\"plan_type\":\"PRO\"}").statusCode()).isEqualTo(200);
        if(consent)assertThat(call(c,"PATCH","/api/reports/comparison-settings/","{\"consented\":true,\"age_group\":\"AGE_30S\",\"income_decile\":3}").statusCode()).isEqualTo(200);return user;}
    long generate(HttpClient c,String body)throws Exception{var r=call(c,"POST","/api/reports/",body);assertThat(r.statusCode()).as(r.body()).isEqualTo(202);return data(r).path("report_id").asLong();}
    ReportJob await(long id,ReportJob.Status status)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);ReportJob job;
        do{job=jobs.internal(id).orElseThrow();if(job.status()==status)return job;Thread.sleep(25);}while(System.nanoTime()<end);assertThat(job.status()).as("worker error: %s",job.errorCode()).isEqualTo(status);return job;}
    JsonNode detail(HttpClient c,long id)throws Exception{var r=call(c,"GET","/api/reports/"+id+"/",null);assertThat(r.statusCode()).as(r.body()).isEqualTo(200);return data(r);}
    @Test void realEngineCompletesGoldenReportAndPreservesCurrentOverrideWithNoLiveBudgetReads()throws Exception{
        var c=browser();long user=initialize(c,true);overrides.add(new PlannedAmount(1,AllocationType.EXTRA_PAYMENT,0,null));long revision=revisions.current(user);long id=generate(c,"{}");
        overrides.set(0,new PlannedAmount(1,AllocationType.EXTRA_PAYMENT,20000,null));var job=await(id,ReportJob.Status.COMPLETED);var result=detail(c,id);
        assertThat(job.explanationSource()).isEqualTo("FALLBACK");assertThat(result.path("payoff_scenarios").get(0).path("estimated_payoff_date").asText()).isEqualTo("2027-07-25");
        assertThat(result.path("payoff_scenarios")).hasSize(4);assertThat(result.path("pace_analysis").path("monthly_principal_repayment_rate").decimalValue()).isEqualByComparingTo("1.5");
        assertThat(result.path("pace_analysis").path("principal_repaid_last_6_months").asLong()).isEqualTo(1500);assertThat(result.path("generated_at").asText()).endsWith("+09:00");
        assertThat(result.path("comparison").path("source_type").asText()).isEqualTo("DEMO");assertThat(result.path("strategy_available").asBoolean()).isTrue();
        var latest=data(call(c,"GET","/api/reports/latest/",null)).path("report");assertThat(latest.path("pace_analysis").path("monthly_principal_repayment_rate").isTextual()).isTrue();
        doThrow(ApiException.unavailable()).when(budgets).financeSnapshot(anyLong(),any());assertThat(call(c,"POST","/api/reports/","{}").statusCode()).isEqualTo(200);
        verify(budgets,times(1)).monthlyOverrides(user,month);assertThat(revisions.current(user)).isEqualTo(revision);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM report_attempts WHERE user_id=?",Long.class,user)).isEqualTo(1);
    }
    @Test void selectedReportUsesIndividualHistoryAndPayoffWhileProtectingOtherFutureGoals()throws Exception{
        two.set(true);var c=browser();long user=initialize(c,false);long id=generate(c,"{\"loan_id\":2}");await(id,ReportJob.Status.COMPLETED);var result=detail(c,id);
        assertThat(result.path("pace_analysis").path("principal_repaid_last_6_months").asLong()).isEqualTo(600);assertThat(result.path("comparison").isNull()).isTrue();
        assertThat(result.path("payoff_scenarios").get(0).path("estimated_payoff_date").asText()).isEqualTo("2027-04-25");
        var strategy=data(call(c,"GET","/api/reports/strategy/?report_id="+id,null));assertThat(strategy.path("strategy_type").asText()).isEqualTo("SINGLE_PREFERENCE");
        for(var candidate:strategy.path("candidates")){for(var allocation:candidate.path("allocations"))assertThat(allocation.path("loan_id").asLong()).isEqualTo(2);
            if(candidate.path("strategy_code").asText().equals("FAST"))assertThat(candidate.path("allocations").get(0).path("amount").asLong()).isEqualTo(20000);}
        verify(history).history(eq(user),eq(2L),any());var other=browser();initialize(other,false);assertThat(call(other,"GET","/api/reports/"+id+"/",null).statusCode()).isEqualTo(404);
    }
    @Test void insufficientObservationsRetainNullPersonalMetricsBesideExplicitDemoPeers()throws Exception{
        unobserved.set(true);var c=browser();initialize(c,true);long id=generate(c,"{}");await(id,ReportJob.Status.COMPLETED);var result=detail(c,id);
        assertThat(result.path("pace_analysis").path("observed_month_count").asInt()).isZero();assertThat(result.path("pace_analysis").path("principal_repaid_last_6_months").isNull()).isTrue();
        assertThat(result.path("comparison").path("status").asText()).isEqualTo("INSUFFICIENT");assertThat(result.path("comparison").path("extra_payment_count_6m").isNull()).isTrue();
        assertThat(result.path("comparison").path("peer_extra_payment_count_6m").asInt()).isEqualTo(3);assertThat(result.path("comparison").path("disclaimer").asText()).contains("가상");
    }
    @Test void confirmedZeroIsNotMissingAndZeroIncomeDoesNotCreateAZeroDsr()throws Exception{
        zeros.set(true);var c=browser();initialize(c,true);long id=generate(c,"{}");await(id,ReportJob.Status.COMPLETED);var result=detail(c,id);
        assertThat(result.path("pace_analysis").path("monthly_principal_repayment_rate").decimalValue()).isEqualByComparingTo("0");
        assertThat(result.path("comparison").path("extra_payment_count_6m").asInt()).isZero();assertThat(result.path("comparison").path("monthly_debt_service_ratio_pct").isNull()).isTrue();
    }
    @Test void modelTextCannotChangeServerNumbersStrategyOrStoredCandidates()throws Exception{
        var c=browser();initialize(c,true);long id=generate(c,"{}");var job=await(id,ReportJob.Status.COMPLETED);var input=json.readValue(job.inputPayload(),ReportInput.class);
        var disabled=calculation.calculate(input,id);var builder=RestClient.builder().baseUrl("https://api.openai.com/v1");var server=MockRestServiceServer.bindTo(builder).build();
        String text=json.writeValueAsString(Map.of("summary","계획을 확인해 주세요.","action_note","기록을 확인해 주세요."));
        String response=json.writeValueAsString(Map.of("status","completed","output",List.of(Map.of("content",List.of(Map.of("type","output_text","text",text))))));
        server.expect(requestTo("https://api.openai.com/v1/responses")).andRespond(withSuccess(response,MediaType.APPLICATION_JSON));
        var client=new ExplanationClient(builder.build(),json,true,"fixture-key","fixture-model");var model=new ReportCalculationAdapter(risks,strategies,client,json).calculate(input,id);
        assertThat(model.explanationSource()).isEqualTo("MODEL");assertThat(model.strategies()).isEqualTo(disabled.strategies());assertThat(model.candidates()).isEqualTo(disabled.candidates());
        for(String key:List.of("pace_analysis","comparison","payoff_scenarios","strategy_available","strategy_type"))assertThat(model.result().get(key)).isEqualTo(disabled.result().get(key));server.verify();
    }
    @Test void coreEngineFailureProducesFailedJobWithoutFinancialPlaceholderOrExplanation()throws Exception{
        doThrow(ApiException.unavailable()).when(engine).simulate(any());var c=browser();long user=initialize(c,false);long id=generate(c,"{}");var job=await(id,ReportJob.Status.FAILED);
        assertThat(job.errorCode()).isEqualTo("SERVICE_UNAVAILABLE");assertThat(job.resultPayload()).isNull();assertThat(job.nextRetryAt()).isEqualTo(clock.instant().plusSeconds(60));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM strategy_candidates WHERE report_id=?",Long.class,id)).isZero();verify(explanations,never()).explain(any());
    }
    @Test void concurrentConsentChangeDoesNotWaitForCalculationAndPublishesOnlyAStaleSnapshot()throws Exception{
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        doAnswer(i->{assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();entered.countDown();if(!release.await(10,TimeUnit.SECONDS))throw new IllegalStateException("Test gate expired");return i.callRealMethod();}).when(explanations).explain(any());
        var c=browser();long user=initialize(c,true);long before=revisions.current(user);long id=generate(c,"{}");
        try{assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();assertThat(call(c,"PATCH","/api/reports/comparison-settings/","{\"consented\":false}").statusCode()).isEqualTo(200);}finally{release.countDown();}
        await(id,ReportJob.Status.COMPLETED);var result=detail(c,id);assertThat(result.path("stale").asBoolean()).isTrue();assertThat(result.path("comparison").isNull()).isTrue();assertThat(result.path("strategy_available").asBoolean()).isFalse();
        assertThat(revisions.current(user)).isEqualTo(before+1);var strategy=data(call(c,"GET","/api/reports/strategy/?report_id="+id,null));assertThat(strategy.path("unavailable_reason").asText()).isEqualTo("STALE");assertThat(strategy.path("candidates")).isEmpty();
    }
}
