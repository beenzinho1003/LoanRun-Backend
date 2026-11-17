package com.loanrun;

import static org.assertj.core.api.Assertions.*;
import com.loanrun.risk.dto.RiskDtos.*;
import com.loanrun.risk.service.RiskMath;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class RiskMathTest {
    RiskMath.Assessment assess(long surplus,long burden,long used,long limit){return RiskMath.assess(1000000L,surplus,burden,used,limit,BigDecimal.ZERO,false,surplus,0L,true);}
    @Test void surplusBoundariesUseExactValuesBeforeDisplayRounding(){
        assertThat(assess(29999,0,0,1).checks().get(0).level()).isEqualTo(Level.DANGER);
        assertThat(assess(30000,0,0,1).checks().get(0).level()).isEqualTo(Level.CAUTION);
        var below=assess(79999,0,0,1).checks().get(0);assertThat(below.ratioPct()).isEqualByComparingTo("8.00");assertThat(below.level()).isEqualTo(Level.CAUTION);
        assertThat(assess(80000,0,0,1).checks().get(0).level()).isEqualTo(Level.STABLE);
    }
    @Test void basicAndDiscretionaryBoundariesMatchTheSpecifiedOpenAndClosedIntervals(){
        assertThat(assess(100000,299999,89999,100000).checks().get(3).level()).isEqualTo(Level.STABLE);
        assertThat(assess(100000,300000,90000,100000).checks().get(3).level()).isEqualTo(Level.CAUTION);
        assertThat(assess(100000,399999,100000,100000).checks().get(1).level()).isEqualTo(Level.CAUTION);
        var danger=assess(100000,400000,100001,100000);assertThat(danger.checks().get(3).level()).isEqualTo(Level.DANGER);assertThat(danger.checks().get(1).level()).isEqualTo(Level.DANGER);
    }
    @Test void zeroAndMissingAreDifferentAndConfirmedBasicFailureOverridesIncompleteData(){
        assertThat(assess(100000,0,0,0).checks().get(1).level()).isEqualTo(Level.STABLE);
        assertThat(assess(100000,0,1,0).checks().get(1).level()).isEqualTo(Level.DANGER);
        var missing=RiskMath.assess(0L,0L,0,null,0L,null,false,0L,null,true);assertThat(missing.score()).isNull();assertThat(missing.level()).isNull();
        var failure=RiskMath.assess(0L,-1L,1,null,0L,null,false,-1L,null,true);assertThat(failure.level()).isEqualTo(Level.DANGER);assertThat(failure.score()).isNull();
        assertThat(RiskMath.assess(1000000L,-1L,0,0L,0L,null,false,-1L,0L,false).level()).isNull();
    }
    @Test void weightedScoreAndDangerThresholdAreIndependentFromOneIndicator(){
        var sample=RiskMath.assess(3500000L,740000L,280000,780000L,800000L,BigDecimal.ZERO,false,740000L,100000L,true);
        assertThat(sample.score()).isEqualByComparingTo("7.5");assertThat(sample.level()).isEqualTo(Level.STABLE);
        var sixty=RiskMath.assess(1000000L,100000L,400000,101L,100L,new BigDecimal("50"),true,-1L,0L,true);
        assertThat(sixty.score()).isEqualByComparingTo("60");assertThat(sixty.level()).isEqualTo(Level.DANGER);
    }
    @Test void targetOrStressedBasicFailureRaisesTheMinimumOverallLevel(){
        assertThat(RiskMath.assess(1000000L,100000L,0,0L,1L,BigDecimal.ZERO,false,100000L,100001L,true).level()).isEqualTo(Level.CAUTION);
        assertThat(RiskMath.assess(1000000L,100000L,0,0L,1L,new BigDecimal("10"),true,-1L,0L,true).level()).isEqualTo(Level.CAUTION);
    }
    @Test void thresholdSearchReturnsTheFirstStrictFailureOrNullWithinTheBound(){
        assertThat(RiskMath.firstFailure(i->500000L-i*1000L<100000,500,new BigDecimal("0.01"))).isEqualByComparingTo("4.01");
        assertThat(RiskMath.firstFailure(i->500000L-i*1000L<0,500,new BigDecimal("0.01"))).isNull();
        assertThat(RiskMath.firstFailure(i->true,500,new BigDecimal("0.01"))).isEqualByComparingTo("0");
        assertThat(RiskMath.expenseThreshold(500000L,100000L)).isEqualByComparingTo("400001");
        assertThat(RiskMath.expenseThreshold(500000L,0L)).isEqualByComparingTo("500001");
    }
}
