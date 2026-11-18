package com.loanrun.report.service;

import com.loanrun.common.contract.*;
import com.loanrun.common.contract.FinanceModels.DateRange;
import com.loanrun.common.error.ApiException;
import com.loanrun.report.domain.ReportInput;
import com.loanrun.risk.service.RiskSimulationService;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class ReportInputService {
    private final RiskSimulationService risks;private final ComparisonSettingsService comparison;
    private final ObjectProvider<ReportHistoryQueryPort> history;private final ObjectProvider<PlanPerformanceQueryPort> performance;
    private final ObjectProvider<PlanQueryPort> plans;private final ObjectProvider<BudgetQueryPort> budgets;
    public ReportInputService(RiskSimulationService risks,ComparisonSettingsService comparison,ObjectProvider<ReportHistoryQueryPort> history,
            ObjectProvider<PlanPerformanceQueryPort> performance,ObjectProvider<PlanQueryPort> plans,ObjectProvider<BudgetQueryPort> budgets){
        this.risks=risks;this.comparison=comparison;this.history=history;this.performance=performance;this.plans=plans;this.budgets=budgets;
    }
    /** Caller holds the user mutation lock. No engine, dispatch or external call is made while capturing. */
    @Transactional(propagation=Propagation.MANDATORY)
    public ReportInput capture(long userId,Long loanId,long revision){
        var source=risks.capture(userId);var facts=source.household();var month=facts.month();
        if(loanId!=null&&facts.loans().stream().noneMatch(loan->loan.loanId()==loanId))
            throw new ApiException(HttpStatus.NOT_FOUND,"LOAN_NOT_FOUND","대출을 찾을 수 없습니다.");
        if(facts.loans().isEmpty()||facts.finance().incomeType()==null||facts.finance().planningIncome()==null)
            throw new ApiException(HttpStatus.BAD_REQUEST,"REPORT_INPUT_INCOMPLETE","리포트에 필요한 대출 또는 재정정보가 없습니다.");
        var h=history.getIfAvailable();var p=performance.getIfAvailable();var plan=plans.getIfAvailable();var budget=budgets.getIfAvailable();
        if(h==null||p==null||plan==null||budget==null) throw ApiException.unavailable();
        Map<YearMonth,Long> incomes=new LinkedHashMap<>();
        for(int i=6;i>=1;i--){var observed=month.minusMonths(i);incomes.put(observed,budget.financeSnapshot(userId,observed).actualIncome());}
        return new ReportInput(userId,loanId,revision,month,source,h.history(userId,loanId,new DateRange(month.minusMonths(6).atDay(1),month.atDay(1).minusDays(1))),
            incomes,comparison.snapshot(userId),plan.overallState(userId),p.performance(userId,facts.asOf()));
    }
}
