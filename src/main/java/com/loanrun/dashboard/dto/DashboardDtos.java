package com.loanrun.dashboard.dto;

import com.loanrun.common.contract.PlanType;
import com.loanrun.dashboard.service.DashboardReportStatusPolicy.Status;
import com.loanrun.risk.dto.RiskDtos.Level;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public final class DashboardDtos {
    private DashboardDtos(){}
    public record Loan(long loanId,String productName,long remainingPrincipal,Long paidPrincipal,BigDecimal progressPercent,
        long savingsBalance,String status,long requiredMonthlySaving){}
    public record Payment(long dueItemId,long loanId,LocalDate dueDate,long requiredAmount,long plannedExtraAmount,long paidRequiredAmount,
        long paidExtraAmount,long outstandingAmount,String status,String productName,long totalPlannedAmount,String displayStatus,
        long remainingExtraAmount,boolean unpaidConfirmed,long plannedSavingAmount,long actualSavingAmount){}
    public record Monthly(long scheduledRepayment,long actualRepayment,long remainingRequiredAmount,long plannedExtraAmount,long actualExtraAmount,
        int completedCount,int totalCount,long remainingAmount,long plannedSavingAmount,long actualSavingAmount){}
    public record Risk(Level level,Long availableCash,boolean locked,BigDecimal score){}
    public record Detail(PlanType planType,Long selectedLoanId,List<Loan> loans,long totalRemainingPrincipal,BigDecimal overallProgressPercent,
        LocalDate estimatedPayoffDate,Payment nextPayment,Monthly monthlySummary,Risk riskSummary,Status reportStatus,List<Payment> monthlyChecklist){}
}
