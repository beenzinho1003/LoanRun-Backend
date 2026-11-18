package com.loanrun.report.service;

import com.loanrun.report.client.ExplanationClient.Explanation;
import com.loanrun.report.domain.ReportInput;
import com.loanrun.report.dto.ReportResultDtos.*;
import java.math.BigDecimal;
import java.util.List;

/** Maps already-calculated facts. DEMO peers never replace missing personal observations. */
public final class ReportResultAssembler {
    private ReportResultAssembler(){}
    public static Result assemble(long reportId,ReportInput input,ReportStatistics.Stats stats,StrategyCalculator.Computed strategy,List<Payoff> payoffs,Explanation explanation){
        var pace=new Pace(stats.monthlyPrincipalRepaymentRate(),stats.principalRepaid(),stats.dataStatus(),stats.principalReductionPct(),stats.observedMonthCount(),stats.savingsDeposited(),stats.savingsUsed());
        Comparison comparison=null;
        if(input.comparison().consented())comparison=new Comparison(stats.averageDebtServiceRatio()==null?"INSUFFICIENT":"DEMO","DEMO",input.comparison().ageGroup(),input.comparison().incomeDecile(),
            stats.extraCount(),3,"또래 수치는 가상 예시이며 실제 통계가 아닙니다.","DEBT_SERVICE_RATIO",stats.averageDebtServiceRatio(),BigDecimal.valueOf(25));
        // The short completion transaction stamps authoritative id/status/times/stale immediately before publishing.
        return new Result(reportId,"COMPLETED",null,false,explanation.summary(),pace,comparison,payoffs,
            List.of(new Suggestion("PLAN_REVIEW","상환 계획 확인",explanation.actionNote(),"REPAYMENT_PLAN")),null,null,strategy.view().strategyAvailable(),strategy.view().strategyType());
    }
}
