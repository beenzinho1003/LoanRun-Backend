package com.loanrun.risk.service;

import com.loanrun.risk.dto.RiskDtos.*;
import java.math.BigDecimal;
import java.time.*;
import org.springframework.stereotype.Service;

@Service
public class RiskDetailService {
    private final RiskDetailInputService inputs;private final RiskSimulationService simulations;private final RiskRateStress rates;
    public RiskDetailService(RiskDetailInputService inputs,RiskSimulationService simulations,RiskRateStress rates){this.inputs=inputs;this.simulations=simulations;this.rates=rates;}
    public Detail read(long userId,Long selected,YearMonth month){
        var captured=inputs.capture(userId,selected,month);if(captured.frozen()!=null)return captured.frozen();
        var facts=captured.current().household();var finance=facts.finance();
        var simulation=simulations.evaluate(captured.current(),ScenarioType.CURRENT).response();
        long basic=Math.subtractExact(Math.addExact(facts.due().scheduledCash(),facts.due().previousOutstandingCash()),facts.due().savingsUsedForDue());
        var probe=rates.probe(facts);
        var assessment=RiskMath.assess(finance.planningIncome(),facts.capacity().availableCash(),basic,
            finance.actualDiscretionaryExpenses(),finance.discretionaryLimit(),facts.variableExposurePct(),probe.hasVariableLoans(),
            probe.available(BigDecimal.ONE),facts.monthlyTarget(),!facts.loans().isEmpty());
        var indicators=new Indicators(RiskMath.display(RiskMath.ratio(facts.capacity().availableCash(),finance.planningIncome())),
            RiskMath.display(RiskMath.ratio(basic,finance.planningIncome())),facts.capacity().availableCash(),RiskMath.display(facts.variableExposurePct()),
            finance.actualDiscretionaryExpenses(),finance.discretionaryLimit(),RiskMath.display(RiskMath.ratio(finance.actualDiscretionaryExpenses(),finance.discretionaryLimit())));
        var upcoming=RiskDetailPresentation.events(facts.loans(),selected,facts.asOf());
        var history=RiskDetailPresentation.history(captured.history(),selected,month);
        var current=simulation.scenario();
        return new Detail(assessment.level(),indicators,upcoming,history.events(),assessment.dataStatus(),assessment.checks(),simulation.rateThresholds(),
            history.changes(),true,history.summary(),simulation.incomeThresholds(),simulation.expenseThresholds(),assessment.score(),current.emergencyCoverageMonths(),
            current.emergencyCoverageStatus(),current.oneTimeShortfall());
    }
}
