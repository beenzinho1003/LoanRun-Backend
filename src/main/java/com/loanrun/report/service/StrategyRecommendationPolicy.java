package com.loanrun.report.service;

import java.math.BigDecimal;
import java.util.*;

/** Applies agreed recommendation rules to engine results; never calculates interest or repayment schedules. */
public final class StrategyRecommendationPolicy {
    private StrategyRecommendationPolicy() {}
    public enum Code { INTEREST_SAVING, RATE_RISK, PARALLEL, FAST, BALANCED, STABLE, SAVINGS }
    public enum FeeBasis { NET, GROSS_INTEREST }
    public record Cost(Code code,Long interest,Long fee,boolean feasible) {
        public Cost { Objects.requireNonNull(code); }
    }
    public record Saving(Code code,Long interestSaving,Long netSaving,boolean feasible) {}
    public record Comparison(FeeBasis basis,List<Saving> savings) {
        public Comparison { savings=List.copyOf(savings); }
    }
    public static Comparison compare(Long baselineInterest,Long baselineFee,List<Cost> costs) {
        var codes=EnumSet.noneOf(Code.class);
        for(var cost:costs) if(!codes.add(cost.code())) throw new IllegalArgumentException("Duplicate strategy code");
        boolean known=baselineFee!=null&&costs.stream().allMatch(cost->cost.fee()!=null);
        var savings=costs.stream().map(cost->{
            Long gross=baselineInterest==null||cost.interest()==null?null:Math.subtractExact(baselineInterest,cost.interest());
            Long net=known&&gross!=null?Math.subtractExact(gross,Math.subtractExact(cost.fee(),baselineFee)):null;
            return new Saving(cost.code(),gross,net,cost.feasible());
        }).toList();
        return new Comparison(known?FeeBasis.NET:FeeBasis.GROSS_INTEREST,savings);
    }
    /** Unknown capacity/saving sufficiency is supplied as false by the captured-facts adapter. */
    public static Code multi(Comparison comparison,String current,BigDecimal variableExposurePct,
            boolean rateUpCannotKeepPlan,boolean eligibleVariable,boolean requiredFundingAvailable) {
        if(!requiredFundingAvailable) return null;
        var eligible=comparison.savings().stream().filter(Saving::feasible).toList();
        for(var saving:eligible) if(!List.of(Code.INTEREST_SAVING,Code.RATE_RISK,Code.PARALLEL).contains(saving.code()))
            throw new IllegalArgumentException("Expected multi-loan strategies");
        if(variableExposurePct!=null&&variableExposurePct.compareTo(BigDecimal.valueOf(50))>=0
                &&rateUpCannotKeepPlan&&eligibleVariable&&eligible.stream().anyMatch(s->s.code()==Code.RATE_RISK)) return Code.RATE_RISK;
        // A missing comparable cost could change which candidate wins; do not silently drop it.
        if(eligible.stream().anyMatch(s->score(comparison.basis(),s)==null)) return null;
        return eligible.stream().sorted(Comparator.comparing((Saving s)->score(comparison.basis(),s)).reversed()
            .thenComparingInt(s->s.code().name().equals(current)?-1:s.code().ordinal()))
            .map(Saving::code).findFirst().orElse(null);
    }
    private static Long score(FeeBasis basis,Saving saving){return basis==FeeBasis.NET?saving.netSaving():saving.interestSaving();}

    public static Code single(Long availableCash,Long planningIncome,Long actualDiscretionary,Long discretionaryLimit) {
        if(availableCash==null) return null;
        if(availableCash<0) return Code.STABLE;
        if(planningIncome==null||planningIncome<=0) return null;
        var percentCash=BigDecimal.valueOf(availableCash).multiply(BigDecimal.valueOf(100));
        var income=BigDecimal.valueOf(planningIncome);
        if(percentCash.compareTo(income.multiply(BigDecimal.valueOf(3)))<0) return Code.STABLE;
        if(percentCash.compareTo(income.multiply(BigDecimal.valueOf(8)))<0) return Code.BALANCED;
        if(actualDiscretionary==null||discretionaryLimit==null||discretionaryLimit<=0) return null;
        return BigDecimal.valueOf(actualDiscretionary).multiply(BigDecimal.valueOf(100))
            .compareTo(BigDecimal.valueOf(discretionaryLimit).multiply(BigDecimal.valueOf(90)))>=0?Code.BALANCED:Code.FAST;
    }
    public static String judgement(String current,Code recommended,BigDecimal adherencePct) {
        if(current==null||current.equals("NONE")) return "FIRST";
        if(recommended==null||adherencePct==null) return "INSUFFICIENT_HISTORY";
        return recommended.name().equals(current)&&adherencePct.compareTo(BigDecimal.valueOf(90))>=0?"KEEP":"CHANGE";
    }
}
