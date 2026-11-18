package com.loanrun.report.service;

import com.loanrun.common.contract.FinanceModels.HistoryMonth;
import java.math.*;
import java.time.YearMonth;
import java.util.*;

/** Personal observed-history aggregation only; no peer fixtures, financial engine or HTTP DTOs. */
public final class ReportStatistics {
    private ReportStatistics() {}
    public record Stats(BigDecimal monthlyPrincipalRepaymentRate,Long principalRepaid,BigDecimal principalReductionPct,
        int observedMonthCount,Long savingsDeposited,Long savingsUsed,Integer extraCount,BigDecimal averageDebtServiceRatio,String dataStatus) {}
    public static Stats calculate(List<HistoryMonth> history,Map<YearMonth,Long> actualIncome,YearMonth basisMonth) {
        Objects.requireNonNull(basisMonth);
        var observed=history.stream().filter(m->m.observed()&&!m.month().isBefore(basisMonth.minusMonths(6))&&m.month().isBefore(basisMonth))
            .sorted(Comparator.comparing(HistoryMonth::month)).toList();
        Set<YearMonth> unique=new HashSet<>();
        for(var m:observed) if(!unique.add(m.month())) throw new IllegalArgumentException("Duplicate observed history month");
        if(observed.isEmpty()) return new Stats(null,null,null,0,null,null,null,null,"INSUFFICIENT");
        long principal=0,registered=0,deposits=0,used=0;int extraCount=0;
        List<BigDecimal> pace=new ArrayList<>(),debt=new ArrayList<>();
        for(var m:observed) {
            long paid=Math.addExact(m.regularPrincipal(),m.extraPrincipal());principal=Math.addExact(principal,paid);
            registered=Math.addExact(registered,m.registeredBalance());deposits=Math.addExact(deposits,m.savingAdditions());
            used=Math.addExact(used,m.savingsUsed());extraCount=Math.addExact(extraCount,m.extraCount());
            var rate=ratio(paid,Math.addExact(m.openingBalance(),m.registeredBalance()));if(rate!=null) pace.add(rate);
            Long income=actualIncome.get(m.month());
            var service=income==null?null:ratio(Math.addExact(paid,m.interest()),income);if(service!=null) debt.add(service);
        }
        var average=average(pace);
        return new Stats(average,principal,display(ratio(principal,Math.addExact(observed.getFirst().openingBalance(),registered))),
            observed.size(),deposits,used,extraCount,average(debt),average==null?"INSUFFICIENT":"SUFFICIENT");
    }
    private static BigDecimal ratio(long value,long denominator){return denominator<=0?null:
        BigDecimal.valueOf(value).multiply(BigDecimal.valueOf(100)).divide(BigDecimal.valueOf(denominator),MathContext.DECIMAL128);}
    private static BigDecimal average(List<BigDecimal> values){return values.isEmpty()?null:values.stream().reduce(BigDecimal.ZERO,BigDecimal::add)
        .divide(BigDecimal.valueOf(values.size()),2,RoundingMode.HALF_UP);}
    private static BigDecimal display(BigDecimal value){return value==null?null:value.setScale(2,RoundingMode.HALF_UP);}
}
