package com.loanrun.budget.service;

import com.loanrun.common.contract.BudgetRecalculationPort;
import com.loanrun.common.contract.UserMutationLock;
import java.time.Clock;
import java.time.YearMonth;
import com.loanrun.budget.repository.MonthlyBudgetRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BudgetRecalculationService implements BudgetRecalculationPort {
    private final BudgetReadService reads;
    private final MonthlyBudgetRepository budgets;
    private final UserMutationLock locks;
    private final Clock clock;
    public BudgetRecalculationService(BudgetReadService reads, MonthlyBudgetRepository budgets, UserMutationLock locks, Clock clock) {
        this.reads = reads; this.budgets = budgets; this.locks = locks; this.clock = clock;
    }
    @Override @Transactional(propagation=Propagation.MANDATORY)
    public void recalculateFrom(long userId, YearMonth earliestMonth) {
        locks.lock(userId);
        for (var e : reads.through(userId, YearMonth.now(clock))) {
            if (e.budget() == null || e.budget().id() == null || e.budget().month().isBefore(earliestMonth)) continue;
            var m = e.metrics();
            budgets.saveSettlement(userId,e.budget().month(),m);
        }
    }
}
