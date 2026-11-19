package com.loanrun.payment.dto;

import com.loanrun.loan.read.LoanSources.PaymentKind;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/** API #28 view, separate from persisted source records. */
public record PaymentDetail(long paymentId, long loanId, LocalDate paidAt, long amount, PaymentKind paymentType,
    Long dueItemId, long principalAmount, long interestAmount, long extraAmount, OffsetDateTime createdAt,
    long feeAmount, long savingsUsedAmount, boolean editable, Long delayDays, List<DueAllocation> dueAllocations) {
    public PaymentDetail { dueAllocations=List.copyOf(dueAllocations); }
    public record DueAllocation(long dueItemId, long requiredPaidAmount, long delayDays) {}
}
