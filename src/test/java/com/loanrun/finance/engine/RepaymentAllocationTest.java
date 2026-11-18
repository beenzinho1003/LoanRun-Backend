package com.loanrun.finance.engine;

import static org.assertj.core.api.Assertions.*;
import com.loanrun.common.contract.FinanceModels.*;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.*;
import org.junit.jupiter.api.Test;

class RepaymentAllocationTest {
    final DefaultRepaymentEngine engine = new DefaultRepaymentEngine();
    final YearMonth january = YearMonth.of(2026, 1);

    LoanFixture loan(long id, long balance, long principal) {
        var loan = new LoanFixture(RepaymentMethod.EQUAL_PRINCIPAL);
        loan.id = id; loan.balance = balance; loan.rate = BigDecimal.ZERO; loan.fixedPrincipal = principal;
        return loan;
    }
    PlannedAmount amount(long id, long cash, Integer priority) { return new PlannedAmount(id, AllocationType.EXTRA_PAYMENT, cash, priority); }
    SimulationResult simulate(List<LoanFixture> loans, Map<YearMonth, List<PlannedAmount>> amounts, String rule, int months) {
        return engine.simulate(new SimulationInput(loans.stream().map(LoanFixture::terms).toList(), january.atDay(1),
            months, amounts, Map.of(), null, rule));
    }

    @Test void priorityReusesOnlyExistingExtraCashIncludingInMonthsAfterItsFirstLoanHasPaidOff() {
        var a = loan(1, 100, 50); var b = loan(2, 1_000, 100);
        var amounts = List.of(amount(1, 200, 1), amount(2, 0, 2));
        var result = simulate(List.of(a, b), Map.of(january, amounts, january.plusMonths(1), amounts), "INTEREST_SAVING", 2);
        assertThat(result.rows()).hasSize(3);
        assertThat(result.rows().get(0).scheduledPrincipal()).isEqualTo(50);
        assertThat(result.rows().get(0).extraPrincipal()).isEqualTo(50);
        assertThat(result.rows().get(1).extraPrincipal()).isEqualTo(150);
        assertThat(result.rows().get(2).extraPrincipal()).isEqualTo(200);
        assertThat(result.rows().get(2).balance()).isEqualTo(450);
    }

    @Test void parallelReweightsCurrentBalancesEveryPeriodAndCapsTheHouseholdCash() {
        var a = loan(1, 1_000, 100); var b = loan(2, 3_000, 100);
        var amounts = List.of(amount(1, 1, 1), amount(2, 379, 1));
        var result = simulate(List.of(b, a), Map.of(january, amounts, january.plusMonths(1), amounts), "PARALLEL", 2);
        assertThat(result.rows()).extracting(SimulationRow::extraCash).containsExactly(90L, 290L, 84L, 296L);
        assertThat(result.rows().stream().mapToLong(SimulationRow::extraCash).sum()).isEqualTo(760);
    }

    @Test void parallelOneWonRemaindersAreAssignedByLoanIdRatherThanInputOrder() {
        var result = simulate(List.of(loan(3, 1_000, 0), loan(2, 1_000, 0), loan(1, 1_000, 0)),
            Map.of(january, List.of(amount(3, 2, 1), amount(2, 0, 1), amount(1, 0, 1))), "PARALLEL", 1);
        assertThat(result.rows()).extracting(SimulationRow::extraPrincipal).containsExactly(1L, 1L, 0L);
    }

    @Test void manualAmountsAndExplicitZeroArePreservedInsideAStrategyMonth() {
        var result = simulate(List.of(loan(1, 1_000, 0), loan(2, 1_000, 0)),
            Map.of(january, List.of(amount(1, 0, null), amount(2, 200, null)),
                january.plusMonths(1), List.of(amount(1, 100, 1), amount(2, 0, 2))), "INTEREST_SAVING", 2);
        assertThat(result.rows()).extracting(SimulationRow::extraCash).containsExactly(0L, 200L, 100L, 0L);
    }

    @Test void anUnusedManualCapDoesNotFlowIntoAnotherLoansPlan() {
        var result = simulate(List.of(loan(1, 100, 0), loan(2, 1_000, 0)),
            Map.of(january, List.of(amount(1, 500, null), amount(2, 10, null))), "KEEP_PLANS", 1);
        assertThat(result.rows()).extracting(SimulationRow::extraCash).containsExactly(100L, 10L);
    }

    @Test void parallelCanUseAOneWonRemainderOnTheLoanWhoseFeeAllowsIt() {
        var a = loan(1, 10, 0); a.feeMethod = FeeMethod.FIXED; a.feeRate = new BigDecimal("100");
        var b = loan(2, 10, 0);
        var result = simulate(List.of(a, b), Map.of(january, List.of(amount(1, 1, 1), amount(2, 0, 1))), "PARALLEL", 1);
        assertThat(result.rows()).extracting(SimulationRow::extraPrincipal).containsExactly(0L, 1L);
        assertThat(result.rows().stream().mapToLong(SimulationRow::extraCash).sum()).isEqualTo(1);
    }

    @Test void repeatedAllocationPassesRoundThePeriodsFeeOnlyOnce() {
        var a = loan(1, 3, 0); a.feeMethod = FeeMethod.FIXED; a.feeRate = new BigDecimal("50");
        var b = loan(2, 1, 0);
        var result = simulate(List.of(a, b), Map.of(january, List.of(amount(1, 6, 1), amount(2, 0, 1))), "PARALLEL", 1);
        assertThat(result.rows().getFirst().extraPrincipal()).isEqualTo(3);
        assertThat(result.rows().getFirst().fee()).isEqualTo(2);
        assertThat(result.rows().getFirst().extraCash()).isEqualTo(5);
        assertThat(result.rows().getLast().extraCash()).isEqualTo(1);
        assertThat(result.totalFee()).isEqualTo(2);
    }

    @Test void sameMonthInterestIsComputedBeforeExtraAndStopsAfterCompletePayoff() {
        var loan = new LoanFixture(); loan.feeMethod = FeeMethod.FIXED; loan.feeRate = BigDecimal.ONE;
        var result = simulate(List.of(loan), Map.of(january, List.of(amount(1, 20_000_000, null))), "KEEP_PLANS", 12);
        assertThat(result.rows()).hasSize(1); assertThat(result.rows().getFirst().interest()).isEqualTo(60_000);
        assertThat(result.rows().getFirst().balance()).isZero();
        assertThat(result.totalPrincipal()).isEqualTo(12_000_000);
        assertThat(result.rows().getFirst().extraCash()).isLessThan(20_000_000);
    }
}
