package com.loanrun.payment.read;

import java.util.List;

/** Aggregates cover all matching payments, while records contain only the requested page. */
public record PaymentListSource(long count, long amount, long principal, long interest, long extra, long fee,
    long savingsUsed, List<PaymentDetailSource> records) {
    public PaymentListSource { records=List.copyOf(records); }
}
