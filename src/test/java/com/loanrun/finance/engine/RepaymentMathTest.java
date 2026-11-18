package com.loanrun.finance.engine;

import static org.assertj.core.api.Assertions.*;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class RepaymentMathTest {
    @Test void independentTwelveMillionSixPercentExpectations() {
        assertThat(RepaymentMath.monthlyRate(new BigDecimal("6.00"))).isEqualByComparingTo("0.005");
        assertThat(RepaymentMath.annuity(12_000_000, new BigDecimal("6"), 12)).isEqualTo(1_032_797);
        assertThat(RepaymentMath.interest(12_000_000, new BigDecimal("6"))).isEqualTo(60_000);
        assertThat(RepaymentMath.fixedPrincipal(12_000_000, 12)).isEqualTo(1_000_000);
    }

    @Test void zeroRateAndOneWonUseHalfUpAtTheMoneyBoundary() {
        assertThat(RepaymentMath.annuity(12_000_000, BigDecimal.ZERO, 12)).isEqualTo(1_000_000);
        assertThat(RepaymentMath.fixedPrincipal(1, 2)).isEqualTo(1);
        assertThat(RepaymentMath.interest(100, new BigDecimal("6"))).isEqualTo(1);
        assertThat(RepaymentMath.annuity(1_000_000, new BigDecimal("0.000000000001"), 12)).isEqualTo(83_333);
    }

    @Test void requiredSavingsCeilAndImmediateShortageDoNotDoubleCountExistingAssets() {
        assertThat(RepaymentMath.requiredSaving(12_000_000, 2_000_000, 12)).isEqualTo(833_334);
        assertThat(RepaymentMath.requiredSaving(12_000_000, 2_000_000, 0)).isEqualTo(10_000_000);
        assertThat(RepaymentMath.requiredSaving(12_000_000, 13_000_000, 0)).isZero();
        assertThat(RepaymentMath.requiredSaving(0, 0, 12)).isZero();
    }

    @Test void invalidInputsAndOverflowAreRejectedInsteadOfWrapped() {
        assertThatIllegalArgumentException().isThrownBy(() -> RepaymentMath.annuity(1, BigDecimal.ZERO, 0));
        assertThatIllegalArgumentException().isThrownBy(() -> RepaymentMath.annuity(1, BigDecimal.ZERO, 1201));
        assertThatIllegalArgumentException().isThrownBy(() -> RepaymentMath.interest(1, new BigDecimal("-1")));
        assertThatIllegalArgumentException().isThrownBy(() -> RepaymentMath.requiredSaving(-1, 0, 12));
        assertThatThrownBy(() -> RepaymentMath.interest(Long.MAX_VALUE, new BigDecimal("2400")))
            .isInstanceOf(ArithmeticException.class);
    }
}
