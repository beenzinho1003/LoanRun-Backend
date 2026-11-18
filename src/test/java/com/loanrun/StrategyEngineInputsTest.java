package com.loanrun;

import static org.assertj.core.api.Assertions.*;
import static com.loanrun.report.service.StrategyEngineInputs.*;
import com.loanrun.common.contract.FinanceModels.*;
import com.loanrun.report.service.StrategyRecommendationPolicy.Code;
import com.loanrun.risk.domain.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class StrategyEngineInputsTest {
    final YearMonth month=YearMonth.of(2026,11);final LocalDate asOf=month.atDay(18);
    LoanTerms loan(long id,long balance,RateType type,RepaymentMethod method,LocalDate grace){return new LoanTerms(id,1,"가상",LoanType.CREDIT,balance,balance,asOf,
        BigDecimal.valueOf(6),type,method,grace,method==RepaymentMethod.GRACE_INSTALLMENT?RepaymentMethod.EQUAL_PAYMENT:null,month.atDay(25),25,
        asOf.plusYears(1),100L,100L,null,PrepaymentPolicy.KEEP_PAYMENT,FeeMethod.UNKNOWN,null,null,null,0,0,LoanStatus.ACTIVE,null);}
    LoanTerms loan(long id,long balance){return loan(id,balance,RateType.FIXED,RepaymentMethod.EQUAL_PAYMENT,null);}
    PlanSnapshot plan(long id,long extra,long saving,long required,LocalDate effective){return new PlanSnapshot(id,true,asOf.plusYears(1),null,extra,saving,required,"AMOUNT",null,effective,1);}
    BudgetCapacity cap(Long available,long recurring,long current,long reserve,Long remaining){return new BudgetCapacity(month,available,recurring,Math.max(current-recurring,0),current,0,0,remaining,reserve,null,"CONFIRMED","OPEN",List.of());}
    RiskScenarioInput source(List<LoanTerms> loans,List<PlanSnapshot> plans,BudgetCapacity cap,Map<Long,Long> paid,Map<Long,Long> saved){
        return new RiskScenarioInput(new RiskInputs(1,asOf,month,loans,plans,null,cap,null,null,null,null),
            new PaymentSummary(month,0,0,0,0,0,0,0,true,0,0,paid),new SavingSummary(month,0,0,0,0,true,0,saved));
    }
    long total(List<PlannedAmount> amounts){return amounts.stream().map(PlannedAmount::cashAmount).reduce(0L,Math::addExact);}
    @Test void singleActualCashIsDeductedOnlyInFirstMonthAndFeeInclusiveGoalsStayUnchanged(){
        var loan=loan(1,10000);var source=source(List.of(loan),List.of(plan(1,300,0,0,asOf)),cap(1000L,1000,1000,0,900L),Map.of(1L,100L),Map.of());
        var prepared=candidate(source,Code.FAST).orElseThrow();var input=prepared.simulation();
        assertThat(total(prepared.goals())).isEqualTo(800);assertThat(total(input.monthlyAmounts().get(month))).isEqualTo(700);
        assertThat(total(input.monthlyAmounts().get(month.plusMonths(1)))).isEqualTo(800);assertThat(prepared.currentFundingAvailable()).isTrue();
        assertThat(input.asOf()).isEqualTo(asOf);assertThat(input.terms()).containsExactly(loan);assertThat(input.allocationRule()).isEqualTo("FAST");assertThat(input.horizonMonths()).isEqualTo(1200);
        assertThat(input.rateOverrides()).isEmpty();assertThat(loan.feeMethod()).isEqualTo(FeeMethod.UNKNOWN);
    }
    @Test void multiSavingIsReservedBeforeExtrasAndBaselineUsesExistingSavingWithoutIncreasingIt(){
        var direct=loan(1,10000,RateType.VARIABLE,RepaymentMethod.EQUAL_PAYMENT,null);var bullet=loan(2,20000,RateType.FIXED,RepaymentMethod.BULLET,null);
        var source=source(List.of(direct,bullet),List.of(plan(1,600,0,0,asOf),plan(2,0,50,100,asOf)),cap(1000L,1000,1000,0,825L),Map.of(1L,150L),Map.of(2L,25L));
        var prepared=candidate(source,Code.RATE_RISK).orElseThrow();
        assertThat(prepared.goals()).containsExactly(new PlannedAmount(2,AllocationType.REPAYMENT_SAVING,100,null),new PlannedAmount(1,AllocationType.EXTRA_PAYMENT,550,1));
        assertThat(total(prepared.simulation().monthlyAmounts().get(month))).isEqualTo(475);assertThat(total(prepared.simulation().monthlyAmounts().get(month.plusMonths(1)))).isEqualTo(650);
        var baseline=baseline(source);assertThat(total(baseline.monthlyAmounts().get(month))).isEqualTo(475);assertThat(total(baseline.monthlyAmounts().get(month.plusMonths(1)))).isEqualTo(650);
        assertThat(baseline.monthlyAmounts().get(month.plusMonths(1))).contains(new PlannedAmount(2,AllocationType.REPAYMENT_SAVING,50,null));assertThat(baseline.allocationRule()).isEqualTo("KEEP_PLANS");
    }
    @Test void parallelInitialCashWeightsHaveDeterministicOneWonRemaindersAndDelegateTheRule(){
        var source=source(List.of(loan(3,100),loan(1,100),loan(2,100)),List.of(),cap(1000L,1000,10000,0,10000L),Map.of(),Map.of());
        var prepared=candidate(source,Code.PARALLEL).orElseThrow();
        assertThat(prepared.goals()).containsExactly(new PlannedAmount(1,AllocationType.EXTRA_PAYMENT,167,1),new PlannedAmount(2,AllocationType.EXTRA_PAYMENT,167,1),new PlannedAmount(3,AllocationType.EXTRA_PAYMENT,166,1));
        assertThat(prepared.simulation().allocationRule()).isEqualTo("PARALLEL");assertThat(total(prepared.goals())).isEqualTo(500);
        assertThatThrownBy(()->prepared.simulation().monthlyAmounts().put(month,List.of())).isInstanceOf(UnsupportedOperationException.class);
    }
    @Test void prioritySeedsRetainAllTargetsWithoutReinvestingScheduledPayments(){
        var fixed=loan(1,100);var variable=loan(2,200,RateType.VARIABLE,RepaymentMethod.EQUAL_PAYMENT,null);
        var source=source(List.of(fixed,variable),List.of(plan(1,400,0,0,asOf)),cap(1000L,1000,1000,0,1000L),Map.of(),Map.of());
        var prepared=candidate(source,Code.RATE_RISK).orElseThrow();
        assertThat(prepared.goals()).containsExactly(new PlannedAmount(2,AllocationType.EXTRA_PAYMENT,400,1),new PlannedAmount(1,AllocationType.EXTRA_PAYMENT,0,2));
        assertThat(total(prepared.simulation().monthlyAmounts().get(month.plusMonths(1199)))).isEqualTo(400);
    }
    @Test void exhaustedCurrentCashCannotBeMarkedFullyFundedButFutureGoalsRemainSeparate(){
        var source=source(List.of(loan(1,10000)),List.of(),cap(1000L,1000,1000,1000,0L),Map.of(),Map.of());
        var prepared=candidate(source,Code.FAST).orElseThrow();assertThat(prepared.currentFundingAvailable()).isFalse();
        assertThat(total(prepared.simulation().monthlyAmounts().get(month))).isZero();assertThat(total(prepared.simulation().monthlyAmounts().get(month.plusMonths(1)))).isEqualTo(800);
        assertThat(total(candidate(source,Code.STABLE).orElseThrow().goals())).isZero();
    }
    @Test void savingsCannotBeInventedWhenMissingOrUnaffordable(){
        var bullet=loan(1,10000,RateType.FIXED,RepaymentMethod.BULLET,null);
        assertThat(candidate(source(List.of(bullet),List.of(),cap(1000L,1000,1000,0,1000L),Map.of(),Map.of()),Code.SAVINGS)).isEmpty();
        assertThat(candidate(source(List.of(bullet),List.of(plan(1,0,100,2000,asOf)),cap(1000L,1000,1000,0,1000L),Map.of(),Map.of()),Code.SAVINGS)).isEmpty();
        assertThat(candidate(source(List.of(loan(1,10000)),List.of(),cap(null,1000,1000,0,1000L),Map.of(),Map.of()),Code.FAST)).isEmpty();
    }
    @Test void baselineHonorsFutureEffectiveAndGraceDatesWithoutSubtractingCurrentActualsAgain(){
        var grace=loan(1,10000,RateType.FIXED,RepaymentMethod.GRACE_INSTALLMENT,month.plusMonths(1).atDay(15));
        var source=source(List.of(grace,loan(2,10000)),List.of(plan(1,500,0,0,asOf),plan(2,300,0,0,month.plusMonths(1).atDay(1))),
            cap(1000L,1000,1000,0,1000L),Map.of(1L,100L,2L,100L),Map.of());
        var baseline=baseline(source);assertThat(baseline.monthlyAmounts().get(month)).isEmpty();assertThat(total(baseline.monthlyAmounts().get(month.plusMonths(1)))).isEqualTo(800);
    }
    @Test void explicitlyConfiguredZeroAndInvalidStrategyTypesDoNotBecomeDefaultExtraCash(){
        var source=source(List.of(loan(1,10000),loan(2,10000)),List.of(plan(1,0,0,0,asOf)),cap(1000L,1000,1000,0,1000L),Map.of(),Map.of());
        assertThat(total(candidate(source,Code.INTEREST_SAVING).orElseThrow().goals())).isZero();
        assertThatThrownBy(()->candidate(source,Code.FAST)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void currentManualOverrideUsesCarryWithoutChangingStableFutureGoals(){
        var source=source(List.of(loan(1,10000)),List.of(plan(1,300,0,0,asOf)),cap(1000L,1000,5000,0,4900L),Map.of(1L,100L),Map.of());
        var overrides=List.of(new PlannedAmount(1,AllocationType.EXTRA_PAYMENT,2000,99));
        var prepared=candidate(source,Code.STABLE,overrides).orElseThrow();
        assertThat(total(prepared.simulation().monthlyAmounts().get(month))).isEqualTo(1900);assertThat(prepared.currentFundingAvailable()).isTrue();
        assertThat(total(prepared.goals())).isZero();assertThat(total(prepared.simulation().monthlyAmounts().get(month.plusMonths(1)))).isZero();
        assertThat(total(baseline(source,overrides).monthlyAmounts().get(month))).isEqualTo(1900);
        assertThat(total(baseline(source,overrides).monthlyAmounts().get(month.plusMonths(1)))).isEqualTo(300);
    }
    @Test void explicitZeroAndNewCurrentTargetKeepManualPrioritiesOutOfFutureStrategy(){
        var source=source(List.of(loan(1,10000),loan(2,20000)),List.of(),cap(1000L,1000,5000,0,5000L),Map.of(),Map.of());
        var overrides=List.of(new PlannedAmount(1,AllocationType.EXTRA_PAYMENT,0,null),new PlannedAmount(2,AllocationType.EXTRA_PAYMENT,700,99));
        var prepared=candidate(source,Code.INTEREST_SAVING,overrides).orElseThrow();
        assertThat(prepared.simulation().monthlyAmounts().get(month)).containsExactly(new PlannedAmount(1,AllocationType.EXTRA_PAYMENT,0,null),new PlannedAmount(2,AllocationType.EXTRA_PAYMENT,700,null));
        assertThat(total(prepared.simulation().monthlyAmounts().get(month.plusMonths(1)))).isEqualTo(500);
        assertThat(total(baseline(source,overrides).monthlyAmounts().get(month))).isEqualTo(700);
    }
    @Test void selectedSourceDoesNotDeductOtherLoanMapsTwiceButWholeUserCapacityStillProtectsUsedCash(){
        var selected=source(List.of(loan(1,10000)),List.of(),cap(1000L,1000,1000,0,1000L),Map.of(99L,900L),Map.of());
        assertThat(total(candidate(selected,Code.FAST).orElseThrow().simulation().monthlyAmounts().get(month))).isEqualTo(800);
    }
    @Test void invalidOrDuplicateCapturedOverridesFailInsteadOfProducingAChangedGoal(){
        var source=source(List.of(loan(1,10000)),List.of(),cap(1000L,1000,1000,0,1000L),Map.of(),Map.of());
        var wrong=new PlannedAmount(1,AllocationType.REPAYMENT_SAVING,100,null);
        assertThatThrownBy(()->candidate(source,Code.FAST,List.of(wrong))).isInstanceOf(IllegalArgumentException.class);
        var valid=new PlannedAmount(1,AllocationType.EXTRA_PAYMENT,100,null);
        assertThatThrownBy(()->baseline(source,List.of(valid,valid))).isInstanceOf(IllegalArgumentException.class);
    }
}
