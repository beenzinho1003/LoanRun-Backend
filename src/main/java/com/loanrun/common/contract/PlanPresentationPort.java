package com.loanrun.common.contract;

import com.loanrun.common.contract.FinanceModels.PlannedAmount;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

/** Team 1 read projection for the complete plan view. Caller supplies overrides to avoid a budget/plan read cycle. */
public interface PlanPresentationPort {
    List<PlanView> monthViews(long userId, LocalDate asOf, YearMonth month, List<PlannedAmount> overrides);

    record Track(String status, String dataStatus, String regularDueStatus) {}
    record PlanView(long loanId, String mode, String preference, LocalDate targetDate, Long extraMonthlyPayment,
        Long monthlyAmount, LocalDate estimatedPayoffDate, Long estimatedTotalInterest, LocalDate effectiveDate,
        LocalDate targetPayoffDate, LocalDate projectedPayoffDate, String repaymentCategory, BigDecimal plannedProgressPct,
        BigDecimal actualProgressPct, Long plannedExtraAmount, Long actualExtraAmount, Long extraGapAmount,
        Integer payoffDelayMonths, Track track, Long cumulativePlannedExtraAmount, Long cumulativeActualExtraAmount,
        Long cumulativeExtraGapAmount, Integer priority, Integer dDay, Long estimatedTotalFee, Long savingsBalance,
        Long requiredMonthlySaving, Long plannedSavingAmount, Long actualSavingAmount, Long cumulativePlannedSavingAmount,
        Long cumulativeActualSavingAmount, Long savingGapAmount, BigDecimal savingProgressPct, String projectionSource,
        int observedMonthCount, Long planStartPrincipal, String regularDueStatus, BigDecimal extraAchievementPct,
        Long affordabilityGapAmount, List<String> warnings) {
        public PlanView { warnings = List.copyOf(warnings); }
    }
}
