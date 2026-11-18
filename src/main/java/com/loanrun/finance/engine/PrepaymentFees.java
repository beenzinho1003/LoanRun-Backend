package com.loanrun.finance.engine;

import com.loanrun.common.contract.FinanceModels.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

final class PrepaymentFees {
    private PrepaymentFees() {}
    record Extra(long principal, Long fee, long cash) {}

    /** null means the contract does not establish the fee, including unsupported loan types. */
    static BigDecimal rate(LoanTerms loan, LocalDate date) {
        if (loan.feeMethod() == FeeMethod.NONE) return BigDecimal.ZERO;
        if (loan.feeMethod() == FeeMethod.UNKNOWN || loan.feeMethod() == null
                || loan.loanType() != LoanType.MORTGAGE && loan.loanType() != LoanType.JEONSE)
            return loan.feeEndDate() != null && date.isAfter(loan.feeEndDate()) ? BigDecimal.ZERO : null;
        if (loan.feeRate() == null || loan.feeRate().signum() < 0)
            throw new IllegalArgumentException("Confirmed fees require a nonnegative rate");
        if (loan.feeStartDate() != null && loan.feeEndDate() != null && loan.feeEndDate().isBefore(loan.feeStartDate()))
            throw new IllegalArgumentException("Reversed fee period");
        if (loan.feeMethod() == FeeMethod.LINEAR && (loan.feeStartDate() == null || loan.feeEndDate() == null
                || !loan.feeEndDate().isAfter(loan.feeStartDate())))
            throw new IllegalArgumentException("Linear fees require a positive dated period");
        if (loan.feeEndDate() != null && date.isAfter(loan.feeEndDate())) return BigDecimal.ZERO;
        if (loan.feeStartDate() != null && date.isBefore(loan.feeStartDate())) return BigDecimal.ZERO;
        var fraction = loan.feeRate().divide(BigDecimal.valueOf(100), RepaymentMath.PRECISION);
        if (loan.feeMethod() == FeeMethod.FIXED) return fraction;
        long total = ChronoUnit.DAYS.between(loan.feeStartDate(), loan.feeEndDate());
        long left = Math.clamp(ChronoUnit.DAYS.between(date, loan.feeEndDate()), 0, total);
        return fraction.multiply(BigDecimal.valueOf(left)).divide(BigDecimal.valueOf(total), RepaymentMath.PRECISION);
    }

    static Long fee(LoanTerms loan, LocalDate date, long principal) {
        var rate = rate(loan, date);
        return rate == null ? null : RepaymentMath.won(BigDecimal.valueOf(principal).multiply(rate));
    }

    static Extra withinCash(LoanTerms loan, LocalDate date, long balance, long cash) {
        var rate = rate(loan, date);
        if (rate == null) {
            // Gross-interest projection only: the fee and total cash remain unknown in the result.
            long principal = Math.min(balance, cash);
            return new Extra(principal, null, principal);
        }
        long low = 0, high = Math.min(balance, cash);
        var budget = BigDecimal.valueOf(cash);
        while (low < high) {
            long middle = low + (high - low) / 2 + (high - low) % 2;
            var principal = BigDecimal.valueOf(middle);
            var fee = principal.multiply(rate).setScale(0, java.math.RoundingMode.HALF_UP);
            if (principal.add(fee).compareTo(budget) <= 0) low = middle;
            else high = middle - 1;
        }
        long fee = RepaymentMath.won(BigDecimal.valueOf(low).multiply(rate));
        return new Extra(low, fee, Math.addExact(low, fee));
    }
}
