package com.loanrun.loan.read;

import com.loanrun.common.contract.FinanceModels.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.List;

/** Immutable persisted facts, not API DTOs or calculated as-of balances/statuses. */
public final class LoanSources {
    private LoanSources() {}
    public enum PaymentKind { REGULAR, EXTRA }
    public record Loan(long loanId,long userId,Long productId,String institutionName,String productName,
        LoanType loanType,String loanForm,RepaymentMethod method,long originalPrincipal,long storedRemainingPrincipal,
        BigDecimal annualRate,RateType rateType,Integer rateResetMonths,LocalDate nextRateResetDate,
        LocalDate startDate,LocalDate maturityDate,int paymentDay,LocalDate storedNextDueDate,
        Long contractualNextAmount,Long baseAnchor,Long fixedPrincipal,int graceMonths,LocalDate graceEnd,
        RepaymentMethod postGraceMethod,PrepaymentPolicy prepaymentPolicy,FeeMethod originalFeeMethod,
        BigDecimal feeRate,LocalDate feeStart,LocalDate feeEnd,Boolean feeExempt,long initialSavings,
        LoanStatus storedStatus,LocalDate latestSnapshotDate,long latestSnapshotPrincipal,PlanSnapshot initialPlan,
        Instant createdAt,Instant updatedAt) {}
    public record Balance(long id,long userId,long loanId,LocalDate effectiveDate,long amount,String reason,Instant createdAt) {}
    public record Rate(long id,long userId,long loanId,BigDecimal annualRate,LocalDate effectiveDate,Instant createdAt) {}
    public record Payment(long id,long userId,long loanId,LocalDate paidAt,PaymentKind kind,long amount,
        long regularPrincipal,long extraPrincipal,long interest,long fee,long savingsUsed,String memo,Instant createdAt) {}
    public record Saving(long id,long userId,long loanId,LocalDate savedAt,long amount,String memo,Instant createdAt) {}
    public record Due(long id,long userId,long loanId,LocalDate dueDate,long estimatedPrincipal,long estimatedInterest,
        long fee,Long contractualAmount,long requiredAmount,boolean confirmedUnpaid,long termsRevision,Instant createdAt) {}
    public record Allocation(long id,long userId,long loanId,long paymentId,long dueItemId,long principal,long interest,long fee) {}
    public record Snapshot(LocalDate actualsThrough,List<Loan> loans,List<Balance> balances,List<Rate> rates,
        List<Payment> payments,List<Saving> savings,List<Due> dues,List<Allocation> allocations) {
        public Snapshot {
            loans=List.copyOf(loans);balances=List.copyOf(balances);rates=List.copyOf(rates);
            payments=List.copyOf(payments);savings=List.copyOf(savings);dues=List.copyOf(dues);allocations=List.copyOf(allocations);
        }
    }
}
