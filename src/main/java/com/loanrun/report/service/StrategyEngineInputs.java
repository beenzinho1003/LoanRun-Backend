package com.loanrun.report.service;

import com.loanrun.common.contract.FinanceModels.*;
import com.loanrun.risk.domain.RiskScenarioInput;
import com.loanrun.report.service.StrategyInputPolicy.CashBudget;
import com.loanrun.report.service.StrategyRecommendationPolicy.Code;
import java.math.*;
import java.time.YearMonth;
import java.util.*;

/** Produces fee-inclusive cash conditions only. All loan arithmetic and dynamic allocation belong to Team 1. */
public final class StrategyEngineInputs {
    private StrategyEngineInputs() {}
    public record Prepared(List<PlannedAmount> goals,SimulationInput simulation,CashBudget budget,boolean currentFundingAvailable) {
        public Prepared {goals=List.copyOf(goals);}
    }
    public static SimulationInput baseline(RiskScenarioInput source){
        return baseline(source,List.of());
    }
    public static SimulationInput baseline(RiskScenarioInput source,List<PlannedAmount> overrides){
        var facts=source.household();Map<YearMonth,List<PlannedAmount>> months=new LinkedHashMap<>();
        for(int i=0;i<1200;i++){
            var month=facts.month().plusMonths(i);List<PlannedAmount> amounts=new ArrayList<>();
            for(var loan:facts.loans()){
                var plan=plan(source,loan.loanId());if(plan.isEmpty())continue;var p=plan.get();
                if(p.effectiveDate()!=null&&month.atEndOfMonth().isBefore(p.effectiveDate()))continue;
                if(loan.method()==RepaymentMethod.GRACE_INSTALLMENT&&(loan.graceEnd()==null||month.atEndOfMonth().isBefore(loan.graceEnd())))continue;
                boolean saving=loan.method()==RepaymentMethod.BULLET;
                long cash=saving?p.monthlySaving():p.monthlyExtraCash();
                var amount=new PlannedAmount(loan.loanId(),saving?AllocationType.REPAYMENT_SAVING:AllocationType.EXTRA_PAYMENT,cash,null);
                amounts.add(amount);
            }
            months.put(month,i==0?currentGoals(source,amounts,overrides).stream().map(a->remaining(a,source)).toList():amounts);
        }
        return request(source,months,"KEEP_PLANS");
    }
    /** Empty means these captured facts cannot fund/define this candidate; no engine or external call is made. */
    public static Optional<Prepared> candidate(RiskScenarioInput source,Code code){
        return candidate(source,code,List.of());
    }
    public static Optional<Prepared> candidate(RiskScenarioInput source,Code code,List<PlannedAmount> overrides){
        var facts=source.household();String type=StrategyInputPolicy.type(facts.loans(),facts.asOf());
        if(type.equals("NONE"))return Optional.empty();
        boolean single=type.equals("SINGLE_PREFERENCE"),savingOnly=type.equals("SAVINGS_ONLY");
        if(single&&!List.of(Code.FAST,Code.BALANCED,Code.STABLE).contains(code)
                ||savingOnly&&code!=Code.SAVINGS||type.equals("MULTI")&&!List.of(Code.RATE_RISK,Code.INTEREST_SAVING,Code.PARALLEL).contains(code))
            throw new IllegalArgumentException("Strategy does not match captured loan type");
        var bullets=facts.loans().stream().filter(l->l.status()==LoanStatus.ACTIVE&&l.outstanding()>0&&l.method()==RepaymentMethod.BULLET)
            .sorted(Comparator.comparingLong(LoanTerms::loanId)).toList();
        List<PlannedAmount> goals=new ArrayList<>();long required=0;
        for(var loan:bullets){var plan=plan(source,loan.loanId());if(plan.isEmpty())return Optional.empty();
            long amount=plan.get().requiredMonthlySaving();if(amount<0)throw new IllegalArgumentException("Negative saving requirement");
            required=Math.addExact(required,amount);goals.add(new PlannedAmount(loan.loanId(),AllocationType.REPAYMENT_SAVING,amount,null));}
        Long configured=null;
        var direct=StrategyInputPolicy.directTargets(facts.loans(),facts.asOf());
        if(!single&&!savingOnly){
            for(var loan:facts.loans())if(bullets.contains(loan)||direct.contains(loan)){
                var plan=plan(source,loan.loanId());if(plan.isPresent())configured=Math.addExact(configured==null?0:configured,
                    loan.method()==RepaymentMethod.BULLET?plan.get().monthlySaving():plan.get().monthlyExtraCash());
            }
        }
        int percent=code==Code.FAST?80:code==Code.STABLE||savingOnly?0:50;
        var budget=StrategyInputPolicy.budget(facts.capacity(),configured,required,percent);
        if(!budget.fundingAvailable())return Optional.empty();
        long extra=savingOnly?0:Math.subtractExact(budget.recurringCash(),required);
        if(single)goals.add(new PlannedAmount(direct.getFirst().loanId(),AllocationType.EXTRA_PAYMENT,extra,null));
        else if(!savingOnly){
            var ordered=StrategyInputPolicy.order(direct,code);
            long[] cash=code==Code.PARALLEL?proportions(ordered,extra):new long[ordered.size()];
            if(code!=Code.PARALLEL)cash[0]=extra;
            for(int i=0;i<ordered.size();i++)goals.add(new PlannedAmount(ordered.get(i).loanId(),AllocationType.EXTRA_PAYMENT,cash[i],code==Code.PARALLEL?1:i+1));
        }
        Set<Long> scope=new HashSet<>();facts.loans().forEach(l->scope.add(l.loanId()));
        long paid=Math.max(Math.addExact(facts.capacity().usedExtraCash(),facts.capacity().usedSaving()),
            Math.addExact(sum(source.payments().extraCashByLoan(),scope),sum(source.savings().additionsByLoan(),scope)));
        var currentGoals=currentGoals(source,goals,overrides);
        long currentTotal=currentGoals.stream().map(PlannedAmount::cashAmount).reduce(0L,Math::addExact);
        long currentLimit=Math.max(Math.subtractExact(Math.min(currentTotal,facts.capacity().allocationCapacity()),paid),0);
        long capacityLeft=Math.max(Math.subtractExact(Math.subtractExact(facts.capacity().allocationCapacity(),paid),facts.capacity().reserveAmount()),0);
        currentLimit=Math.min(currentLimit,capacityLeft);
        if(facts.capacity().remainingAllocatable()!=null)currentLimit=Math.min(currentLimit,Math.max(facts.capacity().remainingAllocatable(),0));
        var remaining=currentGoals.stream().map(goal->remaining(goal,source)).toList();
        long savingLeft=remaining.stream().filter(a->a.allocationType()==AllocationType.REPAYMENT_SAVING).map(PlannedAmount::cashAmount).reduce(0L,Math::addExact);
        if(savingLeft>currentLimit)return Optional.empty();
        List<PlannedAmount> current=new ArrayList<>();long left=currentLimit;
        for(var amount:remaining){long cash=Math.min(left,amount.cashAmount());left-=cash;current.add(new PlannedAmount(amount.loanId(),amount.allocationType(),cash,amount.priority()));}
        Map<YearMonth,List<PlannedAmount>> months=new LinkedHashMap<>();months.put(facts.month(),current);
        for(int i=1;i<1200;i++)months.put(facts.month().plusMonths(i),goals);
        long requestedCurrent=remaining.stream().map(PlannedAmount::cashAmount).reduce(0L,Math::addExact);
        return Optional.of(new Prepared(goals,request(source,months,code.name()),budget,requestedCurrent<=currentLimit));
    }
    private static Optional<PlanSnapshot> plan(RiskScenarioInput source,long loanId){
        var matching=source.household().plans().stream().filter(p->p.loanId()==loanId&&p.configured()).toList();
        if(matching.size()>1)throw new IllegalArgumentException("Duplicate configured plan");return matching.stream().findFirst();
    }
    private static PlannedAmount remaining(PlannedAmount amount,RiskScenarioInput source){
        long actual=(amount.allocationType()==AllocationType.REPAYMENT_SAVING?source.savings().additionsByLoan():source.payments().extraCashByLoan()).getOrDefault(amount.loanId(),0L);
        return new PlannedAmount(amount.loanId(),amount.allocationType(),Math.max(Math.subtractExact(amount.cashAmount(),actual),0),amount.priority());
    }
    private static long sum(Map<Long,Long> values,Set<Long> scope){return values.entrySet().stream().filter(e->scope.contains(e.getKey())).map(Map.Entry::getValue).reduce(0L,Math::addExact);}
    private static List<PlannedAmount> currentGoals(RiskScenarioInput source,List<PlannedAmount> goals,List<PlannedAmount> overrides){
        Map<Long,PlannedAmount> current=new LinkedHashMap<>();goals.forEach(goal->current.put(goal.loanId(),goal));Set<Long> seen=new HashSet<>();
        for(var override:overrides){
            var loan=source.household().loans().stream().filter(l->l.loanId()==override.loanId()).findFirst();if(loan.isEmpty())continue;
            var expected=loan.get().method()==RepaymentMethod.BULLET?AllocationType.REPAYMENT_SAVING:AllocationType.EXTRA_PAYMENT;
            if(override.cashAmount()<0||override.allocationType()!=expected||!seen.add(override.loanId()))throw new IllegalArgumentException("Invalid captured month override");
            current.put(override.loanId(),new PlannedAmount(override.loanId(),override.allocationType(),override.cashAmount(),null));
        }
        // Saving conditions retain priority over direct extra cash, regardless of override insertion order.
        return current.values().stream().sorted(Comparator.comparingInt(a->a.allocationType()==AllocationType.REPAYMENT_SAVING?0:1)).toList();
    }
    /** Initial cash weights only; the engine must recompute remaining-principal weights at each period. */
    private static long[] proportions(List<LoanTerms> ordered,long cash){
        var total=ordered.stream().map(l->BigDecimal.valueOf(l.outstanding())).reduce(BigDecimal.ZERO,BigDecimal::add);
        long[] values=new long[ordered.size()];long assigned=0;
        for(int i=0;i<values.length;i++){values[i]=BigDecimal.valueOf(cash).multiply(BigDecimal.valueOf(ordered.get(i).outstanding())).divide(total,0,RoundingMode.DOWN).longValueExact();assigned=Math.addExact(assigned,values[i]);}
        for(int i=0;assigned<cash;i++,assigned++)values[i]++;
        return values;
    }
    private static SimulationInput request(RiskScenarioInput source,Map<YearMonth,List<PlannedAmount>> months,String rule){
        return new SimulationInput(source.household().loans(),source.household().asOf(),1200,months,Map.of(),null,rule);
    }
}
