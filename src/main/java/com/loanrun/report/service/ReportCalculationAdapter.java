package com.loanrun.report.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loanrun.report.client.ExplanationClient;
import com.loanrun.report.domain.ReportInput;
import com.loanrun.report.dto.ReportResultDtos.Payoff;
import com.loanrun.risk.dto.RiskDtos.ScenarioType;
import com.loanrun.risk.service.RiskSimulationService;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class ReportCalculationAdapter implements ReportCalculation {
    private final RiskSimulationService risks;private final StrategyCalculator strategies;private final ExplanationClient explanations;private final ObjectMapper json;
    public ReportCalculationAdapter(RiskSimulationService risks,StrategyCalculator strategies,ExplanationClient explanations,ObjectMapper json){
        this.risks=risks;this.strategies=strategies;this.explanations=explanations;this.json=json;
    }
    @Override public Calculated calculate(ReportInput input,long reportId){
        if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Calculate reports outside financial transactions");
        var stats=ReportStatistics.calculate(input.history(),input.actualIncomes(),input.basisMonth());
        var targets=StrategyEngineInputs.baseline(input.scenarios(),input.monthlyOverrides()).monthlyAmounts();
        Map<ScenarioType,RiskSimulationService.Evaluation> evaluated=new EnumMap<>(ScenarioType.class);List<Payoff> payoffs=new ArrayList<>();
        for(var type:ScenarioType.values()){
            var value=risks.evaluate(input.scenarios(),type,targets);evaluated.put(type,value);
            payoffs.add(new Payoff(type.name(),value.response().scenario().payoffFeasibility(),input.loanId()==null?value.projectedPayoffDate():value.loanPayoffDates().get(input.loanId())));
        }
        var current=evaluated.get(ScenarioType.CURRENT).response();var increased=evaluated.get(ScenarioType.RATE_UP).response();
        var strategy=strategies.calculate(reportId,input,new StrategyCalculator.RateContext(increased.scenario().availableCash(),current.rateThresholds().basicAtRiskFrom()));
        String code=strategy.view().recommendation()==null?"NONE":strategy.view().recommendation().strategyCode();
        var explanation=explanations.explain(new ExplanationClient.Facts(current.scenario().level()==null?null:current.scenario().level().name(),code,
            stats.observedMonthCount(),stats.monthlyPrincipalRepaymentRate(),stats.averageDebtServiceRatio()));
        var result=ReportResultAssembler.assemble(reportId,input,stats,strategy,payoffs,explanation);
        return new Calculated(json.valueToTree(result),json.valueToTree(strategy.view()),strategy.stored(),explanation.source());
    }
}
