package com.loanrun.loan.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** API #18 view; no previous rate is known before the first stored history row. */
public record LoanRateHistory(long loanId, List<Entry> rates) {
    public LoanRateHistory { rates=List.copyOf(rates); }
    public record Entry(long rateId, long loanId, LocalDate effectiveDate,
        BigDecimal previousRate, BigDecimal newRate, String source) {}
}
