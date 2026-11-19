package com.loanrun.loan.dto;

import com.loanrun.common.contract.FinanceModels.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/** Public registration view, separate from persistence and internal engine terms. */
public record LoanCreated(long loanId, Long productId, String institutionName, String productName,
    LoanType loanType, String loanForm, RepaymentMethod repaymentMethod, long originalPrincipal,
    long remainingPrincipal, BigDecimal annualRate, RateType rateType, Integer rateResetMonths,
    LocalDate nextRateResetDate, LocalDate startDate, LocalDate maturityDate, int paymentDay,
    int graceMonths, RepaymentMethod postGraceMethod, Long contractualMonthlyPayment,
    long estimatedMonthlyPayment, long paidPrincipal, BigDecimal progressPercent, LoanStatus status,
    LocalDate asOf, String repaymentCategory, String rateSource, OffsetDateTime rateUpdatedAt,
    boolean rateLinkConsent, String rateSyncStatus, PrepaymentPolicy prepaymentPolicy,
    FeeMethod prepaymentFeeMethod, BigDecimal prepaymentFeeRate, LocalDate feeStartDate,
    LocalDate feeEndDate, Boolean feeExempt, long savingsBalance, long requiredMonthlySaving,
    LocalDate snapshotDate, long snapshotPrincipal) {}
