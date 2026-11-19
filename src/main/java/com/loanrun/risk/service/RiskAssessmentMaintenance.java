package com.loanrun.risk.service;

import com.loanrun.risk.dto.RiskDtos.Detail;
import java.time.*;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
@ConditionalOnProperty(name="loanrun.risk-history.enabled",havingValue="true")
public class RiskAssessmentMaintenance {
    private final RiskCompletedMonthCapture inputs;private final RiskDetailService calculator;private final RiskAssessmentStore store;
    public RiskAssessmentMaintenance(RiskCompletedMonthCapture inputs,RiskDetailService calculator,RiskAssessmentStore store){this.inputs=inputs;this.calculator=calculator;this.store=store;}
    @Transactional(propagation=Propagation.NOT_SUPPORTED)
    public boolean refresh(long userId,YearMonth month){
        var captured=inputs.capture(userId,month);var scenario=captured.scenario();var facts=scenario.household();
        var household=calculator.calculate(scenario,captured.history(),null);
        Map<Long,Detail> selected=new TreeMap<>();
        for(long loanId:captured.ownedLoanIds()){
            var events=RiskDetailPresentation.events(facts.loans(),loanId,facts.asOf());var history=RiskDetailPresentation.history(captured.history(),loanId,month);
            selected.put(loanId,new Detail(household.level(),household.indicators(),events,history.events(),household.dataStatus(),household.checks(),household.rateThresholds(),
                history.changes(),household.strategyLinkAvailable(),history.summary(),household.incomeThresholds(),household.expenseThresholds(),household.score(),
                household.emergencyCoverageMonths(),household.emergencyCoverageStatus(),household.oneTimeShortfall()));
        }
        return store.save(captured,household,selected);
    }
}
