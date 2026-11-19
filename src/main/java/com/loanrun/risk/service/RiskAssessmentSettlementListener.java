package com.loanrun.risk.service;

import com.loanrun.budget.domain.CompletedBudgetMonths;
import com.loanrun.common.contract.RevisionPort;
import com.loanrun.risk.repository.RiskHistoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.*;

@Component
@ConditionalOnProperty(name="loanrun.risk-history.enabled",havingValue="true")
public class RiskAssessmentSettlementListener {
    private static final Logger LOG=LoggerFactory.getLogger(RiskAssessmentSettlementListener.class);
    private final RiskAssessmentMaintenance maintenance;
    private final RiskHistoryRepository history;private final RevisionPort revisions;
    public RiskAssessmentSettlementListener(RiskAssessmentMaintenance maintenance,RiskHistoryRepository history,RevisionPort revisions){this.maintenance=maintenance;this.history=history;this.revisions=revisions;}
    @TransactionalEventListener(phase=TransactionPhase.BEFORE_COMMIT)
    public void requested(CompletedBudgetMonths event){
        long revision=revisions.current(event.userId());
        for(var month:event.months())history.request(event.userId(),month,revision);
    }
    @TransactionalEventListener(phase=TransactionPhase.AFTER_COMMIT)
    public void completed(CompletedBudgetMonths event){
        for(var month:event.months())try{
            if(!maintenance.refresh(event.userId(),month))LOG.warn("Completed risk assessment deferred after input revision changed");
        }catch(RuntimeException failure){
            // The financial transaction already committed. A later settlement read retries missing derived results.
            // Do not log exception messages, user identifiers or financial payloads.
            LOG.warn("Completed risk assessment deferred because confirmed source evaluation was unavailable");
        }
    }
}
