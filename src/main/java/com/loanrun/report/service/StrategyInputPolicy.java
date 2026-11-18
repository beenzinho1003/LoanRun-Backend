package com.loanrun.report.service;

import com.loanrun.common.contract.FinanceModels.*;
import com.loanrun.report.service.StrategyRecommendationPolicy.Code;
import java.math.*;
import java.time.LocalDate;
import java.util.*;

/** Candidate input conditions only. The repayment engine owns fees, principal caps and per-period reallocation. */
public final class StrategyInputPolicy {
    private StrategyInputPolicy() {}
    public record CashBudget(Long recurringCash,Long currentCash,Long requiredSaving,boolean fundingAvailable,List<String> warnings) {
        public CashBudget {warnings=List.copyOf(warnings);}
    }
    /** null configuredCash means unset; an explicit zero remains a zero goal except required saving takes priority. */
    public static CashBudget budget(BudgetCapacity capacity,Long configuredCash,Long requiredSaving,int defaultPercent) {
        if(defaultPercent!=0&&defaultPercent!=50&&defaultPercent!=80) throw new IllegalArgumentException("Unsupported preference percentage");
        if((configuredCash!=null&&configuredCash<0)||(requiredSaving!=null&&requiredSaving<0)) throw new IllegalArgumentException("Negative cash goal");
        if(capacity.availableCash()==null||capacity.recurringCap()==null||capacity.allocationCapacity()==null||requiredSaving==null)
            return new CashBudget(null,null,requiredSaving,false,List.of("STRATEGY_INPUT_INCOMPLETE"));
        long cap=Math.max(capacity.recurringCap(),0);
        long requested=configuredCash==null?fraction(cap,defaultPercent):configuredCash;
        long recurring=Math.min(Math.max(requested,requiredSaving),cap);
        long current=Math.min(recurring,Math.max(capacity.allocationCapacity(),0));
        List<String> warnings=new ArrayList<>();
        if(capacity.availableCash()<0) warnings.add("BASIC_PAYMENT_SHORTFALL");
        if(requiredSaving>recurring||requiredSaving>current) warnings.add("REQUIRED_SAVING_SHORTFALL");
        return new CashBudget(recurring,current,requiredSaving,warnings.isEmpty(),warnings);
    }
    private static long fraction(long cash,int percent){return BigDecimal.valueOf(cash).multiply(BigDecimal.valueOf(percent))
        .divide(BigDecimal.valueOf(100),0,RoundingMode.HALF_UP).longValueExact();}
    public static List<LoanTerms> directTargets(List<LoanTerms> loans,LocalDate asOf){
        return active(loans).stream().filter(loan->loan.method()!=RepaymentMethod.BULLET)
            .filter(loan->loan.method()!=RepaymentMethod.GRACE_INSTALLMENT||(loan.graceEnd()!=null&&!asOf.isBefore(loan.graceEnd()))).toList();
    }
    public static String type(List<LoanTerms> loans,LocalDate asOf){
        var active=active(loans);var direct=directTargets(active,asOf);
        if(direct.isEmpty())return active.stream().anyMatch(loan->loan.method()==RepaymentMethod.BULLET)?"SAVINGS_ONLY":"NONE";
        return active.size()==1?"SINGLE_PREFERENCE":"MULTI";
    }
    private static List<LoanTerms> active(List<LoanTerms> loans){return loans.stream().filter(loan->loan.status()==LoanStatus.ACTIVE&&loan.outstanding()>0).toList();}
    /** Input list must already be restricted to eligible direct targets. No money is allocated here. */
    public static List<LoanTerms> order(List<LoanTerms> targets,Code code){
        Comparator<LoanTerms> interest=Comparator.comparing(LoanTerms::annualRate,Comparator.reverseOrder())
            .thenComparingLong(LoanTerms::outstanding).thenComparingLong(LoanTerms::loanId);
        Comparator<LoanTerms> risk=(a,b)->{
            if(a.rateType()!=b.rateType())return a.rateType()==RateType.VARIABLE?-1:1;
            if(a.rateType()==RateType.FIXED)return interest.compare(a,b);
            return Comparator.comparingLong(LoanTerms::outstanding).reversed()
                .thenComparing(LoanTerms::annualRate,Comparator.reverseOrder()).thenComparingLong(LoanTerms::loanId).compare(a,b);
        };
        var ordering=switch(code){case RATE_RISK->risk;case INTEREST_SAVING->interest;case PARALLEL->Comparator.comparingLong(LoanTerms::loanId);
            default->throw new IllegalArgumentException("Expected multi-loan strategy");};
        return targets.stream().sorted(ordering).toList();
    }
}
