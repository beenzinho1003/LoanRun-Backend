package com.loanrun.finance.engine;

import static org.assertj.core.api.Assertions.*;
import com.loanrun.common.contract.FinanceModels.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PrepaymentFeesTest {
    final DefaultRepaymentEngine engine = new DefaultRepaymentEngine();

    SimulationResult simulate(LoanFixture loan, long cash) {
        return engine.simulate(new SimulationInput(List.of(loan.terms()), loan.snapshot, 1,
            Map.of(YearMonth.from(loan.snapshot), List.of(new PlannedAmount(1, AllocationType.EXTRA_PAYMENT, cash, null))),
            Map.of(), null, "KEEP_PLANS"));
    }

    @Test void fixedFeeIsInsideCashAndNeverReducesPrincipal() {
        var loan = new LoanFixture(); loan.feeMethod = FeeMethod.FIXED; loan.feeRate = new BigDecimal("1");
        var row = simulate(loan, 101_000).rows().getFirst();
        assertThat(row.extraPrincipal()).isEqualTo(100_000);
        assertThat(row.fee()).isEqualTo(1_000);
        assertThat(row.extraCash()).isEqualTo(101_000);
        assertThat(row.balance()).isEqualTo(12_000_000 - row.scheduledPrincipal() - 100_000);
        assertThat(simulate(loan, 101_000).totalFee()).isEqualTo(1_000);
    }

    @Test void linearFeeUsesExactDayRatioAndEndsWithoutAnExtraCharge() {
        var loan = new LoanFixture(); loan.feeMethod = FeeMethod.LINEAR; loan.feeRate = new BigDecimal("1");
        loan.feeStart = LocalDate.of(2026, 1, 1); loan.feeEnd = LocalDate.of(2026, 1, 21);
        loan.next = LocalDate.of(2026, 1, 11); loan.day = 11;
        var row = simulate(loan, 100_500).rows().getFirst();
        assertThat(row.extraPrincipal()).isEqualTo(100_000); assertThat(row.fee()).isEqualTo(500);
        assertThat(PrepaymentFees.fee(loan.terms(), loan.feeEnd, 100_000)).isZero();
        assertThat(PrepaymentFees.fee(loan.terms(), loan.feeEnd.plusDays(1), 100_000)).isZero();
        assertThat(PrepaymentFees.fee(loan.terms(), loan.feeStart.minusDays(1), 100_000)).isZero();
    }

    @Test void explicitNoneUnknownAndUnsupportedContractConditionsRemainDistinct() {
        var loan = new LoanFixture(); loan.feeMethod = FeeMethod.UNKNOWN;
        var result = simulate(loan, 100_000);
        assertThat(result.rows().getFirst().fee()).isNull();
        assertThat(result.totalFee()).isNull(); assertThat(result.totalCash()).isNull();
        assertThat(result.feeComparisonBasis()).isEqualTo("GROSS_INTEREST");
        assertThat(result.warnings()).contains("FEE_UNKNOWN");
        loan.feeMethod = FeeMethod.FIXED; loan.feeRate = BigDecimal.ONE; loan.type = LoanType.CREDIT;
        assertThat(simulate(loan, 100_000).totalFee()).isNull();
        loan.feeMethod = FeeMethod.NONE;
        assertThat(simulate(loan, 100_000).totalFee()).isZero();
        assertThat(simulate(loan, 100_000).feeComparisonBasis()).isEqualTo("NET");
        loan.feeMethod = FeeMethod.UNKNOWN; loan.feeEnd = loan.next.minusDays(1);
        assertThat(simulate(loan, 100_000).totalFee()).isZero();
    }

    @Test void fixedFeeEndIsInclusiveWhileLinearRatioIsZeroOnTheEndDate() {
        var loan = new LoanFixture(); loan.feeMethod = FeeMethod.FIXED; loan.feeRate = BigDecimal.ONE;
        loan.feeEnd = loan.next;
        assertThat(PrepaymentFees.fee(loan.terms(), loan.feeEnd, 100_000)).isEqualTo(1_000);
        assertThat(PrepaymentFees.fee(loan.terms(), loan.feeEnd.plusDays(1), 100_000)).isZero();
    }

    @Test void remainingPrincipalAndRoundedFeeCannotExceedCashEvenByOneWon() {
        var loan = new LoanFixture(); loan.feeMethod = FeeMethod.FIXED; loan.feeRate = new BigDecimal("50");
        assertThat(PrepaymentFees.withinCash(loan.terms(), loan.next, 100, 1).principal()).isZero();
        var extra = PrepaymentFees.withinCash(loan.terms(), loan.next, 100, 149);
        assertThat(extra.principal()).isEqualTo(99); assertThat(extra.fee()).isEqualTo(50);
        assertThat(extra.cash()).isEqualTo(149);
        extra = PrepaymentFees.withinCash(loan.terms(), loan.next, 100, 1_000);
        assertThat(extra.principal()).isEqualTo(100); assertThat(extra.cash()).isEqualTo(150);
    }

    @Test void invalidConfirmedFeeDatesAndRatesFailInsteadOfBecomingZero() {
        var loan = new LoanFixture(); loan.feeMethod = FeeMethod.FIXED;
        assertThatIllegalArgumentException().isThrownBy(() -> simulate(loan, 100));
        loan.feeRate = BigDecimal.ONE; loan.feeMethod = FeeMethod.LINEAR;
        assertThatIllegalArgumentException().isThrownBy(() -> simulate(loan, 100));
        loan.feeStart = loan.next; loan.feeEnd = loan.next;
        assertThatIllegalArgumentException().isThrownBy(() -> simulate(loan, 100));
    }
}
