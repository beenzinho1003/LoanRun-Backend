package com.loanrun.report.domain;

import com.loanrun.common.contract.FinanceModels.*;
import com.loanrun.common.contract.PlanPerformanceQueryPort.Performance;
import com.loanrun.report.dto.ComparisonSettings;
import com.loanrun.risk.domain.RiskScenarioInput;
import java.time.YearMonth;
import java.util.*;

/** Server-captured facts only; nullable incomes are retained through JSON storage. */
public record ReportInput(long userId,Long loanId,long revision,YearMonth basisMonth,RiskScenarioInput scenarios,
    List<HistoryMonth> history,Map<YearMonth,Long> actualIncomes,ComparisonSettings comparison,OverallState overall,Performance performance) {
    public ReportInput {history=List.copyOf(history);actualIncomes=Collections.unmodifiableMap(new LinkedHashMap<>(actualIncomes));}
}
