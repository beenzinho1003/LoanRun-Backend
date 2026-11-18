package com.loanrun.finance.engine;

import static org.assertj.core.api.Assertions.*;
import com.loanrun.common.contract.FinanceModels.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class DefaultRepaymentEngineTest {
    final DefaultRepaymentEngine engine = new DefaultRepaymentEngine();
    final YearMonth january = YearMonth.of(2026, 1);

    SimulationInput input(LoanFixture loan, int months, Map<YearMonth, List<PlannedAmount>> amounts) {
        return new SimulationInput(List.of(loan.terms()), loan.snapshot, months, amounts, Map.of(), null, "KEEP_PLANS");
    }
    Map<YearMonth, List<PlannedAmount>> repeat(long id, AllocationType type, long cash, int months) {
        Map<YearMonth, List<PlannedAmount>> amounts = new HashMap<>();
        for (int i = 0; i < months; i++) amounts.put(january.plusMonths(i), List.of(new PlannedAmount(id, type, cash, null)));
        return amounts;
    }
    SimulationResult simulate(LoanFixture loan) { return engine.simulate(input(loan, 12, Map.of())); }

    @Test void equalPaymentMatchesIndependentFormulaAndConservesEveryWon() {
        var result = simulate(new LoanFixture());
        assertThat(result.rows()).hasSize(12);
        assertThat(result.rows().getFirst().scheduledPrincipal()).isEqualTo(972_797);
        assertThat(result.rows().getFirst().interest()).isEqualTo(60_000);
        assertThat(result.rows().subList(0, 11)).allSatisfy(row ->
            assertThat(row.scheduledPrincipal() + row.interest()).isEqualTo(1_032_797));
        assertThat(result.totalPrincipal()).isEqualTo(12_000_000);
        assertThat(result.rows().getLast().balance()).isZero();
        assertThat(result.projectedPayoffDate()).isEqualTo(LocalDate.of(2026, 12, 25));
        assertThat(result.totalCash()).isEqualTo(12_000_000 + result.totalInterest());
        assertThat(result.insufficient()).isFalse();
    }

    @Test void equalPrincipalHasKnownTotalInterestAndFinalBasicPayment() {
        var result = simulate(new LoanFixture(RepaymentMethod.EQUAL_PRINCIPAL));
        assertThat(result.rows()).allSatisfy(row -> assertThat(row.scheduledPrincipal()).isEqualTo(1_000_000));
        assertThat(result.totalInterest()).isEqualTo(390_000);
        assertThat(result.rows().getFirst().interest()).isEqualTo(60_000);
        assertThat(result.rows().getLast().interest()).isEqualTo(5_000);
        assertThat(result.totalPrincipal()).isEqualTo(12_000_000);
    }

    @ParameterizedTest
    @EnumSource(value = RepaymentMethod.class, names = {"EQUAL_PAYMENT", "EQUAL_PRINCIPAL"})
    void zeroInterestAndFinalOneWonNeverGoNegative(RepaymentMethod method) {
        var loan = new LoanFixture(method); loan.rate = BigDecimal.ZERO;
        var result = simulate(loan);
        assertThat(result.totalInterest()).isZero();
        assertThat(result.rows()).allSatisfy(row -> assertThat(row.scheduledPrincipal()).isEqualTo(1_000_000));
        loan.balance = 1;
        result = simulate(loan);
        assertThat(result.rows().getLast().scheduledPrincipal()).isEqualTo(1);
        assertThat(result.rows().getLast().balance()).isZero();
        assertThat(result.totalPrincipal()).isEqualTo(1);
        assertThat(result.rows()).allSatisfy(row -> assertThat(row.balance()).isNotNegative());
    }

    @Test void bulletInterestContinuesDespiteSavingAndFinalContributionIsCapped() {
        var loan = new LoanFixture(RepaymentMethod.BULLET);
        loan.initialSaving = 2_000_000; loan.saving = 2_000_000;
        var result = engine.simulate(input(loan, 12, repeat(1, AllocationType.REPAYMENT_SAVING, 833_334, 12)));
        assertThat(result.rows().subList(0, 11)).allSatisfy(row -> {
            assertThat(row.balance()).isEqualTo(12_000_000);
            assertThat(row.scheduledPrincipal()).isZero();
            assertThat(row.interest()).isEqualTo(60_000);
        });
        assertThat(result.rows().getLast().saving()).isEqualTo(833_326);
        assertThat(result.rows().getLast().remainingSaving()).isZero();
        assertThat(result.totalInterest()).isEqualTo(720_000);
        assertThat(result.totalCash()).isEqualTo(10_720_000);
        assertThat(result.insufficient()).isFalse();
    }

    @Test void bulletUnfundedMaturityDoesNotClaimAnAchievablePayoff() {
        var loan = new LoanFixture(RepaymentMethod.BULLET);
        var result = simulate(loan);
        assertThat(result.rows().getLast().scheduledPrincipal()).isEqualTo(12_000_000);
        assertThat(result.totalInterest()).isEqualTo(720_000);
        assertThat(result.insufficient()).isTrue();
        assertThat(result.projectedPayoffDate()).isNull();
        assertThat(result.warnings()).contains("BULLET_SAVINGS_SHORTFALL");
    }

    @Test void existingBulletSavingsAreNotAutomaticallyRepaidBeforeMaturity() {
        var loan = new LoanFixture(RepaymentMethod.BULLET); loan.initialSaving = 2_000_000; loan.saving = 13_000_000;
        var result = simulate(loan);
        assertThat(result.totalInterest()).isEqualTo(720_000);
        assertThat(result.rows().getLast().remainingSaving()).isEqualTo(1_000_000);
        assertThat(result.totalCash()).isEqualTo(720_000);
        assertThat(result.projectedPayoffDate()).isEqualTo(loan.maturity);
    }

    @ParameterizedTest
    @EnumSource(value = RepaymentMethod.class, names = {"EQUAL_PAYMENT", "EQUAL_PRINCIPAL"})
    void graceEndsOnTheFirstDueOnOrAfterItsEnd(RepaymentMethod postGrace) {
        var loan = new LoanFixture(RepaymentMethod.GRACE_INSTALLMENT);
        loan.grace = LocalDate.of(2026, 4, 25); loan.postGrace = postGrace;
        var result = engine.simulate(input(loan, 12, repeat(1, AllocationType.EXTRA_PAYMENT, 1_000, 12)));
        assertThat(result.rows().subList(0, 3)).allSatisfy(row -> {
            assertThat(row.scheduledPrincipal()).isZero(); assertThat(row.extraPrincipal()).isZero();
            assertThat(row.interest()).isEqualTo(60_000);
        });
        assertThat(result.rows().get(3).scheduledPrincipal()).isPositive();
        assertThat(result.rows().get(3).extraPrincipal()).isEqualTo(1_000);
        assertThat(result.totalPrincipal()).isEqualTo(12_000_000);
        assertThat(result.warnings()).contains("GRACE_EXTRA_NOT_APPLIED");
        loan.grace = loan.grace.plusDays(1);
        assertThat(simulate(loan).rows().get(3).scheduledPrincipal()).isZero();
        assertThat(simulate(loan).rows().get(4).scheduledPrincipal()).isPositive();
    }

    @Test void threeGraceMonthsThenNinePrincipalInstallmentsMatchTheContract() {
        var loan = new LoanFixture(RepaymentMethod.GRACE_INSTALLMENT);
        loan.grace = LocalDate.of(2026, 4, 25); loan.postGrace = RepaymentMethod.EQUAL_PRINCIPAL;
        var rows = simulate(loan).rows();
        assertThat(rows.get(3).scheduledPrincipal()).isEqualTo(1_333_333);
        assertThat(rows.get(3).interest()).isEqualTo(60_000);
        assertThat(rows.getLast().scheduledPrincipal()).isEqualTo(1_333_336);
        assertThat(rows.getLast().balance()).isZero();
    }

    @Test void thirtyFirstIsRestoredAfterShortMonthsIncludingLeapFebruary() {
        var loan = new LoanFixture(); loan.day = 31; loan.next = LocalDate.of(2026, 1, 31); loan.maturity = LocalDate.of(2026, 12, 31);
        assertThat(simulate(loan).rows().stream().map(SimulationRow::date).limit(3)).containsExactly(
            LocalDate.of(2026, 1, 31), LocalDate.of(2026, 2, 28), LocalDate.of(2026, 3, 31));
        loan.snapshot = LocalDate.of(2028, 1, 1); loan.next = LocalDate.of(2028, 1, 31); loan.maturity = LocalDate.of(2028, 12, 31);
        assertThat(simulate(loan).rows().get(1).date()).isEqualTo(LocalDate.of(2028, 2, 29));
    }

    @Test void horizonCountsCalendarMonthsAndNeverReplaysASkippedPastDue() {
        var loan = new LoanFixture();
        assertThat(engine.simulate(input(loan, 1, Map.of())).rows()).hasSize(1);
        var request = new SimulationInput(List.of(loan.terms()), LocalDate.of(2026, 1, 26), 1, Map.of(), Map.of(), null, "NONE");
        assertThat(engine.simulate(request).rows()).isEmpty();
        request = new SimulationInput(List.of(loan.terms()), LocalDate.of(2026, 1, 26), 2, Map.of(), Map.of(), null, "NONE");
        assertThat(engine.simulate(request).rows()).extracting(SimulationRow::date).containsExactly(LocalDate.of(2026, 2, 25));
        loan.maturity = LocalDate.of(2026, 12, 10);
        assertThat(simulate(loan).rows().getLast().date()).isEqualTo(loan.maturity);
        loan.maturity = LocalDate.of(2026, 12, 30);
        assertThat(simulate(loan).rows().getLast().date()).isEqualTo(loan.maturity);
    }

    @Test void contractualNextPaymentOverridesOnlyItsOwnInstallment() {
        var loan = new LoanFixture(); loan.nextAmount = 1_500_000L;
        var rows = simulate(loan).rows();
        assertThat(rows.getFirst().scheduledPrincipal()).isEqualTo(1_440_000);
        assertThat(rows.get(1).scheduledPrincipal() + rows.get(1).interest()).isEqualTo(1_032_797);
        assertThat(rows.get(1).interest()).isEqualTo(52_800);
    }

    @ParameterizedTest
    @EnumSource(value = RepaymentMethod.class, names = {"EQUAL_PAYMENT", "EQUAL_PRINCIPAL"})
    void paymentPolicyKeepsTheAnchorWhileMaturityPolicyRepricesAfterExtra(RepaymentMethod method) {
        var loan = new LoanFixture(method);
        var amounts = repeat(1, AllocationType.EXTRA_PAYMENT, 1_000_000, 1);
        var keepPayment = engine.simulate(input(loan, 12, amounts));
        loan.policy = PrepaymentPolicy.KEEP_MATURITY;
        var keepMaturity = engine.simulate(input(loan, 12, amounts));
        var paymentRow = keepPayment.rows().get(1); var maturityRow = keepMaturity.rows().get(1);
        assertThat(paymentRow.scheduledPrincipal() + paymentRow.interest())
            .isGreaterThan(maturityRow.scheduledPrincipal() + maturityRow.interest());
        assertThat(keepPayment.projectedPayoffDate()).isBefore(keepMaturity.projectedPayoffDate());
        assertThat(keepMaturity.projectedPayoffDate()).isEqualTo(loan.maturity);
        if (method == RepaymentMethod.EQUAL_PRINCIPAL) {
            assertThat(paymentRow.scheduledPrincipal()).isEqualTo(1_000_000);
            assertThat(maturityRow.scheduledPrincipal()).isEqualTo(909_091);
        }
    }

    @Test void existingAnchorsAndAbsoluteRateOverridesRemainRequestLocal() {
        var loan = new LoanFixture(); loan.anchor = 1_032_797L; loan.nextAmount = 1_032_797L; loan.rateType = RateType.VARIABLE;
        var original = loan.terms();
        var changed = engine.simulate(new SimulationInput(List.of(original), loan.snapshot, 1, Map.of(), Map.of(1L, new BigDecimal("7")), null, "NONE"));
        assertThat(changed.rows().getFirst().interest()).isEqualTo(70_000);
        assertThat(changed.rows().getFirst().scheduledPrincipal() + changed.rows().getFirst().interest()).isGreaterThan(1_032_797);
        assertThat(original.annualRate()).isEqualByComparingTo("6");
        assertThat(simulate(loan).rows().getFirst().interest()).isEqualTo(60_000);
        loan.method = RepaymentMethod.EQUAL_PRINCIPAL; loan.fixedPrincipal = 500_000L; loan.nextAmount = null;
        assertThat(simulate(loan).rows().getFirst().scheduledPrincipal()).isEqualTo(500_000);
    }

    @Test void paidDeletedAndEmptyInputsCannotCreateFuturePaymentsOrInventPayoffDates() {
        var loan = new LoanFixture(); loan.status = LoanStatus.DELETED;
        assertThat(simulate(loan).rows()).isEmpty();
        loan.status = LoanStatus.PAID; assertThat(simulate(loan).rows()).isEmpty();
        loan.status = LoanStatus.ACTIVE; loan.balance = 0; assertThat(simulate(loan).rows()).isEmpty();
        var result = engine.simulate(new SimulationInput(List.of(), loan.snapshot, 1, Map.of(), Map.of(), null, "NONE"));
        assertThat(result.projectedPayoffDate()).isNull(); assertThat(result.totalPrincipal()).isZero();
    }

    @Test void expiredMaturityAndInsufficientContractualPaymentRemainExplicitFailures() {
        var loan = new LoanFixture();
        var expired = engine.simulate(new SimulationInput(List.of(loan.terms()), LocalDate.of(2027, 1, 1), 12, Map.of(), Map.of(), null, "NONE"));
        assertThat(expired.insufficient()).isTrue(); assertThat(expired.warnings()).contains("MATURITY_PASSED");
        loan.nextAmount = 0L;
        var result = simulate(loan);
        assertThat(result.insufficient()).isTrue(); assertThat(result.projectedPayoffDate()).isNull();
        assertThat(result.warnings()).contains("CONTRACTUAL_AMOUNT_BELOW_INTEREST");
        loan.nextAmount = null; loan.anchor = 0L;
        assertThat(simulate(loan).insufficient()).isTrue();
        assertThat(simulate(loan).warnings()).contains("BASIC_PAYMENT_BELOW_INTEREST");
    }

    @Test void invalidOwnersTermsAndAllocationReferencesNeverProduceAResult() {
        var a = new LoanFixture(); var b = new LoanFixture(); b.id = 2; b.owner = 99;
        assertThatIllegalArgumentException().isThrownBy(() -> engine.simulate(new SimulationInput(
            List.of(a.terms(), b.terms()), a.snapshot, 12, Map.of(), Map.of(), null, "NONE")));
        assertThatIllegalArgumentException().isThrownBy(() -> engine.simulate(new SimulationInput(
            List.of(a.terms(), a.terms()), a.snapshot, 12, Map.of(), Map.of(), null, "NONE")));
        assertThatIllegalArgumentException().isThrownBy(() -> engine.simulate(input(a, 12, repeat(99, AllocationType.EXTRA_PAYMENT, 1, 1))));
        assertThatIllegalArgumentException().isThrownBy(() -> engine.simulate(input(a, 12, repeat(1, AllocationType.REPAYMENT_SAVING, 1, 1))));
        a.day = 0; assertThatIllegalArgumentException().isThrownBy(() -> simulate(a));
        a.day = 25; a.balance = a.original + 1; assertThatIllegalArgumentException().isThrownBy(() -> simulate(a));
        a.balance = 1; a.method = RepaymentMethod.GRACE_INSTALLMENT;
        assertThatIllegalArgumentException().isThrownBy(() -> simulate(a));
    }

    @Test void repeatedAndConcurrentCallsLeaveNoSharedStateOrMutableResults() {
        var request = input(new LoanFixture(), 12, repeat(1, AllocationType.EXTRA_PAYMENT, 100_000, 12));
        var expected = engine.simulate(request);
        assertThat(IntStream.range(0, 24).parallel().mapToObj(i -> engine.simulate(request)).toList())
            .allSatisfy(result -> assertThat(result).isEqualTo(expected));
        assertThat(request.terms().getFirst().outstanding()).isEqualTo(12_000_000);
        assertThatThrownBy(() -> expected.rows().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> expected.warnings().clear()).isInstanceOf(UnsupportedOperationException.class);
    }
}
