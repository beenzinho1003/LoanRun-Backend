package com.loanrun.common.contract;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Team 1 compares saved source plan versions with actual observations. Incomplete history stays null. */
public interface PlanPerformanceQueryPort {
    record Performance(BigDecimal adherenceRate3m,Integer consecutiveExtraOnTrackMonths,Integer missedPaymentCount6m) {}
    Performance performance(long userId,LocalDate asOf);
}
