package com.loanrun.risk.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public final class RiskDtos {
    private RiskDtos(){}
    public enum Level{STABLE,CAUTION,DANGER}
    public enum ScenarioType{CURRENT,RATE_UP,INCOME_DOWN,EXPENSE_UP}
    public record Check(String code,String label,BigDecimal ratioPct,Level level,int weight,Integer points){}
    public record Indicators(BigDecimal surplusRatioPercent,BigDecimal debtServiceRatioPercent,Long availableCash,
        BigDecimal variableRateExposure,Long discretionaryExpenses,Long discretionaryLimitAmount,BigDecimal discretionaryLimitUsagePct){}
    public record Thresholds(BigDecimal reduceExtraFrom,BigDecimal basicAtRiskFrom){}
    public record Event(String type,long loanId,LocalDate eventDate,int daysUntil,int dDay,boolean isImminent){}
    public record HistoryEvent(LocalDate eventDate,String type,Level level,String description){}
    public record Change(String yearMonth,String direction,String reason,Long amountDelta,LocalDate eventDate,BigDecimal changeValue,String changeUnit){}
    public record ChangeSummary(Long netMonthlyBurdenDelta,int rateChangeCount,int incomeChangeCount,int expenseChangeCount){}
    public record Detail(Level level,Indicators indicators,List<Event> upcomingEvents,List<HistoryEvent> history,String dataStatus,
        List<Check> checks,Thresholds rateThresholds,List<Change> sixMonthChanges,boolean strategyLinkAvailable,ChangeSummary sixMonthSummary,
        Thresholds incomeThresholds,Thresholds expenseThresholds,BigDecimal score,BigDecimal emergencyCoverageMonths,String emergencyCoverageStatus,Long oneTimeShortfall){}
    public record Baseline(Long income,Long monthlyPayment,Long availableCash,Level level,BigDecimal score){}
    public record Scenario(Long income,Long monthlyPayment,Long availableCash,Level level,String payoffFeasibility,BigDecimal score,
        long expenseIncreaseAmount,BigDecimal emergencyCoverageMonths,String emergencyCoverageStatus,Long oneTimeShortfall,int rateIncreasePp,int incomeChangePct){}
    public record Delta(Long income,Long monthlyPayment,Long availableCash){}
    public record Simulation(Baseline baseline,Scenario scenario,Delta delta,List<String> warnings,Thresholds rateThresholds,
        Thresholds incomeThresholds,Thresholds expenseThresholds,ScenarioType scenarioType){}
}
