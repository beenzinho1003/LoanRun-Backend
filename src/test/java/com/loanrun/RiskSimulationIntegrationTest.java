package com.loanrun;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.loanrun.common.contract.*;
import com.loanrun.common.contract.FinanceModels.*;
import com.loanrun.common.error.ApiException;
import java.math.*;
import java.net.*;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@Testcontainers
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"loanrun.demo-mode=true","server.servlet.session.cookie.secure=false"})
@Import(BootstrapIntegrationTest.TestConfig.class)
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class RiskSimulationIntegrationTest {
    @Container static final PostgreSQLContainer<?> DB=new PostgreSQLContainer<>("postgres:16.16-bookworm");
    @DynamicPropertySource static void database(DynamicPropertyRegistry r){r.add("spring.datasource.url",DB::getJdbcUrl);r.add("spring.datasource.username",DB::getUsername);r.add("spring.datasource.password",DB::getPassword);}
    @LocalServerPort int port;@Autowired ObjectMapper mapper;@Autowired RevisionPort revisions;@Autowired JdbcTemplate jdbc;
    @Autowired com.loanrun.risk.service.RiskSimulationService risk;
    @MockitoBean LoanQueryPort loans;@MockitoBean PlanQueryPort plans;@MockitoBean com.loanrun.budget.service.BudgetReadService budgets;@MockitoBean RepaymentEngine engine;
    final AtomicLong owner=new AtomicLong();static final YearMonth MONTH=YearMonth.of(2026,11);
    LoanTerms loan(long id,RateType rate){return new LoanTerms(id,owner.get(),"가상",LoanType.CREDIT,500000,500000,MONTH.atDay(17),BigDecimal.valueOf(6),rate,
        RepaymentMethod.EQUAL_PAYMENT,null,null,MONTH.atDay(25),25,MONTH.plusYears(2).atDay(25),id==1?400000L:100000L,100000L,null,
        PrepaymentPolicy.KEEP_PAYMENT,FeeMethod.NONE,BigDecimal.ZERO,null,null,0,0,LoanStatus.ACTIVE,null);}
    PlanSnapshot plan(long id,long extra){return new PlanSnapshot(id,true,MONTH.plusYears(1).atDay(25),null,extra,0,0,"EXTRA_AMOUNT",null,MONTH.atDay(1),3);}
    BudgetCapacity capacity(Long cash){return new BudgetCapacity(MONTH,cash,cash,900000,cash==null?null:cash+900000,20000,0,cash==null?null:cash+880000,0,0L,"ASSUMED","OPEN",List.of());}
    @BeforeEach void facts(){
        owner.set(0);
        when(loans.activeLoans(anyLong(),any())).thenAnswer(i->(long)i.getArgument(0)==owner.get()?List.of(loan(1,RateType.VARIABLE),loan(2,RateType.FIXED)):List.of());
        when(plans.currentPlans(anyLong(),any())).thenAnswer(i->(long)i.getArgument(0)==owner.get()?List.of(plan(1,60000),plan(2,40000)):List.of());
        when(loans.dueSummary(anyLong(),any())).thenAnswer(i->new DueSummary(i.getArgument(1),500000,0,0,500000,0,MONTH.atDay(25),2,0,0,0,0));
        when(loans.paymentSummary(anyLong(),any())).thenAnswer(i->new PaymentSummary(i.getArgument(1),0,20000,0,0,20000,0,1,true,0,0,Map.of(1L,20000L)));
        when(loans.savingSummary(anyLong(),any())).thenAnswer(i->new SavingSummary(i.getArgument(1),0,0,0,0,true,0,Map.of()));
        when(budgets.capacity(anyLong(),any())).thenReturn(capacity(200000L));
        when(budgets.financeSnapshot(anyLong(),any())).thenReturn(new FinanceSnapshot(MONTH,IncomeType.REGULAR,1000000L,1000000L,100000L,100000L,100000L,100000L,100000L,0L,600000L,true,"INPUT"));
        when(engine.simulate(any())).thenAnswer(i->{SimulationInput input=i.getArgument(0);
            if(input.horizonMonths()==1){long delta=input.rateOverrides().getOrDefault(1L,BigDecimal.valueOf(6)).subtract(BigDecimal.valueOf(6)).multiply(BigDecimal.valueOf(50000)).longValueExact();
                return new SimulationResult(List.of(new SimulationRow(MONTH.atDay(25),1,395000,5000+delta,0L,0,0,0,100000,0),
                    new SimulationRow(MONTH.atDay(25),2,95000,5000,0L,0,0,0,100000,0)),null,null,null,null,null,false,"NONE",List.of());}
            var date=MONTH.plusMonths(11).atDay(25);
            return new SimulationResult(List.of(new SimulationRow(date,1,500000,0,0L,0,0,0,0,0),new SimulationRow(date,2,500000,0,0L,0,0,0,0,0)),
                date,null,null,null,null,false,"NONE",List.of("ENGINE_WARNING"));
        });
    }
    HttpClient browser(){return HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();}
    HttpResponse<String> call(HttpClient c,String method,String path,String body)throws Exception{return c.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+path))
        .header("Origin","http://localhost:5173").header("Content-Type","application/json").method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());}
    JsonNode json(HttpResponse<String> r)throws Exception{return mapper.readTree(r.body());}
    long initialize(HttpClient c,boolean owned,boolean pro)throws Exception{long id=json(call(c,"GET","/api/users/me/",null)).path("data").path("user_id").longValue();if(owned)owner.set(id);
        if(pro)assertThat(call(c,"PATCH","/api/settings/subscription/","{\"plan_type\":\"PRO\"}").statusCode()).isEqualTo(200);return id;}
    HttpResponse<String> simulate(HttpClient c,String type)throws Exception{return call(c,"POST","/api/risks/simulate/","{\"scenario_type\":\""+type+"\"}");}
    @Test void independentScenariosUseWholeHouseholdAndCurrentRemainingTargetsWithoutWritingRevision()throws Exception{
        var c=browser();long id=initialize(c,true,true),revision=revisions.current(id);
        var current=simulate(c,"CURRENT");assertThat(current.statusCode()).as(current.body()).isEqualTo(200);assertThat(json(current).path("code").asText()).isEqualTo("RISK_SIMULATION_SUCCESS");
        var data=json(current).path("data");assertThat(data.path("scenario").path("available_cash").longValue()).isEqualTo(200000);
        assertThat(data.path("scenario").path("payoff_feasibility").asText()).isEqualTo("HIGH");assertThat(data.path("warnings").toString()).contains("ENGINE_WARNING");
        assertThat(data.path("income_thresholds").path("reduce_extra_from").decimalValue()).isEqualByComparingTo("10.01");
        assertThat(data.path("rate_thresholds").path("basic_at_risk_from").decimalValue()).isEqualByComparingTo("4.01");
        assertThat(data.path("expense_thresholds").path("basic_at_risk_from").longValue()).isEqualTo(200001);
        var rate=json(simulate(c,"RATE_UP")).path("data");assertThat(rate.path("scenario").path("available_cash").longValue()).isEqualTo(150000);
        assertThat(rate.path("scenario").path("monthly_payment").longValue()).isEqualTo(550000);
        var income=json(simulate(c,"INCOME_DOWN")).path("data");assertThat(income.path("scenario").path("income").longValue()).isEqualTo(950000);
        assertThat(income.path("scenario").path("available_cash").longValue()).isEqualTo(150000);
        var expense=json(simulate(c,"EXPENSE_UP")).path("data");assertThat(expense.path("scenario").path("available_cash").longValue()).isEqualTo(-300000);
        assertThat(expense.path("scenario").path("one_time_shortfall").longValue()).isEqualTo(300000);
        assertThat(expense.path("scenario").path("emergency_coverage_status").asText()).isEqualTo("NOT_NEEDED");assertThat(expense.path("scenario").path("emergency_coverage_months").isNull()).isTrue();
        assertThat(revisions.current(id)).isEqualTo(revision);
        var capture=org.mockito.ArgumentCaptor.forClass(SimulationInput.class);verify(engine,atLeastOnce()).simulate(capture.capture());
        var full=capture.getAllValues().stream().filter(input->input.horizonMonths()==1200).findFirst().orElseThrow();
        assertThat(full.monthlyAmounts().get(MONTH).getFirst().cashAmount()).isEqualTo(40000);
        assertThat(full.monthlyAmounts().get(MONTH.plusMonths(1)).getFirst().cashAmount()).isEqualTo(60000);
    }
    @Test void proGateAndLoanFilterDoNotLetAnotherSessionBorrowLoansOrNarrowHousehold()throws Exception{
        var c=browser();initialize(c,true,false);assertThat(simulate(c,"CURRENT").statusCode()).isEqualTo(403);verifyNoInteractions(engine);
        call(c,"PATCH","/api/settings/subscription/","{\"plan_type\":\"PRO\"}");
        var filtered=call(c,"POST","/api/risks/simulate/","{\"scenario_type\":\"CURRENT\",\"loan_ids\":[1]}");
        assertThat(json(filtered).path("data").path("baseline").path("monthly_payment").longValue()).isEqualTo(500000);
        var other=browser();initialize(other,false,true);var response=call(other,"POST","/api/risks/simulate/","{\"scenario_type\":\"CURRENT\",\"loan_ids\":[1]}");
        assertThat(response.statusCode()).isEqualTo(404);assertThat(json(response).path("code").asText()).isEqualTo("LOAN_NOT_FOUND");
    }
    @Test void rejectsDirectChangesInvalidEnumsIdsAndWrongRoutes()throws Exception{
        var c=browser();initialize(c,true,true);
        for(String body:List.of("{}","{\"scenario_type\":\"CUSTOM\"}","{\"scenario_type\":\"CURRENT\",\"income_change_pct\":-5}",
            "{\"scenario_type\":\"CURRENT\",\"loan_ids\":null}","{\"scenario_type\":\"CURRENT\",\"loan_ids\":[0]}",
            "{\"scenario_type\":\"CURRENT\",\"loan_ids\":[1,1]}","{\"scenario_type\":\"CURRENT\",\"loan_ids\":[1.5]}")){
            var r=call(c,"POST","/api/risks/simulate/",body);assertThat(r.statusCode()).isEqualTo(400);assertThat(json(r).path("code").asText()).isEqualTo("SIMULATION_VALIDATION_ERROR");}
        assertThat(call(c,"POST","/api/risks/simulate","{\"scenario_type\":\"CURRENT\"}").statusCode()).isEqualTo(404);verifyNoInteractions(engine);
    }
    @Test void unknownCashRemainsNullAndDependentFailuresReturn503WithoutFinancialWrites()throws Exception{
        var c=browser();long id=initialize(c,true,true),revision=revisions.current(id);when(budgets.capacity(id,MONTH)).thenReturn(capacity(null));
        var response=simulate(c,"CURRENT");assertThat(response.statusCode()).isEqualTo(200);var data=json(response).path("data");
        assertThat(data.path("scenario").path("available_cash").isNull()).isTrue();assertThat(data.path("scenario").path("payoff_feasibility").isNull()).isTrue();
        assertThat(data.path("scenario").path("score").isNull()).isTrue();assertThat(data.path("scenario").path("emergency_coverage_status").asText()).isEqualTo("INSUFFICIENT");verifyNoInteractions(engine);
        when(budgets.capacity(id,MONTH)).thenThrow(ApiException.unavailable());assertThat(simulate(c,"CURRENT").statusCode()).isEqualTo(503);assertThat(revisions.current(id)).isEqualTo(revision);
    }
    @Test void recurringShortfallUsesKnownEmergencyFundAndFixedOnlyRateUpHasNoEffect()throws Exception{
        var c=browser();long id=initialize(c,true,true);when(budgets.capacity(id,MONTH)).thenReturn(capacity(-100000L));
        var data=json(simulate(c,"CURRENT")).path("data");assertThat(data.path("scenario").path("emergency_coverage_months").decimalValue()).isEqualByComparingTo("6");
        assertThat(data.path("scenario").path("payoff_feasibility").asText()).isEqualTo("LOW");
        when(loans.activeLoans(eq(id),any())).thenReturn(List.of(loan(1,RateType.FIXED),loan(2,RateType.FIXED)));when(budgets.capacity(id,MONTH)).thenReturn(capacity(200000L));
        var rate=json(simulate(c,"RATE_UP")).path("data");assertThat(rate.path("scenario").path("available_cash").longValue()).isEqualTo(200000);
        assertThat(rate.path("rate_thresholds").path("basic_at_risk_from").isNull()).isTrue();assertThat(rate.path("warnings").toString()).contains("고정금리");
    }
    @Test void capturedEvaluationRetainsLedgerFactsAndPayoffDatesAfterLiveSourcesChange()throws Exception{
        var c=browser();long id=initialize(c,true,true),revision=revisions.current(id);var captured=risk.capture(id);
        when(loans.paymentSummary(id,MONTH)).thenThrow(ApiException.unavailable());
        when(budgets.capacity(id,MONTH)).thenThrow(ApiException.unavailable());
        var restored=mapper.readValue(mapper.writeValueAsString(captured),com.loanrun.risk.domain.RiskScenarioInput.class);
        var result=risk.evaluate(restored,com.loanrun.risk.dto.RiskDtos.ScenarioType.CURRENT);
        assertThat(result.response().scenario().payoffFeasibility()).isEqualTo("HIGH");
        assertThat(result.projectedPayoffDate()).isEqualTo(MONTH.plusMonths(11).atDay(25));
        assertThat(result.loanPayoffDates()).hasSize(2);assertThat(revisions.current(id)).isEqualTo(revision);
        assertThatThrownBy(()->captured.payments().extraCashByLoan().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThat(simulate(c,"CURRENT").statusCode()).isEqualTo(503);
    }
}
