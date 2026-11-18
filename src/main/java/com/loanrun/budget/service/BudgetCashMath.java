package com.loanrun.budget.service;

import java.math.BigInteger;

/** Cash arithmetic only; callers preserve unknown inputs and supply engine/ledger amounts. */
public final class BudgetCashMath {
    private BudgetCashMath() {}
    public record Expenses(long fixed, long variable, long discretionary, long savings) {
        public Expenses { nonnegative(fixed, variable, discretionary, savings); }
    }
    public record Capacity(long availableCash, long recurringCap, long allocationCapacity, long deficit) {}
    public record Settlement(String status, Long reserveSettled, Long carryOut) {}

    public static Capacity planning(long income, Expenses expenses, long scheduledCash, long previousOutstanding,
            long savingsUsedForDue, long carryIn) {
        nonnegative(income, scheduledCash, previousOutstanding, savingsUsedForDue, carryIn);
        BigInteger basic = BigInteger.valueOf(scheduledCash).add(BigInteger.valueOf(previousOutstanding))
            .subtract(BigInteger.valueOf(savingsUsedForDue));
        if (basic.signum() < 0) throw new IllegalArgumentException("Savings used exceed the supplied due burden");
        long available = subtractExpenses(BigInteger.valueOf(income).subtract(basic), expenses).longValueExact();
        return new Capacity(available, Math.max(available, 0), Math.max(Math.addExact(available, carryIn), 0),
            available < 0 ? Math.negateExact(available) : 0);
    }

    /** cashPaid already excludes prior savings used; no scheduled payment is subtracted again. */
    public static long actual(long income, Expenses expenses, long cashPaid, long savingsAdded) {
        nonnegative(income, cashPaid, savingsAdded);
        return subtractExpenses(BigInteger.valueOf(income), expenses).subtract(BigInteger.valueOf(cashPaid))
            .subtract(BigInteger.valueOf(savingsAdded)).longValueExact();
    }

    public static Settlement settle(boolean closed, Long actualAvailable, long carryIn, long reserve) {
        nonnegative(carryIn, reserve);
        if (!closed) return new Settlement("OPEN", null, null);
        if (actualAvailable == null) return new Settlement("INSUFFICIENT", null, null);
        long surplus = Math.max(Math.addExact(actualAvailable, carryIn), 0);
        long retained = Math.min(reserve, surplus);
        return new Settlement("SETTLED", retained, surplus - retained);
    }

    private static BigInteger subtractExpenses(BigInteger start, Expenses expenses) {
        return start.subtract(BigInteger.valueOf(expenses.fixed())).subtract(BigInteger.valueOf(expenses.variable()))
            .subtract(BigInteger.valueOf(expenses.discretionary())).subtract(BigInteger.valueOf(expenses.savings()));
    }
    private static void nonnegative(long... amounts) {
        for (long amount : amounts) if (amount < 0) throw new IllegalArgumentException("Negative cash input");
    }
}
