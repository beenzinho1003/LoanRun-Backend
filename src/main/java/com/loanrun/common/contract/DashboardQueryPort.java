package com.loanrun.common.contract;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Team 1 proposed ledger/plan read projection. Confirm fields/units before merging consumers. */
public interface DashboardQueryPort {
    record LoanProgress(long loanId,String productName,long remainingPrincipal,Long paidPrincipal,BigDecimal progressPercent,
        long savingsBalance,String status,long requiredMonthlySaving){}
    record DueItem(long dueItemId,long loanId,LocalDate dueDate,long requiredAmount,long plannedExtraAmount,long paidRequiredAmount,
        long paidExtraAmount,long outstandingAmount,String status,String productName,long totalPlannedAmount,String displayStatus,
        long remainingExtraAmount,boolean unpaidConfirmed,long plannedSavingAmount,long actualSavingAmount){}
    record MonthlySummary(long scheduledRepayment,long actualRepayment,long remainingRequiredAmount,long plannedExtraAmount,long actualExtraAmount,
        int completedCount,int totalCount,long remainingAmount,long plannedSavingAmount,long actualSavingAmount){}
    record Snapshot(List<LoanProgress> loans,long totalRemainingPrincipal,BigDecimal overallProgressPercent,LocalDate estimatedPayoffDate,
        DueItem nextPayment,MonthlySummary monthlySummary,List<DueItem> monthlyChecklist){
        public Snapshot {loans=List.copyOf(loans);monthlyChecklist=List.copyOf(monthlyChecklist);}
    }
    Snapshot snapshot(long userId,Long selectedLoanId,LocalDate asOf);
}
