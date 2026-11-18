package com.loanrun;

import static org.assertj.core.api.Assertions.*;
import static com.loanrun.report.service.StrategyRecommendationPolicy.*;
import com.loanrun.report.service.StrategyRecommendationPolicy;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;

class StrategyRecommendationPolicyTest {
    Cost cost(Code code,Long interest,Long fee){return new Cost(code,interest,fee,true);}
    Code choose(Comparison comparison,String current){return multi(comparison,current,null,false,false,true);}
    @Test void knownFeesCanReverseTheInterestOnlyWinnerAndNegativeSavingsArePreserved(){
        var result=compare(1000L,10L,List.of(cost(Code.INTEREST_SAVING,700L,400L),cost(Code.RATE_RISK,800L,20L),cost(Code.PARALLEL,1100L,10L)));
        assertThat(result.basis()).isEqualTo(FeeBasis.NET);
        assertThat(result.savings().getFirst().netSaving()).isEqualTo(-90);
        assertThat(result.savings().getLast().interestSaving()).isEqualTo(-100);
        assertThat(choose(result,null)).isEqualTo(Code.RATE_RISK);
    }
    @Test void OneUnknownFeeSwitchesEveryCandidateToGrossWithoutInventingZero(){
        var result=compare(1000L,10L,List.of(cost(Code.INTEREST_SAVING,700L,400L),cost(Code.RATE_RISK,800L,null)));
        assertThat(result.basis()).isEqualTo(FeeBasis.GROSS_INTEREST);
        assertThat(result.savings()).allSatisfy(s->assertThat(s.netSaving()).isNull());
        assertThat(choose(result,null)).isEqualTo(Code.INTEREST_SAVING);
        assertThat(compare(1000L,null,List.of(cost(Code.RATE_RISK,800L,0L))).basis()).isEqualTo(FeeBasis.GROSS_INTEREST);
    }
    @Test void tiesPreferAppliedStrategyThenTheAgreedOrderRegardlessOfInputOrder(){
        var result=compare(1000L,0L,List.of(cost(Code.PARALLEL,800L,0L),cost(Code.RATE_RISK,800L,0L),cost(Code.INTEREST_SAVING,800L,0L)));
        assertThat(choose(result,"PARALLEL")).isEqualTo(Code.PARALLEL);
        assertThat(choose(result,"MANUAL")).isEqualTo(Code.INTEREST_SAVING);
        assertThat(choose(compare(1000L,0L,List.of(cost(Code.PARALLEL,800L,0L),cost(Code.RATE_RISK,800L,0L))),null)).isEqualTo(Code.RATE_RISK);
    }
    @Test void rateOverrideRequiresAllRiskConditionsAndNeverRecommendsAnInfeasibleCandidate(){
        var result=compare(1000L,0L,List.of(cost(Code.INTEREST_SAVING,700L,0L),cost(Code.RATE_RISK,900L,0L)));
        assertThat(multi(result,null,new BigDecimal("50"),true,true,true)).isEqualTo(Code.RATE_RISK);
        assertThat(multi(result,null,new BigDecimal("49.99"),true,true,true)).isEqualTo(Code.INTEREST_SAVING);
        assertThat(multi(result,null,new BigDecimal("50"),false,true,true)).isEqualTo(Code.INTEREST_SAVING);
        assertThat(multi(result,null,new BigDecimal("50"),true,false,true)).isEqualTo(Code.INTEREST_SAVING);
        var infeasible=compare(1000L,0L,List.of(cost(Code.INTEREST_SAVING,700L,0L),new Cost(Code.RATE_RISK,900L,0L,false)));
        assertThat(multi(infeasible,null,new BigDecimal("50"),true,true,true)).isEqualTo(Code.INTEREST_SAVING);
        assertThat(multi(result,null,new BigDecimal("50"),true,true,false)).isNull();
    }
    @Test void incompleteComparableInterestDefersRankingAndOverflowFailsExplicitly(){
        assertThat(choose(compare(1000L,0L,List.of(cost(Code.INTEREST_SAVING,null,0L),cost(Code.RATE_RISK,800L,0L))),null)).isNull();
        assertThat(choose(compare(null,0L,List.of(cost(Code.RATE_RISK,800L,0L))),null)).isNull();
        assertThat(choose(compare(1000L,0L,List.of()),null)).isNull();
        assertThatThrownBy(()->compare(Long.MAX_VALUE,0L,List.of(cost(Code.RATE_RISK,-1L,0L)))).isInstanceOf(ArithmeticException.class);
        assertThatThrownBy(()->compare(1000L,0L,List.of(cost(Code.RATE_RISK,800L,0L),cost(Code.RATE_RISK,900L,0L)))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void singleRecommendationUsesExactThreeEightAndNinetyPercentBoundaries(){
        assertThat(single(299L,10000L,0L,100L)).isEqualTo(Code.STABLE);
        assertThat(single(300L,10000L,0L,100L)).isEqualTo(Code.BALANCED);
        assertThat(single(799L,10000L,0L,100L)).isEqualTo(Code.BALANCED);
        assertThat(single(800L,10000L,89L,100L)).isEqualTo(Code.FAST);
        assertThat(single(800L,10000L,90L,100L)).isEqualTo(Code.BALANCED);
        assertThat(single(1L,33L,0L,100L)).isEqualTo(Code.BALANCED);
    }
    @Test void missingInputsStayUnknownExceptForAlreadyProvenStableOrBalancedConditions(){
        assertThat(single(-1L,null,null,null)).isEqualTo(Code.STABLE);
        assertThat(single(null,1000L,0L,100L)).isNull();
        assertThat(single(80L,0L,0L,100L)).isNull();
        assertThat(single(80L,1000L,null,100L)).isNull();
        assertThat(single(80L,1000L,0L,0L)).isNull();
        assertThat(single(30L,1000L,null,null)).isEqualTo(Code.BALANCED);
    }
    @Test void judgementNeedsHistoryAndTheSameRecommendedCodeForKeep(){
        assertThat(judgement("NONE",null,null)).isEqualTo("FIRST");
        assertThat(judgement("RATE_RISK",null,new BigDecimal("95"))).isEqualTo("INSUFFICIENT_HISTORY");
        assertThat(judgement("RATE_RISK",Code.RATE_RISK,null)).isEqualTo("INSUFFICIENT_HISTORY");
        assertThat(judgement("RATE_RISK",Code.RATE_RISK,new BigDecimal("90"))).isEqualTo("KEEP");
        assertThat(judgement("RATE_RISK",Code.RATE_RISK,new BigDecimal("89.99"))).isEqualTo("CHANGE");
        assertThat(judgement("RATE_RISK",Code.INTEREST_SAVING,new BigDecimal("100"))).isEqualTo("CHANGE");
    }
}
