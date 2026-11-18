package com.loanrun;

import static org.assertj.core.api.Assertions.*;
import com.loanrun.budget.service.BudgetCashMath;
import com.loanrun.budget.service.BudgetCashMath.Expenses;
import org.junit.jupiter.api.Test;

class BudgetCashMathTest {
    final Expenses expenses = new Expenses(800000, 200000, 500000, 200000);
    @Test void recurringCapacityExcludesCarryButCurrentCapacityIncludesIt() {
        var result = BudgetCashMath.planning(3000000, expenses, 800000, 0, 0, 100000);
        assertThat(result.availableCash()).isEqualTo(500000);
        assertThat(result.recurringCap()).isEqualTo(500000);
        assertThat(result.allocationCapacity()).isEqualTo(600000);
        assertThat(result.deficit()).isZero();
    }
    @Test void deficitStaysSignedAndCarryDoesNotBecomeRecurringIncome() {
        var result = BudgetCashMath.planning(2000000, expenses, 800000, 0, 0, 600000);
        assertThat(result.availableCash()).isEqualTo(-500000);
        assertThat(result.recurringCap()).isZero();
        assertThat(result.allocationCapacity()).isEqualTo(100000);
        assertThat(result.deficit()).isEqualTo(500000);
    }
    @Test void priorSavingsFundedPrincipalIsExcludedFromCashBurdenOnlyOnce() {
        var result = BudgetCashMath.planning(3000000, expenses, 12060000, 0, 12000000, 0);
        assertThat(result.availableCash()).isEqualTo(1240000);
        assertThat(BudgetCashMath.actual(3000000, expenses, 60000, 100000)).isEqualTo(1140000);
    }
    @Test void actualUsesOnlyRealCashAndNeverSubtractsFullScheduledBurdenAgain() {
        assertThat(BudgetCashMath.actual(3000000, expenses, 300000, 100000)).isEqualTo(900000);
        assertThat(BudgetCashMath.planning(3000000, expenses, 800000, 200000, 0, 0).availableCash()).isEqualTo(300000);
    }
    @Test void settlementDistinguishesUnknownZeroDeficitAndRetainedReserve() {
        assertThat(BudgetCashMath.settle(false, 300000L, 0, 100000).carryOut()).isNull();
        var unknown = BudgetCashMath.settle(true, null, 100000, 100000);
        assertThat(unknown.status()).isEqualTo("INSUFFICIENT");assertThat(unknown.carryOut()).isNull();
        var positive = BudgetCashMath.settle(true, 300000L, 0, 100000);
        assertThat(positive.reserveSettled()).isEqualTo(100000);assertThat(positive.carryOut()).isEqualTo(200000);
        assertThat(BudgetCashMath.settle(true, 0L, 0, 100000).carryOut()).isZero();
        assertThat(BudgetCashMath.settle(true, -1L, 0, 100000).carryOut()).isZero();
    }
    @Test void invalidSourcesAndOverflowAreRejectedInsteadOfWrapping() {
        assertThatThrownBy(() -> BudgetCashMath.planning(0, expenses, 0, 0, 1, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Expenses(-1, 0, 0, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BudgetCashMath.planning(Long.MAX_VALUE, new Expenses(0,0,0,0), 0,0,0,1)).isInstanceOf(ArithmeticException.class);
        assertThatThrownBy(() -> BudgetCashMath.settle(true, Long.MAX_VALUE, 1, 0)).isInstanceOf(ArithmeticException.class);
    }
}
