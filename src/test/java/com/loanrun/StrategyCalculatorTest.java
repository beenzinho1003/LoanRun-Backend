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
import com.loanrun.report.dto.ComparisonSettings;
import com.loanrun.report.service.StrategyCalculator;
import com.loanrun.report.service.StrategyCalculator.RateContext;
import com.loanrun.risk.domain.*;
import com.loanrun.risk.service.RiskMath;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

class StrategyCalculatorTest {
    final StrategyEngineInputsTest fixture=new StrategyEngineInputsTest();
    final RepaymentEngine engine=mock(RepaymentEngine.class);final StaticListableBeanFactory beans=new StaticListableBeanFactory();
    final Clock clock=Clock.fixed(Instant.parse("2026-11-18T03:00:00Z"),ZoneId.of("Asia/Seoul"));
    final Map<String,Long> interest=new HashMap<>(),fees=new HashMap<>();boolean insufficient;
    StrategyCalculator calculator;
    @BeforeEach void engineResults(){
        beans.addBean("engine",engine);calculator=new StrategyCalculator(beans.getBeanProvider(RepaymentEngine.class),clock);
        interest.putAll(Map.of("KEEP_PLANS",1000L,"FAST",500L,"BALANCED",650L,"STABLE",1000L,"RATE_RISK",600L,"INTEREST_SAVING",400L,"PARALLEL",700L,"SAVINGS",1000L));
        doAnswer(invocation->{SimulationInput request=invocation.getArgument(0);List<SimulationRow> rows=new ArrayList<>();
            for(var loan:request.terms()){
                var amount=request.monthlyAmounts().get(fixture.month).stream().filter(a->a.loanId()==loan.loanId()).findFirst();
                long extra=amount.filter(a->a.allocationType()==AllocationType.EXTRA_PAYMENT).map(PlannedAmount::cashAmount).orElse(0L);
                long saved=amount.filter(a->a.allocationType()==AllocationType.REPAYMENT_SAVING).map(PlannedAmount::cashAmount).orElse(0L);
                rows.add(new SimulationRow(fixture.month.atDay(25),loan.loanId(),10,5,0L,extra,extra,saved,9000,loan.currentSavingBalance()+saved));
            }
            return new SimulationResult(rows,insufficient?null:fixture.asOf.plusYears(1),10000L,interest.get(request.allocationRule()),fees.getOrDefault(request.allocationRule(),0L),20000L,insufficient,"NET",List.of());
        }).when(engine).simulate(any());
    }
    ReportInput input(List<LoanTerms> loans,List<PlanSnapshot> plans,Long selected,BudgetCapacity capacity,List<PlannedAmount> overrides){
        var finance=new FinanceSnapshot(fixture.month,IncomeType.REGULAR,10000L,10000L,0L,0L,100L,50L,100L,0L,0L,true,"DEFAULT");
        long total=loans.stream().map(LoanTerms::outstanding).reduce(0L,Math::addExact),variable=loans.stream().filter(l->l.rateType()==RateType.VARIABLE).map(LoanTerms::outstanding).reduce(0L,Math::addExact);
        long target=plans.stream().filter(PlanSnapshot::configured).map(p->p.monthlyExtraCash()+Math.max(p.monthlySaving(),p.requiredMonthlySaving())).reduce(0L,Math::addExact);
        long saved=loans.stream().map(LoanTerms::currentSavingBalance).reduce(0L,Math::addExact);
        var facts=new RiskInputs(1,fixture.asOf,fixture.month,loans,plans,finance,capacity,new DueSummary(fixture.month,15*loans.size(),0,0,15*loans.size(),0,fixture.month.atDay(25),loans.size(),0,0,0,0),target,0L,RiskMath.ratio(variable,total));
        var source=new RiskScenarioInput(facts,new PaymentSummary(fixture.month,0,0,0,0,0,0,0,true,0,0,Map.of()),new SavingSummary(fixture.month,0,saved,0,saved,true,0,Map.of()));
        return new ReportInput(1,selected,7,fixture.month,source,List.of(),Map.of(),ComparisonSettings.defaults(),new OverallState(true,"INTEREST_SAVING","MULTI",clock.instant().minusSeconds(86400)),
            new Performance(new BigDecimal("95"),3,1),overrides);
    }
    ReportInput single(){return input(List.of(fixture.loan(1,10000)),List.of(fixture.plan(1,100,0,0,fixture.asOf)),null,fixture.cap(1000L,1000,1500,0,1500L),List.of());}
    ReportInput multi(){return input(List.of(fixture.loan(1,10000,RateType.VARIABLE,RepaymentMethod.EQUAL_PAYMENT,null),fixture.loan(2,10000)),
        List.of(fixture.plan(1,100,0,0,fixture.asOf),fixture.plan(2,100,0,0,fixture.asOf)),null,fixture.cap(1000L,1000,1500,0,1500L),List.of());}
    @Test void singleCostsUseEngineNumbersAndOneUnknownFeeMakesEveryStoredComparisonGross(){
        fees.put("FAST",null);var computed=calculator.calculate(42,single(),new RateContext(1000L,null));
        assertThat(computed.view().candidates()).extracting(c->c.strategyCode()).containsExactly("FAST","BALANCED","STABLE");assertThat(computed.view().recommendation().strategyCode()).isEqualTo("FAST");
        assertThat(computed.view().candidates().getFirst().estimatedTotalInterest()).isEqualTo(500);assertThat(computed.view().candidates().getFirst().interestSaving()).isEqualTo(500);
        assertThat(computed.view().candidates().getFirst().monthlyBurden()).isEqualTo(815);assertThat(computed.view().candidates()).allSatisfy(c->{assertThat(c.netSaving()).isNull();assertThat(c.feeComparisonBasis()).isEqualTo("GROSS_INTEREST");});
        assertThat(computed.stored()).allSatisfy(c->{assertThat(c.reportId()).isEqualTo(42);assertThat(c.dataRevision()).isEqualTo(7);assertThat(c.computedAt()).isEqualTo(clock.instant());assertThat(c.simulation().feeComparisonBasis()).isEqualTo("GROSS_INTEREST");});
        assertThat(computed.view().strategyVersion()).isEqualTo("strategy-42-7-2026-11");assertThatThrownBy(()->computed.view().candidates().clear()).isInstanceOf(UnsupportedOperationException.class);
    }
    @Test void confirmedRateRiskOverridesHigherInterestSaving(){
        var computed=calculator.calculate(42,multi(),new RateContext(100L,new BigDecimal("1.20")));
        assertThat(computed.view().recommendation().strategyCode()).isEqualTo("RATE_RISK");assertThat(computed.view().situation().basicAtRiskFrom()).isEqualByComparingTo("1.20");
        assertThat(calculator.calculate(42,multi(),new RateContext(1000L,null)).view().recommendation().strategyCode()).isEqualTo("INTEREST_SAVING");
    }
    @Test void feeDeltasDetermineNetWinnerAndUnknownCandidateFeeSwitchesAllBackToGross(){
        fees.put("INTEREST_SAVING",1000L);var net=calculator.calculate(42,multi(),new RateContext(1000L,null));
        assertThat(net.view().feeComparisonBasis()).isEqualTo("NET");assertThat(net.view().recommendation().strategyCode()).isEqualTo("RATE_RISK");
        fees.put("PARALLEL",null);var gross=calculator.calculate(42,multi(),new RateContext(1000L,null));
        assertThat(gross.view().feeComparisonBasis()).isEqualTo("GROSS_INTEREST");assertThat(gross.view().recommendation().strategyCode()).isEqualTo("INTEREST_SAVING");
    }
    @Test void selectedLoanReservesOtherGoalsAndNeverBorrowsHouseholdPerformanceOrAppliedTime(){
        var own=new PlanSnapshot(1,true,fixture.asOf.plusYears(1),null,100,0,0,"PREFERENCE","FAST",fixture.asOf,1);
        var input=input(List.of(fixture.loan(1,10000),fixture.loan(2,10000)),List.of(own,fixture.plan(2,300,0,0,fixture.asOf)),1L,fixture.cap(1000L,1000,1500,0,1500L),List.of(new PlannedAmount(1,AllocationType.EXTRA_PAYMENT,0,null)));
        var computed=calculator.calculate(42,input,new RateContext(1000L,null));assertThat(computed.view().strategyType()).isEqualTo("SINGLE_PREFERENCE");assertThat(computed.view().recommendation().strategyCode()).isEqualTo("BALANCED");
        assertThat(computed.view().currentStrategy().strategyCode()).isEqualTo("FAST");assertThat(computed.view().currentStrategy().appliedAt()).isNull();assertThat(computed.view().currentStrategy().adherenceRate3m()).isNull();
        assertThat(computed.view().situation().consecutiveExtraOnTrackMonths()).isNull();assertThat(computed.view().situation().missedPaymentCount6m()).isNull();
        assertThat(computed.stored()).allSatisfy(c->assertThat(c.allocations()).allSatisfy(a->assertThat(a.loanId()).isEqualTo(1)));
        var requests=org.mockito.ArgumentCaptor.forClass(SimulationInput.class);verify(engine,times(4)).simulate(requests.capture());
        assertThat(requests.getAllValues()).allSatisfy(r->{assertThat(r.terms()).extracting(LoanTerms::loanId).containsExactly(1L);assertThat(r.monthlyAmounts().get(fixture.month)).allSatisfy(a->assertThat(a.cashAmount()).isZero());});
    }
    @Test void insufficientCurrentFundingCannotBeRecommendedAndEngineFailureNeverBecomesAPlaceholder(){
        var limited=input(List.of(fixture.loan(1,10000)),List.of(),null,fixture.cap(1000L,1000,1000,1000,0L),List.of());
        var computed=calculator.calculate(42,limited,new RateContext(1000L,null));assertThat(computed.view().recommendation()).isNull();
        assertThat(computed.stored().stream().filter(c->c.code().equals("FAST")).findFirst().orElseThrow().feasible()).isFalse();
        assertThat(computed.stored().stream().filter(c->c.code().equals("FAST")).findFirst().orElseThrow().simulation().warnings()).contains("CURRENT_ALLOCATION_UNFUNDED");
        insufficient=true;var failed=calculator.calculate(42,single(),new RateContext(1000L,null));assertThat(failed.view().strategyAvailable()).isFalse();assertThat(failed.stored()).isEmpty();assertThat(failed.view().candidates()).isEmpty();
        var empty=new StaticListableBeanFactory();assertThatThrownBy(()->new StrategyCalculator(empty.getBeanProvider(RepaymentEngine.class),clock).calculate(42,single(),new RateContext(1000L,null)))
            .isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code()).isEqualTo("SERVICE_UNAVAILABLE"));
    }
    @Test void savedFundsUsedForMaturityAreNotCashAndDtosKeepExactNumericAndHistoryKeys()throws Exception{
        var bullet=new LoanTerms(1,1,"가상",LoanType.CREDIT,1000,1000,fixture.asOf,BigDecimal.valueOf(6),RateType.FIXED,RepaymentMethod.BULLET,null,null,fixture.month.atDay(25),25,
            fixture.month.atDay(25),1010L,1010L,null,PrepaymentPolicy.KEEP_PAYMENT,FeeMethod.NONE,BigDecimal.ZERO,null,null,1000,1000,LoanStatus.ACTIVE,null);
        doReturn(new SimulationResult(List.of(new SimulationRow(fixture.month.atDay(25),1,1000,10,0L,0,0,0,0,0)),fixture.month.atDay(25),1000L,10L,0L,10L,false,"NET",List.of())).when(engine).simulate(any());
        var input=input(List.of(bullet),List.of(fixture.plan(1,0,0,0,fixture.asOf)),null,fixture.cap(1000L,1000,1000,0,1000L),List.of());
        var computed=calculator.calculate(42,input,new RateContext(1000L,null));assertThat(computed.view().candidates().getFirst().monthlyBurden()).isEqualTo(10);
        var mapper=new ObjectMapper().findAndRegisterModules().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);var json=mapper.valueToTree(computed.view());
        assertThat(json.path("current_strategy").has("adherence_rate_3m")).isTrue();assertThat(json.path("situation").has("missed_payment_count_6m")).isTrue();assertThat(json.path("candidates").get(0).path("monthly_burden").isIntegralNumber()).isTrue();
    }
    @Test void missingMandatoryOtherPlanDefersSelectedCandidatesAndEmptyEngineRowsFailExplicitly(){
        var source=input(List.of(fixture.loan(1,10000),fixture.loan(2,10000,RateType.FIXED,RepaymentMethod.BULLET,null)),List.of(),1L,fixture.cap(1000L,1000,1000,0,1000L),List.of());
        var deferred=calculator.calculate(42,source,new RateContext(1000L,null));assertThat(deferred.view().strategyAvailable()).isFalse();verifyNoInteractions(engine);
        doReturn(new SimulationResult(List.of(),null,null,null,null,null,true,"NET",List.of())).when(engine).simulate(any());
        assertThatThrownBy(()->calculator.calculate(42,single(),new RateContext(1000L,null))).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code()).isEqualTo("SERVICE_UNAVAILABLE"));
    }
}
