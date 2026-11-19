package com.loanrun.payment.dto;

import java.util.List;

public record PaymentList(long count, int page, int pageSize, Integer nextPage, Integer previousPage,
    List<PaymentDetail> results, Totals totals) {
    public PaymentList { results=List.copyOf(results); }
    public record Totals(long amount, long principalAmount, long interestAmount, long extraAmount,
        long feeAmount, long savingsUsedAmount) {}
}
