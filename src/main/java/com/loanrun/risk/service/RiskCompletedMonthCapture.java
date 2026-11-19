package com.loanrun.risk.service;

import com.loanrun.common.contract.*;
import com.loanrun.common.contract.FinanceModels.DateRange;
import com.loanrun.common.error.ApiException;
import com.loanrun.risk.domain.*;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
@ConditionalOnProperty(name="loanrun.risk-history.enabled",havingValue="true")
public class RiskCompletedMonthCapture {
    private final RiskInputService inputs;private final ObjectProvider<LoanQueryPort> loans;private final RiskDetailHistorySource history;private final RevisionPort revisions;private final Clock clock;
    public RiskCompletedMonthCapture(RiskInputService inputs,ObjectProvider<LoanQueryPort> loans,RiskDetailHistorySource history,RevisionPort revisions,Clock clock){
        this.inputs=inputs;this.loans=loans;this.history=history;this.revisions=revisions;this.clock=clock;
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW,readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public CompletedRiskInput capture(long userId,YearMonth month){
        if(!month.isBefore(YearMonth.now(clock)))throw ApiException.unavailable();
        var port=loans.getIfAvailable();if(port==null)throw ApiException.unavailable();
        var facts=inputs.snapshot(userId,month);
        if(facts.userId()!=userId)throw ApiException.unavailable();
        var owned=port.historicalLoans(userId,new DateRange(month.atDay(1),month.atEndOfMonth()));
        if(owned==null||owned.stream().anyMatch(loan->loan.userId()!=userId))throw ApiException.unavailable();
        var ids=new HashSet<Long>();owned.forEach(loan->ids.add(loan.loanId()));facts.loans().forEach(loan->ids.add(loan.loanId()));
        if(ids.stream().anyMatch(id->id<=0))throw ApiException.unavailable();
        var captured=new CompletedRiskInput(revisions.current(userId),new RiskScenarioInput(facts,port.paymentSummary(userId,month),port.savingSummary(userId,month)),
            List.copyOf(ids),history.changes(userId,month));
        RiskAssessmentStore.requireConfirmed(captured,clock);return captured;
    }
}
