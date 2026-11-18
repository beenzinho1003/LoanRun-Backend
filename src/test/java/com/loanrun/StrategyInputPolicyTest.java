package com.loanrun;

import static org.assertj.core.api.Assertions.*;
import static com.loanrun.report.service.StrategyInputPolicy.*;
import com.loanrun.common.contract.FinanceModels.*;
import com.loanrun.report.service.StrategyRecommendationPolicy.Code;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class StrategyInputPolicyTest {
    final LocalDate date=LocalDate.of(2026,11,18);
    BudgetCapacity capacity(Long available,Long recurring,Long current){return new BudgetCapacity(YearMonth.from(date),available,recurring,99999,current,0,0,current,0,null,"CONFIRMED","OPEN",List.of());}
    LoanTerms loan(long id,long balance,String rate,RateType type,RepaymentMethod method,LocalDate grace,LoanStatus status){return new LoanTerms(id,1,"가상",LoanType.CREDIT,
        balance,balance,date,new BigDecimal(rate),type,method,grace,method==RepaymentMethod.GRACE_INSTALLMENT?RepaymentMethod.EQUAL_PAYMENT:null,date.plusDays(7),25,
        date.plusYears(1),100L,100L,null,PrepaymentPolicy.KEEP_PAYMENT,FeeMethod.UNKNOWN,null,null,null,0,0,status,null);}
    LoanTerms loan(long id,long balance,String rate,RateType type){return loan(id,balance,rate,type,RepaymentMethod.EQUAL_PAYMENT,null,LoanStatus.ACTIVE);}
    @Test void carryDoesNotBecomeRecurringCashAndHalfWonRoundsHalfUp(){
        var result=budget(capacity(501L,501L,100500L),null,0L,50);
        assertThat(result.recurringCash()).isEqualTo(251);assertThat(result.currentCash()).isEqualTo(251);assertThat(result.fundingAvailable()).isTrue();
        assertThat(budget(capacity(501L,501L,100500L),null,0L,80).recurringCash()).isEqualTo(401);
        assertThat(budget(capacity(501L,501L,100500L),null,0L,0).recurringCash()).isZero();
    }
    @Test void explicitZeroDiffersFromUnsetAndRequiredSavingTakesPriorityWithinTheCap(){
        assertThat(budget(capacity(1000L,1000L,1000L),0L,0L,50).recurringCash()).isZero();
        var result=budget(capacity(1000L,1000L,1000L),0L,700L,50);
        assertThat(result.recurringCash()).isEqualTo(700);assertThat(result.fundingAvailable()).isTrue();
        assertThat(budget(capacity(1000L,1000L,1000L),5000L,0L,50).recurringCash()).isEqualTo(1000);
    }
    @Test void recurringAndCurrentSavingShortfallsBothWithholdRecommendations(){
        var recurring=budget(capacity(1000L,1000L,10000L),200L,1200L,50);
        assertThat(recurring.recurringCash()).isEqualTo(1000);assertThat(recurring.warnings()).containsExactly("REQUIRED_SAVING_SHORTFALL");assertThat(recurring.fundingAvailable()).isFalse();
        var current=budget(capacity(1000L,1000L,600L),900L,700L,50);
        assertThat(current.recurringCash()).isEqualTo(900);assertThat(current.currentCash()).isEqualTo(600);assertThat(current.fundingAvailable()).isFalse();
    }
    @Test void unknownAndAlreadyUnaffordableInputsNeverBecomeSuccessfulZeroBudgets(){
        for(var cap:List.of(capacity(null,1000L,1000L),capacity(1000L,null,1000L),capacity(1000L,1000L,null))){
            var result=budget(cap,null,0L,50);assertThat(result.recurringCash()).isNull();assertThat(result.fundingAvailable()).isFalse();assertThat(result.warnings()).containsExactly("STRATEGY_INPUT_INCOMPLETE");
        }
        assertThat(budget(capacity(1000L,1000L,1000L),null,null,50).requiredSaving()).isNull();
        var deficit=budget(capacity(-1L,0L,99998L),null,0L,50);assertThat(deficit.fundingAvailable()).isFalse();assertThat(deficit.warnings()).containsExactly("BASIC_PAYMENT_SHORTFALL");
        assertThatThrownBy(()->budget(capacity(1L,1L,1L),-1L,0L,50)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->budget(capacity(1L,1L,1L),null,0L,20)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void graceAndInactiveTargetsAreExcludedUntilTheExactGraceEnd(){
        var grace=loan(1,100,"6",RateType.VARIABLE,RepaymentMethod.GRACE_INSTALLMENT,date,LoanStatus.ACTIVE);
        var unknown=loan(2,100,"6",RateType.VARIABLE,RepaymentMethod.GRACE_INSTALLMENT,null,LoanStatus.ACTIVE);
        var bullet=loan(3,100,"6",RateType.VARIABLE,RepaymentMethod.BULLET,null,LoanStatus.ACTIVE);
        var paid=loan(4,100,"6",RateType.FIXED,RepaymentMethod.EQUAL_PAYMENT,null,LoanStatus.PAID);
        var deleted=loan(5,100,"6",RateType.FIXED,RepaymentMethod.EQUAL_PAYMENT,null,LoanStatus.DELETED);
        var list=List.of(grace,unknown,bullet,paid,deleted,loan(6,0,"6",RateType.FIXED));
        assertThat(directTargets(list,date.minusDays(1))).isEmpty();assertThat(directTargets(list,date)).containsExactly(grace);
        assertThat(type(List.of(grace),date.minusDays(1))).isEqualTo("NONE");assertThat(type(List.of(grace),date)).isEqualTo("SINGLE_PREFERENCE");
        assertThat(type(list,date)).isEqualTo("MULTI");assertThat(type(list,date.minusDays(1))).isEqualTo("SAVINGS_ONLY");
    }
    @Test void rateRiskUsesVariableBalanceThenRateThenIdBeforeFixedInterestOrder(){
        var a=loan(1,100,"6",RateType.VARIABLE);var b=loan(2,200,"4",RateType.VARIABLE);var c=loan(3,200,"5",RateType.VARIABLE);
        var d=loan(4,200,"5",RateType.VARIABLE);var e=loan(5,100,"20",RateType.FIXED);var f=loan(6,50,"20",RateType.FIXED);
        assertThat(order(List.of(e,d,a,f,c,b),Code.RATE_RISK)).extracting(LoanTerms::loanId).containsExactly(3L,4L,2L,1L,6L,5L);
    }
    @Test void interestAndFixedOnlyRiskOrdersMatchAndParallelSeedsUseIdOrder(){
        var a=loan(1,200,"6",RateType.FIXED);var b=loan(2,100,"6",RateType.FIXED);var c=loan(3,100,"6",RateType.FIXED);var d=loan(4,50,"5",RateType.FIXED);
        var list=List.of(c,d,a,b);assertThat(order(list,Code.INTEREST_SAVING)).extracting(LoanTerms::loanId).containsExactly(2L,3L,1L,4L);
        assertThat(order(list,Code.RATE_RISK)).isEqualTo(order(list,Code.INTEREST_SAVING));
        assertThat(order(list,Code.PARALLEL)).extracting(LoanTerms::loanId).containsExactly(1L,2L,3L,4L);
        assertThat(list).containsExactly(c,d,a,b);
    }
}
