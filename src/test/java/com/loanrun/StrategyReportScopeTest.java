package com.loanrun;

import static org.assertj.core.api.Assertions.*;
import static com.loanrun.report.service.StrategyReportScope.*;
import com.loanrun.common.contract.FinanceModels.*;
import com.loanrun.report.service.StrategyEngineInputs;
import com.loanrun.report.service.StrategyRecommendationPolicy.Code;
import com.loanrun.risk.domain.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class StrategyReportScopeTest {
    final StrategyEngineInputsTest fixture=new StrategyEngineInputsTest();
    RiskScenarioInput source(List<LoanTerms> loans,List<PlanSnapshot> plans,Map<Long,Long> paid,Map<Long,Long> saved){
        long used=paid.values().stream().reduce(0L,Math::addExact)+saved.values().stream().reduce(0L,Math::addExact);
        return fixture.source(loans,plans,fixture.cap(1000L,1000,1500,100,Math.max(1400-used,0)),paid,saved);
    }
    long total(List<PlannedAmount> amounts){return amounts.stream().map(PlannedAmount::cashAmount).reduce(0L,Math::addExact);}
    @Test void fullHouseholdScopeRetainsOriginalFactsAndOverrides(){
        var source=source(List.of(fixture.loan(1,10000)),List.of(),Map.of(),Map.of());var overrides=List.of(new PlannedAmount(1,AllocationType.EXTRA_PAYMENT,0,null));
        var scoped=select(source,null,overrides).orElseThrow();assertThat(scoped.candidateInput()).isSameAs(source);assertThat(scoped.overrides()).isEqualTo(overrides);assertThat(scoped.reservedCurrentCash()).isZero();
    }
    @Test void selectedScopeReservesOtherGoalsWithoutDoubleDeductingOtherActual(){
        var source=source(List.of(fixture.loan(1,10000),fixture.loan(2,10000)),List.of(fixture.plan(2,300,0,0,fixture.asOf)),Map.of(1L,100L,2L,200L),Map.of());
        var scoped=select(source,1L,List.of()).orElseThrow();var cap=scoped.candidateInput().household().capacity();
        assertThat(scoped.reservedRecurringCash()).isEqualTo(300);assertThat(scoped.reservedCurrentCash()).isEqualTo(300);
        assertThat(cap.availableCash()).isEqualTo(700);assertThat(cap.recurringCap()).isEqualTo(700);assertThat(cap.allocationCapacity()).isEqualTo(1200);assertThat(cap.remainingAllocatable()).isEqualTo(1000);
        var prepared=StrategyEngineInputs.candidate(scoped.candidateInput(),Code.FAST,scoped.overrides()).orElseThrow();
        assertThat(total(prepared.goals())).isEqualTo(560);assertThat(total(prepared.simulation().monthlyAmounts().get(fixture.month))).isEqualTo(460);
        assertThat(source.household().loans()).hasSize(2);assertThat(source.household().capacity().availableCash()).isEqualTo(1000);
    }
    @Test void otherCurrentOverrideIsReservedSeparatelyFromItsFutureGoalAndSelectedOverrideRemains(){
        var source=source(List.of(fixture.loan(1,10000),fixture.loan(2,10000)),List.of(fixture.plan(2,300,0,0,fixture.asOf)),Map.of(1L,100L,2L,200L),Map.of());
        var overrides=List.of(new PlannedAmount(2,AllocationType.EXTRA_PAYMENT,500,null),new PlannedAmount(1,AllocationType.EXTRA_PAYMENT,0,null));
        var scoped=select(source,1L,overrides).orElseThrow();assertThat(scoped.reservedRecurringCash()).isEqualTo(300);assertThat(scoped.reservedCurrentCash()).isEqualTo(500);
        assertThat(scoped.candidateInput().household().capacity().remainingAllocatable()).isEqualTo(800);assertThat(scoped.overrides()).containsExactly(overrides.getLast());
    }
    @Test void overspendingAndPaidOrDeletedLoanActualCannotBeSpentAgain(){
        var source=source(List.of(fixture.loan(1,10000),fixture.loan(2,10000)),List.of(fixture.plan(2,300,0,0,fixture.asOf)),Map.of(1L,100L,2L,800L,99L,200L),Map.of());
        var scoped=select(source,1L,List.of()).orElseThrow();assertThat(scoped.reservedCurrentCash()).isEqualTo(1000);
        assertThat(scoped.candidateInput().household().capacity().allocationCapacity()).isEqualTo(500);
        var prepared=StrategyEngineInputs.candidate(scoped.candidateInput(),Code.FAST).orElseThrow();assertThat(prepared.currentFundingAvailable()).isFalse();
        assertThat(total(prepared.simulation().monthlyAmounts().get(fixture.month))).isEqualTo(300);
    }
    @Test void mandatoryBulletSavingIsProtectedAndMissingRequiredPlanDefersGeneration(){
        var bullet=fixture.loan(2,20000,RateType.FIXED,RepaymentMethod.BULLET,null);var loans=List.of(fixture.loan(1,10000),bullet);
        assertThat(select(source(loans,List.of(),Map.of(),Map.of()),1L,List.of())).isEmpty();
        var source=source(loans,List.of(fixture.plan(2,0,50,100,fixture.asOf)),Map.of(),Map.of(2L,25L));
        var scoped=select(source,1L,List.of(new PlannedAmount(2,AllocationType.REPAYMENT_SAVING,0,null))).orElseThrow();
        assertThat(scoped.reservedRecurringCash()).isEqualTo(100);assertThat(scoped.reservedCurrentCash()).isEqualTo(100);
    }
    @Test void futureGraceGoalStillProtectsRepeatedFundsButNotAnIneligibleCurrentExtra(){
        var grace=fixture.loan(2,10000,RateType.FIXED,RepaymentMethod.GRACE_INSTALLMENT,fixture.month.plusMonths(1).atDay(15));
        var source=source(List.of(fixture.loan(1,10000),grace),List.of(fixture.plan(2,300,0,0,fixture.asOf)),Map.of(),Map.of());
        var scoped=select(source,1L,List.of()).orElseThrow();assertThat(scoped.reservedRecurringCash()).isEqualTo(300);assertThat(scoped.reservedCurrentCash()).isZero();
    }
    @Test void unknownCapacityStaysUnknownAndMissingSelectionFailsExplicitly(){
        var original=source(List.of(fixture.loan(1,10000)),List.of(),Map.of(),Map.of());var facts=original.household();
        var unknown=new RiskScenarioInput(new RiskInputs(facts.userId(),facts.asOf(),facts.month(),facts.loans(),facts.plans(),facts.finance(),fixture.cap(null,1000,1500,0,null),facts.due(),null,null,null),original.payments(),original.savings());
        var cap=select(unknown,1L,List.of()).orElseThrow().candidateInput().household().capacity();assertThat(cap.availableCash()).isNull();assertThat(cap.remainingAllocatable()).isNull();
        assertThatThrownBy(()->select(original,999L,List.of())).isInstanceOf(IllegalArgumentException.class);
    }
}
