package com.loanrun.dashboard.service;

import com.loanrun.common.contract.*;
import com.loanrun.common.error.ApiException;
import com.loanrun.report.domain.ReportJob;
import com.loanrun.report.repository.ReportJobRepository;
import com.loanrun.risk.domain.RiskInputs;
import com.loanrun.risk.service.RiskInputService;
import java.time.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Captures one database view; engine work runs after this short read transaction. */
@Service
public class DashboardInputService {
    public record Input(long userId,Long selectedLoanId,PlanType plan,long revision,DashboardQueryPort.Snapshot ledger,RiskInputs risk,ReportJob report,ReportJob lastCompleted){}
    private final ObjectProvider<DashboardQueryPort> source;
    private final UserAccessPort access;private final RevisionPort revisions;
    private final RiskInputService risks;private final ReportJobRepository reports;private final Clock clock;
    public DashboardInputService(ObjectProvider<DashboardQueryPort> source,UserAccessPort access,RevisionPort revisions,
            RiskInputService risks,ReportJobRepository reports,Clock clock){
        this.source=source;this.access=access;this.revisions=revisions;this.risks=risks;this.reports=reports;this.clock=clock;
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Input capture(long userId,Long selected){
        if(selected!=null&&selected<=0)throw ApiException.validation("loan_id","양의 ID가 필요합니다.");
        var provider=source.getIfAvailable();if(provider==null)throw ApiException.unavailable();
        var plan=access.planType(userId);var ledger=provider.snapshot(userId,selected,LocalDate.now(clock));
        if(ledger==null)throw ApiException.unavailable();
        if(selected!=null&&ledger.loans().stream().noneMatch(loan->loan.loanId()==selected))
            throw new ApiException(HttpStatus.NOT_FOUND,"LOAN_NOT_FOUND","대출을 찾을 수 없습니다.");
        return new Input(userId,selected,plan,revisions.current(userId),ledger,
            plan==PlanType.PRO?risks.snapshot(userId,YearMonth.now(clock)):null,
            plan==PlanType.PRO?reports.latest(userId,selected).orElse(null):null,
            plan==PlanType.PRO?reports.latestCompleted(userId,selected).orElse(null):null);
    }
}
