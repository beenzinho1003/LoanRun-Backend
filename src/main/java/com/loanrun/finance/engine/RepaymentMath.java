package com.loanrun.finance.engine;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/** KRW is rounded only at a monetary boundary. Rates are annual percentage values. */
public final class RepaymentMath {
    static final MathContext PRECISION = MathContext.DECIMAL128;
    private RepaymentMath() {}

    public static BigDecimal monthlyRate(BigDecimal annualRate) {
        if (annualRate == null || annualRate.signum() < 0)
            throw new IllegalArgumentException("An annual rate is required and must be nonnegative");
        return annualRate.divide(BigDecimal.valueOf(1200), PRECISION);
    }

    public static long interest(long balance, BigDecimal annualRate) {
        requireAmount(balance);
        return won(BigDecimal.valueOf(balance).multiply(monthlyRate(annualRate), PRECISION));
    }

    public static long annuity(long balance, BigDecimal annualRate, int periods) {
        requireAmount(balance);
        requirePeriods(periods);
        var principal = BigDecimal.valueOf(balance);
        var rate = monthlyRate(annualRate);
        if (rate.signum() == 0) return fixedPrincipal(balance, periods);
        var growth = BigDecimal.ONE.add(rate).pow(periods, PRECISION);
        return won(principal.multiply(rate, PRECISION).multiply(growth, PRECISION)
            .divide(growth.subtract(BigDecimal.ONE), PRECISION));
    }

    public static long fixedPrincipal(long balance, int periods) {
        requireAmount(balance);
        requirePeriods(periods);
        return won(BigDecimal.valueOf(balance).divide(BigDecimal.valueOf(periods), PRECISION));
    }

    public static long requiredSaving(long balance, long savings, int periods) {
        requireAmount(balance);
        requireAmount(savings);
        if (periods < 0) throw new IllegalArgumentException("Negative saving periods");
        long shortage = Math.max(balance - savings, 0);
        return periods == 0 ? shortage : BigDecimal.valueOf(shortage)
            .divide(BigDecimal.valueOf(periods), 0, RoundingMode.CEILING).longValueExact();
    }

    static long won(BigDecimal value) { return value.setScale(0, RoundingMode.HALF_UP).longValueExact(); }
    static void requireAmount(long amount) {
        if (amount < 0) throw new IllegalArgumentException("Negative monetary amount");
    }
    private static void requirePeriods(int periods) {
        if (periods < 1 || periods > 1200) throw new IllegalArgumentException("Invalid remaining periods");
    }
}
