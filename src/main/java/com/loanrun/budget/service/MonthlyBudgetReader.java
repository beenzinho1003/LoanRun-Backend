package com.loanrun.budget.service;

import com.loanrun.budget.dto.BudgetDtos.*;
import com.loanrun.budget.repository.MonthlyBudgetRepository;
import com.loanrun.common.contract.*;
import com.loanrun.common.contract.FinanceModels.*;
import com.loanrun.common.error.ApiException;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MonthlyBudgetReader {
    private final BudgetReadService reads;
    private final MonthlyBudgetRepository budgets;
    private final FinanceProfileService profiles;
    private final BudgetRecalculationPort recalculation;
    private final UserMutationLock locks;
    private final ObjectProvider<PlanQueryPort> plans;
    private final Clock clock;
    public MonthlyBudgetReader(BudgetReadService reads,MonthlyBudgetRepository budgets,FinanceProfileService profiles,
            BudgetRecalculationPort recalculation,UserMutationLock locks,ObjectProvider<PlanQueryPort> plans,Clock clock){
        this.reads=reads;this.budgets=budgets;this.profiles=profiles;this.recalculation=recalculation;this.locks=locks;this.plans=plans;this.clock=clock;
    }
    @Transactional public Monthly get(long userId,String monthText){
        YearMonth month=BudgetInput.month(monthText);
        locks.lock(userId);profiles.ensureCurrentSnapshots(userId);
        var snapshots=budgets.through(userId,YearMonth.now(clock));
        recalculation.recalculateFrom(userId,snapshots.isEmpty()?month:snapshots.getFirst().month());
        return view(userId,month);
    }
    @Transactional(readOnly=true) public Monthly view(long userId,YearMonth month){
        var e=reads.evaluate(userId,month);var b=e.budget();var allocation=allocation(userId,e,month);
        Long essential=b==null?null:Math.addExact(b.fixedEssentialExpenses(),b.variableEssentialExpenses());
        return new Monthly(month.toString(),e.monthlyIncome(),b==null?null:b.incomeType(),b==null?null:b.payday(),essential,
            b==null?null:b.discretionaryExpenses(),b==null?null:b.savings(),b==null?null:b.emergencyFund(),e.due().scheduledCash(),
            Math.addExact(e.payments().cashPaid(),e.payments().savingsUsed()),allocation,
            b==null?"INSUFFICIENT":b.expensesConfirmed()?"ENTERED":"DEFAULT",
            b==null||b.updatedAt()==null?null:b.updatedAt().atZone(clock.getZone()).toOffsetDateTime(),
            b==null?null:b.fixedEssentialExpenses(),b==null?null:b.variableEssentialExpenses(),b==null?"DEFAULT":b.source(),
            b==null?null:b.incomeType()==IncomeType.REGULAR?b.expectedIncome():0L,e.entries(),
            e.metrics().allocationCapacity()==null||allocation.totalAllocated()>e.metrics().allocationCapacity(),e.metrics());
    }
    private AllocationSummary allocation(long userId,BudgetReadService.Evaluation e,YearMonth month){
        List<PlannedAmount> amounts;
        if(e.budget()!=null&&e.budget().allocationOverridden()) amounts=budgets.overrides(e.budget());
        else {var port=plans.getIfAvailable();if(port==null)throw ApiException.unavailable();amounts=port.plannedAllocations(userId,month);}
        List<Allocation> result=amounts.stream().map(p->new Allocation(p.loanId(),p.cashAmount(),p.allocationType(),
            p.allocationType()==AllocationType.REPAYMENT_SAVING?e.savings().additionsByLoan().getOrDefault(p.loanId(),0L):e.payments().extraCashByLoan().getOrDefault(p.loanId(),0L))).toList();
        long reserve=e.budget()==null?0:e.budget().reserveAmount();
        Set<Long> targets=new HashSet<>();result.forEach(a->targets.add(a.loanId()));long allocated=reserve;
        for(var a:result)allocated=Math.addExact(allocated,Math.max(a.amount(),a.actualAmount()));
        for(var a:e.payments().extraCashByLoan().entrySet())if(!targets.contains(a.getKey()))allocated=Math.addExact(allocated,a.getValue());
        for(var a:e.savings().additionsByLoan().entrySet())if(!targets.contains(a.getKey()))allocated=Math.addExact(allocated,a.getValue());
        Long capacity=e.metrics().allocationCapacity();
        return new AllocationSummary(result,reserve,allocated,capacity==null?null:Math.max(capacity-allocated,0));
    }
}
