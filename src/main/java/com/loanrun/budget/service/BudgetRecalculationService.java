package com.loanrun.budget.service;

import com.loanrun.common.contract.BudgetRecalculationPort;
import com.loanrun.common.contract.UserMutationLock;
import java.time.Clock;
import java.time.YearMonth;
import com.loanrun.budget.repository.MonthlyBudgetRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import com.loanrun.budget.domain.CompletedBudgetMonths;
import java.util.ArrayList;
import org.springframework.context.ApplicationEventPublisher;

@Service
public class BudgetRecalculationService implements BudgetRecalculationPort {
    private final BudgetReadService reads;
    private final MonthlyBudgetRepository budgets;
    private final UserMutationLock locks;
    private final Clock clock;
    private final ApplicationEventPublisher events;
    public BudgetRecalculationService(BudgetReadService reads, MonthlyBudgetRepository budgets, UserMutationLock locks, Clock clock,ApplicationEventPublisher events) {
        this.reads = reads; this.budgets = budgets; this.locks = locks; this.clock = clock;
        this.events=events;
    }
    @Override @Transactional(propagation=Propagation.MANDATORY)
    public void recalculateFrom(long userId, YearMonth earliestMonth) {
        locks.lock(userId);
        var completed=new ArrayList<YearMonth>();
        for (var e : reads.through(userId, YearMonth.now(clock))) {
            if (e.budget() == null || e.budget().id() == null) continue;
            var m = e.metrics();
            boolean changed=!e.budget().month().isBefore(earliestMonth);
            if(changed)budgets.saveSettlement(userId,e.budget().month(),m);
            var status=changed?m.settlementStatus():e.budget().settlementStatus();
            if(e.budget().month().isBefore(YearMonth.now(clock))&&"SETTLED".equals(status))completed.add(e.budget().month());
        }
        if(!completed.isEmpty())events.publishEvent(new CompletedBudgetMonths(userId,completed));
    }
}
