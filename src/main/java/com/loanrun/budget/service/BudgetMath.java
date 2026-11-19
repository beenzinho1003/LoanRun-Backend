package com.loanrun.budget.service;

import com.loanrun.budget.domain.MonthlyBudget;
import com.loanrun.budget.dto.BudgetDtos.Metrics;
import com.loanrun.common.contract.FinanceModels.*;
import java.math.BigInteger;
import java.time.YearMonth;

/** Budget cash arithmetic only. Interest/schedules/savings requirements remain owned by Team 1. */
public final class BudgetMath {
    private BudgetMath() {}
    public static Metrics calculate(MonthlyBudget budget, Long planningIncome, Long actualIncome, String incomeStatus,
            DueSummary due, PaymentSummary payment, SavingSummary saving, long carryIn, long usedExtraCash, YearMonth currentMonth) {
        long arrears = due.previousOutstandingCash();
        var expenses = budget == null ? null : new BudgetCashMath.Expenses(budget.fixedEssentialExpenses(),
            budget.variableEssentialExpenses(), budget.discretionaryExpenses(), budget.savings());
        var plan = budget == null || planningIncome == null ? null : BudgetCashMath.planning(planningIncome, expenses,
            due.scheduledCash(), arrears, due.savingsUsedForDue(), carryIn);
        Long available = plan == null ? null : plan.availableCash();
        Long capacity = plan == null ? null : plan.allocationCapacity();
        Long actual = budget == null || !budget.expensesConfirmed() || actualIncome == null || "FORECAST".equals(incomeStatus)
            || due.unrecordedCount() > 0 || !payment.observed() || !saving.observed()
            ? null : BudgetCashMath.actual(actualIncome, expenses, payment.cashPaid(), saving.additions());
        boolean closed = budget != null && budget.month().isBefore(currentMonth);
        var settlement = BudgetCashMath.settle(closed, actual, carryIn, budget == null ? 0 : budget.reserveAmount());
        Long actualDiscretionary = budget != null && budget.expensesConfirmed() ? budget.discretionaryExpenses() : null;
        Long limit = budget == null ? null : budget.discretionaryLimit();
        return new Metrics(available, planningIncome, actual, carryIn, capacity,
            plan == null ? null : plan.recurringCap(), plan == null ? null : plan.deficit(),
            capacity == null ? null : Math.max(difference(capacity, usedExtraCash, saving.additions()), 0), settlement.status(), settlement.carryOut(),
            settlement.reserveSettled(), actualIncome, incomeStatus, arrears, payment.cashPaid(), saving.additions(), limit, actualDiscretionary,
            actualDiscretionary == null || limit == null ? null : Math.max(limit - actualDiscretionary, 0));
    }

    public static long difference(long start, long... amounts) {
        BigInteger value = BigInteger.valueOf(start);
        for (long amount : amounts) value = value.subtract(BigInteger.valueOf(amount));
        return value.longValueExact();
    }
}
