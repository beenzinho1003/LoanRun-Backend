package com.loanrun.common.contract;

import com.loanrun.common.contract.FinanceModels.*;
import java.util.List;

/** Team 1 supplies owned actual history. A null loanId means the household, including paid/deleted history. */
public interface ReportHistoryQueryPort {
    List<HistoryMonth> history(long userId,Long loanId,DateRange range);
}
