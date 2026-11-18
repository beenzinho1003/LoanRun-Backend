package com.loanrun.finance.engine;

import static org.assertj.core.api.Assertions.*;
import com.loanrun.common.contract.FinanceModels.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class RepaymentTargetTest {
    final DefaultRepaymentEngine engine = new DefaultRepaymentEngine();
    final LocalDate june = LocalDate.of(2026, 6, 25);

    SimulationInput baseline(LoanFixture loan) {
        return new SimulationInput(List.of(loan.terms()), loan.snapshot, 12, Map.of(), Map.of(), null, "KEEP_PLANS");
    }
    TargetResult minimum(LoanFixture loan, LocalDate date) { return engine.minimumExtra(new TargetInput(baseline(loan), 1, date)); }

    @Test void independentSixMonthZeroRateTargetRequiresExactlyOneMillionExtra() {
        var loan = new LoanFixture(RepaymentMethod.EQUAL_PRINCIPAL); loan.rate = BigDecimal.ZERO;
        assertThat(minimum(loan, june).minimumMonthlyExtraCash()).isEqualTo(1_000_000);
        assertThat(minimum(loan, june).feasible()).isTrue();
        assertThat(minimum(loan, loan.maturity).minimumMonthlyExtraCash()).isZero();
    }

    @Test void inverseReturnsTheTotalGoalRatherThanAnIncrementOnTheOldPlan() {
        var loan = new LoanFixture(RepaymentMethod.EQUAL_PRINCIPAL); loan.rate = BigDecimal.ZERO;
        var old = new SimulationInput(List.of(loan.terms()), loan.snapshot, 12,
            Map.of(YearMonth.of(2026, 1), List.of(new PlannedAmount(1, AllocationType.EXTRA_PAYMENT, 99_000_000, null))),
            Map.of(), null, "KEEP_PLANS");
        assertThat(engine.minimumExtra(new TargetInput(old, 1, june)).minimumMonthlyExtraCash()).isEqualTo(1_000_000);
    }

    @Test void inverseIncludesKnownFeesAndPreservesUnknownCostWarnings() {
        var loan = new LoanFixture(RepaymentMethod.EQUAL_PRINCIPAL);
        loan.balance = 1_200; loan.rate = BigDecimal.ZERO; loan.feeMethod = FeeMethod.FIXED; loan.feeRate = BigDecimal.ONE;
        assertThat(minimum(loan, loan.next).minimumMonthlyExtraCash()).isEqualTo(1_111);
        loan.feeMethod = FeeMethod.UNKNOWN;
        var gross = minimum(loan, loan.next);
        assertThat(gross.minimumMonthlyExtraCash()).isEqualTo(1_100);
        assertThat(gross.warnings()).contains("FEE_UNKNOWN");
    }

    @Test void interestBearingMaturityPolicyInverseIsMinimalToTheWon() {
        var loan = new LoanFixture(); loan.policy = PrepaymentPolicy.KEEP_MATURITY;
        long minimum = minimum(loan, june).minimumMonthlyExtraCash();
        assertThat(meets(loan, minimum)).isTrue();
        assertThat(meets(loan, minimum - 1)).isFalse();
    }
    boolean meets(LoanFixture loan, long cash) {
        Map<YearMonth, List<PlannedAmount>> months = new HashMap<>();
        for (int i = 0; i < 6; i++) months.put(YearMonth.of(2026, 1).plusMonths(i),
            List.of(new PlannedAmount(1, AllocationType.EXTRA_PAYMENT, cash, null)));
        var result = engine.simulate(new SimulationInput(List.of(loan.terms()), loan.snapshot, 6, months, Map.of(), june, "KEEP_PLANS"));
        return result.projectedPayoffDate() != null && !result.projectedPayoffDate().isAfter(june);
    }

    @Test void bulletTargetUsesCurrentSavingsAndOnlyRemainingContributionDates() {
        var loan = new LoanFixture(RepaymentMethod.BULLET); loan.initialSaving = 1_000_000; loan.saving = 2_000_000;
        var target = minimum(loan, june);
        assertThat(target.minimumMonthlyExtraCash()).isEqualTo(1_666_667); assertThat(target.feasible()).isTrue();
        assertThat(minimum(loan, june.minusDays(6)).minimumMonthlyExtraCash()).isEqualTo(2_000_000);
        target = minimum(loan, loan.next.minusDays(1));
        assertThat(target.minimumMonthlyExtraCash()).isEqualTo(10_000_000); assertThat(target.feasible()).isFalse();
        assertThat(target.warnings()).contains("NO_SAVING_PERIOD");
        loan.saving = 12_000_000;
        assertThat(minimum(loan, loan.next.minusDays(1)).minimumMonthlyExtraCash()).isZero();
    }

    @Test void invalidOrImpossibleTargetsCannotBecomeSuccessfulZeroAmounts() {
        var loan = new LoanFixture();
        for (var date : List.of(loan.snapshot.minusDays(1), loan.maturity.plusDays(1), loan.next.minusDays(1))) {
            assertThat(minimum(loan, date).feasible()).isFalse();
            assertThat(minimum(loan, date).minimumMonthlyExtraCash()).isNull();
        }
        loan.method = RepaymentMethod.GRACE_INSTALLMENT; loan.postGrace = RepaymentMethod.EQUAL_PAYMENT;
        loan.grace = LocalDate.of(2026, 4, 25);
        assertThat(minimum(loan, LocalDate.of(2026, 3, 25)).feasible()).isFalse();
        assertThatIllegalArgumentException().isThrownBy(() -> engine.minimumExtra(new TargetInput(baseline(loan), 99, june)));
    }
}
