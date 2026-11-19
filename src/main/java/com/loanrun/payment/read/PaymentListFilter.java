package com.loanrun.payment.read;

import com.loanrun.loan.read.LoanSources.PaymentKind;
import java.time.LocalDate;

/** Validated list request; page is one-based and offset arithmetic uses long. */
public record PaymentListFilter(Long loanId, LocalDate from, LocalDate to, PaymentKind type, int page, int pageSize) {
    public PaymentListFilter {
        if((loanId!=null&&loanId<=0) || page<1 || pageSize<1 || pageSize>100 || (from!=null&&to!=null&&from.isAfter(to)))
            throw new IllegalArgumentException("Invalid payment filter");
    }
    public long offset() { return ((long)page-1)*pageSize; }
}
