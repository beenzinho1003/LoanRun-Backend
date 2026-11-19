package com.loanrun.risk.domain;

import com.loanrun.risk.service.RiskDetailHistorySource.Recorded;
import java.util.List;

/** Captured confirmed month-end sources and the owner revision; carries no live persistence objects. */
public record CompletedRiskInput(long revision,RiskScenarioInput scenario,List<Long> ownedLoanIds,Recorded history){
    public CompletedRiskInput {ownedLoanIds=ownedLoanIds.stream().distinct().sorted().toList();}
}
