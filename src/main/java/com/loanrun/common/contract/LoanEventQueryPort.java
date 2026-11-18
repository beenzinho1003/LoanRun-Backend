package com.loanrun.common.contract;

import com.loanrun.common.contract.FinanceModels.DateRange;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Team 1 supplies preserved source events for this owner; unknown cash impact stays null. */
public interface LoanEventQueryPort {
    record Event(long loanId,LocalDate eventDate,BigDecimal rateChangePp,Long monthlyBurdenDelta) {}
    List<Event> rateChanges(long userId,DateRange range);
}
