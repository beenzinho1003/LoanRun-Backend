package com.loanrun.report.service;

import com.loanrun.common.contract.FinanceModels.*;
import com.loanrun.risk.domain.*;
import java.math.BigDecimal;
import java.util.*;

/** Candidate-only scope. Keep the original captured household input for risk assessment and report history. */
public final class StrategyReportScope {
    private StrategyReportScope() {}
    public record Scoped(RiskScenarioInput candidateInput,List<PlannedAmount> overrides,long reservedRecurringCash,long reservedCurrentCash) {
        public Scoped {overrides=List.copyOf(overrides);}
    }
    public static Optional<Scoped> select(RiskScenarioInput source,Long loanId,List<PlannedAmount> overrides){
        if(loanId==null)return Optional.of(new Scoped(source,overrides,0,0));
        var facts=source.household();var selected=facts.loans().stream().filter(l->l.loanId()==loanId).toList();
        if(selected.size()!=1)throw new IllegalArgumentException("Selected loan is absent or duplicated in captured facts");
        Map<Long,PlanSnapshot> plans=new HashMap<>();
        for(var plan:facts.plans())if(plan.configured()&&plans.put(plan.loanId(),plan)!=null)throw new IllegalArgumentException("Duplicate configured plan");
        Map<Long,PlannedAmount> current=new HashMap<>();
        for(var override:overrides){
            var loan=facts.loans().stream().filter(l->l.loanId()==override.loanId()).findFirst();if(loan.isEmpty())continue;
            var type=loan.get().method()==RepaymentMethod.BULLET?AllocationType.REPAYMENT_SAVING:AllocationType.EXTRA_PAYMENT;
            if(override.allocationType()!=type||override.cashAmount()<0||current.put(override.loanId(),override)!=null)throw new IllegalArgumentException("Invalid captured month override");
        }
        long recurringReserved=0,otherRemaining=0;
        for(var other:facts.loans())if(other.loanId()!=loanId&&other.status()==LoanStatus.ACTIVE&&other.outstanding()>0){
            boolean saving=other.method()==RepaymentMethod.BULLET;var plan=plans.get(other.loanId());
            if(saving&&plan==null)return Optional.empty(); // Unknown mandatory saving must never become zero.
            long required=saving?plan.requiredMonthlySaving():0;
            long future=plan==null?0:saving?Math.max(plan.monthlySaving(),required):plan.monthlyExtraCash();
            if(required<0||future<0)throw new IllegalArgumentException("Negative captured goal");
            recurringReserved=Math.addExact(recurringReserved,future);
            long currentGoal=future;
            if(plan!=null&&plan.effectiveDate()!=null&&facts.month().atEndOfMonth().isBefore(plan.effectiveDate()))currentGoal=required;
            if(other.method()==RepaymentMethod.GRACE_INSTALLMENT&&(other.graceEnd()==null||facts.month().atEndOfMonth().isBefore(other.graceEnd())))currentGoal=0;
            if(current.containsKey(other.loanId()))currentGoal=Math.max(current.get(other.loanId()).cashAmount(),required);
            long actual=(saving?source.savings().additionsByLoan():source.payments().extraCashByLoan()).getOrDefault(other.loanId(),0L);
            otherRemaining=Math.addExact(otherRemaining,Math.max(Math.subtractExact(currentGoal,actual),0));
        }
        var cap=facts.capacity();long selectedExtra=source.payments().extraCashByLoan().getOrDefault(loanId,0L),selectedSaving=source.savings().additionsByLoan().getOrDefault(loanId,0L);
        long globalUsed=Math.max(Math.addExact(cap.usedExtraCash(),cap.usedSaving()),Math.addExact(sum(source.payments().extraCashByLoan()),sum(source.savings().additionsByLoan())));
        long otherUsed=Math.subtractExact(globalUsed,Math.addExact(selectedExtra,selectedSaving));
        long currentReserved=Math.addExact(otherUsed,otherRemaining);
        Long available=cap.availableCash()==null?null:Math.subtractExact(cap.availableCash(),recurringReserved);
        var capacity=new BudgetCapacity(cap.month(),available,deduct(cap.recurringCap(),recurringReserved),cap.carryIn(),deduct(cap.allocationCapacity(),currentReserved),
            selectedExtra,selectedSaving,deduct(cap.remainingAllocatable(),otherRemaining),cap.reserveAmount(),available==null?null:available<0?Math.negateExact(available):0L,
            cap.incomeStatus(),cap.settlementStatus(),cap.warnings());
        var loan=selected.getFirst();var plan=plans.get(loanId);boolean saving=loan.method()==RepaymentMethod.BULLET;
        Long target=plan==null?null:saving?Math.max(plan.monthlySaving(),plan.requiredMonthlySaving()):plan.monthlyExtraCash();
        Long required=saving?(plan==null?null:Long.valueOf(plan.requiredMonthlySaving())):Long.valueOf(0);
        var scopedFacts=new RiskInputs(facts.userId(),facts.asOf(),facts.month(),selected,facts.plans().stream().filter(p->p.loanId()==loanId).toList(),facts.finance(),capacity,
            facts.due(),target,required,loan.outstanding()==0?null:loan.rateType()==RateType.VARIABLE?BigDecimal.valueOf(100):BigDecimal.ZERO);
        return Optional.of(new Scoped(new RiskScenarioInput(scopedFacts,source.payments(),source.savings()),overrides.stream().filter(a->a.loanId()==loanId).toList(),recurringReserved,currentReserved));
    }
    private static Long deduct(Long value,long cash){return value==null?null:Math.max(Math.subtractExact(value,cash),0);}
    private static long sum(Map<Long,Long> amounts){return amounts.values().stream().reduce(0L,Math::addExact);}
}
