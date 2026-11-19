package com.loanrun.payment.read;

import com.loanrun.loan.read.LoanSources.Payment;
import java.time.LocalDate;
import java.util.List;

/** Original payment and its owner-scoped persisted allocations; no inferred schedule. */
public record PaymentDetailSource(Payment payment, LocalDate snapshotDate, List<Allocation> allocations) {
    public PaymentDetailSource { allocations=List.copyOf(allocations); }
    public record Allocation(long dueItemId, LocalDate dueDate, long principal, long interest, long fee) {}
}
