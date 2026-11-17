package com.loanrun.risk.service;

import com.loanrun.risk.dto.RiskDtos.*;
import java.math.*;
import java.util.List;
import java.util.function.IntPredicate;

/** Team-defined risk arithmetic; all loan cash burdens are supplied by Team 1's engine/read projections. */
public final class RiskMath {
    private RiskMath(){}
    public record Assessment(Level level,BigDecimal score,List<Check> checks,String dataStatus){}
    public static Assessment assess(Long income,Long available,long basicBurden,Long discretionary,Long limit,
            BigDecimal exposure,boolean variable,Long afterRate,Long target,boolean hasLoans){
        BigDecimal surplus=ratio(available,income),debt=ratio(basicBurden,income),usage=ratio(discretionary,limit);
        Level surplusLevel=surplus==null?null:surplus.compareTo(BigDecimal.valueOf(8))>=0?Level.STABLE:surplus.compareTo(BigDecimal.valueOf(3))>=0?Level.CAUTION:Level.DANGER;
        Level debtLevel=debt==null?null:debt.compareTo(BigDecimal.valueOf(30))<0?Level.STABLE:debt.compareTo(BigDecimal.valueOf(40))<0?Level.CAUTION:Level.DANGER;
        Level spending=null;
        if(discretionary!=null&&limit!=null)spending=limit==0?(discretionary==0?Level.STABLE:Level.DANGER):usage.compareTo(BigDecimal.valueOf(90))<0?Level.STABLE:usage.compareTo(BigDecimal.valueOf(100))<=0?Level.CAUTION:Level.DANGER;
        Level rate=!variable?Level.STABLE:afterRate==null||target==null?null:afterRate<0?Level.DANGER:afterRate<target?Level.CAUTION:Level.STABLE;
        var checks=List.of(check("SURPLUS_RATIO","여유자금 비율",surplus,surplusLevel,40),check("DISCRETIONARY_LIMIT","선택 지출 한도",usage,spending,15),
            check("VARIABLE_EXPOSURE","변동금리 노출",exposure,rate,15),check("BASIC_DEBT_SERVICE","소득 대비 기본 상환",debt,debtLevel,30));
        boolean complete=hasLoans&&checks.stream().allMatch(c->c.level()!=null);
        BigDecimal score=complete?checks.stream().map(c->BigDecimal.valueOf((long)c.points()*c.weight())).reduce(BigDecimal.ZERO,BigDecimal::add).divide(BigDecimal.valueOf(100)):null;
        Level level=score==null?null:score.compareTo(BigDecimal.valueOf(25))<0?Level.STABLE:score.compareTo(BigDecimal.valueOf(60))<0?Level.CAUTION:Level.DANGER;
        if(hasLoans&&available!=null&&available<0)level=Level.DANGER;
        else if(level==Level.STABLE&&((available!=null&&target!=null&&available<target)||(afterRate!=null&&afterRate<0)))level=Level.CAUTION;
        return new Assessment(level,score,checks,complete?"SUFFICIENT":"INSUFFICIENT");
    }
    private static Check check(String code,String label,BigDecimal ratio,Level level,int weight){
        return new Check(code,label,display(ratio),level,weight,level==null?null:level==Level.STABLE?0:level==Level.CAUTION?50:100);
    }
    public static BigDecimal ratio(Long numerator,Long denominator){
        return numerator==null||denominator==null||denominator<=0?null:BigDecimal.valueOf(numerator).multiply(BigDecimal.valueOf(100)).divide(BigDecimal.valueOf(denominator),MathContext.DECIMAL128);
    }
    public static BigDecimal display(BigDecimal value){return value==null?null:value.setScale(2,RoundingMode.HALF_UP);}
    public static BigDecimal firstFailure(IntPredicate fails,int steps,BigDecimal step){
        if(fails.test(0))return BigDecimal.ZERO;if(!fails.test(steps))return null;
        int lo=1,hi=steps;while(lo<hi){int mid=(lo+hi)>>>1;if(fails.test(mid))hi=mid;else lo=mid+1;}
        return step.multiply(BigDecimal.valueOf(lo));
    }
    public static BigDecimal expenseThreshold(Long available,Long target){
        if(available==null||target==null)return null;if(available<target)return BigDecimal.ZERO;
        BigInteger result=BigInteger.valueOf(available).subtract(BigInteger.valueOf(target)).add(BigInteger.ONE);
        return result.compareTo(BigInteger.valueOf(Long.MAX_VALUE))>0?null:new BigDecimal(result);
    }
}
