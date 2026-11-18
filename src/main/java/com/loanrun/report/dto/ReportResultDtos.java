package com.loanrun.report.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.loanrun.report.dto.ComparisonSettings.AgeGroup;
import com.loanrun.report.dto.ReportReadDtos.Failure;
import java.math.BigDecimal;
import java.time.*;
import java.util.List;

public final class ReportResultDtos {
    private ReportResultDtos(){}
    public record Pace(BigDecimal monthlyPrincipalRepaymentRate,@JsonProperty("principal_repaid_last_6_months")Long principalRepaidLast6Months,
        String dataStatus,@JsonProperty("principal_reduction_pct_6m")BigDecimal principalReductionPct6m,int observedMonthCount,
        @JsonProperty("savings_deposited_6m")Long savingsDeposited6m,@JsonProperty("savings_used_6m")Long savingsUsed6m){}
    public record Comparison(String status,String sourceType,AgeGroup ageGroup,Integer incomeDecile,
        @JsonProperty("extra_payment_count_6m")Integer extraPaymentCount6m,@JsonProperty("peer_extra_payment_count_6m")int peerExtraPaymentCount6m,
        String disclaimer,String metricType,BigDecimal monthlyDebtServiceRatioPct,BigDecimal peerMonthlyDebtServiceRatioPct){}
    public record Payoff(String type,String level,LocalDate estimatedPayoffDate){}
    public record Suggestion(String type,String title,String description,String action){}
    public record Result(long reportId,String status,OffsetDateTime generatedAt,boolean stale,String summary,Pace paceAnalysis,
        Comparison comparison,List<Payoff> payoffScenarios,List<Suggestion> suggestions,Failure error,OffsetDateTime updatedAt,
        boolean strategyAvailable,String strategyType){
        public Result {payoffScenarios=List.copyOf(payoffScenarios);suggestions=List.copyOf(suggestions);}
    }
}
