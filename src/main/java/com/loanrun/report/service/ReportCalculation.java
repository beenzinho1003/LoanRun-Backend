package com.loanrun.report.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.loanrun.common.contract.FinanceModels.StrategyCandidate;
import com.loanrun.report.domain.ReportInput;
import java.util.List;

/** Internal calculation boundary; implementations use only captured input and the pure engine. */
public interface ReportCalculation {
    record Calculated(JsonNode result,JsonNode strategies,List<StrategyCandidate> candidates,String explanationSource) {
        public Calculated {result=result.deepCopy();strategies=strategies==null?null:strategies.deepCopy();candidates=List.copyOf(candidates);}
    }
    Calculated calculate(ReportInput input,long reportId);
}
