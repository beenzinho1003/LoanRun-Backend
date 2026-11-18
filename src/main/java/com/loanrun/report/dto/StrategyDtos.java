package com.loanrun.report.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.loanrun.common.contract.FinanceModels.AllocationType;
import java.math.BigDecimal;
import java.time.*;
import java.util.List;

public final class StrategyDtos {
    private StrategyDtos(){}
    public record Allocation(long loanId,long amount,Integer priority,AllocationType allocationType,long actualAmount){}
    public record Candidate(String strategyCode,String label,List<String> advantages,List<String> disadvantages,LocalDate projectedPayoffDate,
        Long estimatedTotalInterest,Long interestSaving,long monthlyBurden,List<Allocation> allocations,String reason,Long estimatedTotalFee,Long netSaving,String feeComparisonBasis){
        public Candidate {advantages=List.copyOf(advantages);disadvantages=List.copyOf(disadvantages);allocations=List.copyOf(allocations);}
    }
    public record Current(String strategyCode,OffsetDateTime appliedAt,@JsonProperty("adherence_rate_3m")BigDecimal adherenceRate3m,String judgement){}
    public record Situation(long monthlyExtraBudget,LocalDate projectedPayoffDate,Long estimatedTotalInterest,BigDecimal discretionaryLimitUsagePct,
        BigDecimal surplusRatioPct,Integer consecutiveExtraOnTrackMonths,@JsonProperty("missed_payment_count_6m")Integer missedPaymentCount6m,
        BigDecimal variableRateExposurePct,BigDecimal basicAtRiskFrom,Long estimatedTotalFee,long requiredMonthlySaving,Long recurringCapacity){}
    public record Recommendation(String strategyCode,String type,String reason){}
    public record View(boolean strategyAvailable,String unavailableReason,Long reportId,String strategyVersion,Current currentStrategy,Situation situation,
        Recommendation recommendation,List<Candidate> candidates,boolean stale,String strategyType,String dataRevision,String feeComparisonBasis){
        public View {candidates=List.copyOf(candidates);}
    }
}
