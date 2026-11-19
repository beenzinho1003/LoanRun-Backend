package com.loanrun;

import static org.assertj.core.api.Assertions.*;
import com.loanrun.common.contract.FinanceModels.*;
import com.loanrun.risk.dto.RiskDtos.*;
import com.loanrun.risk.service.RiskDetailHistorySource.*;
import com.loanrun.risk.service.RiskDetailPresentation;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class RiskDetailPresentationTest {
    static final YearMonth MONTH=YearMonth.of(2026,11);
    LoanTerms loan(long id,RepaymentMethod method,LocalDate maturity,LocalDate grace,LocalDate reset){return new LoanTerms(id,9,"가상",LoanType.CREDIT,
        500000,500000,MONTH.atDay(17),BigDecimal.ZERO,RateType.FIXED,method,grace,null,MONTH.atDay(25),25,maturity,100000L,null,null,
        PrepaymentPolicy.KEEP_PAYMENT,FeeMethod.NONE,BigDecimal.ZERO,null,null,0,0,LoanStatus.ACTIVE,reset);}
    ChangeEvent change(Long loan,LocalDate date,Reason reason,Long delta){return new ChangeEvent(loan,date,reason,
        delta!=null&&delta<0?Direction.DECREASE:Direction.INCREASE,delta,BigDecimal.ONE,reason==Reason.RATE?"PP":"KRW",Level.CAUTION,"기록된 변경");}
    @Test void allFutureEventsAreSortedAndImminenceIncludesTodayAndDay30ButNot31(){
        var day=MONTH.atDay(17);var loans=List.of(loan(3,RepaymentMethod.GRACE_INSTALLMENT,day.plusYears(1),day.plusDays(31),day.minusDays(1)),
            loan(2,RepaymentMethod.BULLET,day.plusDays(30),null,null),loan(1,RepaymentMethod.EQUAL_PAYMENT,day.plusYears(1),null,day));
        var events=RiskDetailPresentation.events(loans,null,day);
        assertThat(events).extracting(Event::type).containsExactly("RATE_RESET","MATURITY","GRACE_END");
        assertThat(events).extracting(Event::dDay).containsExactly(0,30,31);
        assertThat(events).extracting(Event::isImminent).containsExactly(true,true,false);
        assertThat(RiskDetailPresentation.events(loans,2L,day)).hasSize(1);
        assertThatThrownBy(()->events.clear()).isInstanceOf(UnsupportedOperationException.class);
    }
    @Test void windowIncludesFirstDaySixMonthsAgoAndExcludesCurrentMonthAndOtherLoan(){
        var rows=List.of(change(null,MONTH.minusMonths(6).atDay(1),Reason.EXPENSE,20000L),change(2L,MONTH.minusMonths(1).atDay(1),Reason.RATE,-5000L),
            change(1L,MONTH.minusMonths(1).atDay(2),Reason.RATE,10000L),change(null,MONTH.atDay(1),Reason.INCOME,90000L),
            change(null,MONTH.minusMonths(6).atDay(1).minusDays(1),Reason.EXPENSE,90000L));
        var result=RiskDetailPresentation.history(new Recorded(Set.of(),rows),2L,MONTH);
        assertThat(result.changes()).hasSize(2);assertThat(result.summary().netMonthlyBurdenDelta()).isEqualTo(15000);
        assertThat(result.summary().rateChangeCount()).isEqualTo(1);assertThat(result.summary().expenseChangeCount()).isEqualTo(1);
        assertThat(result.changes().get(1).direction()).isEqualTo("DECREASE");
    }
    @Test void unknownRateCashImpactIsNotReplacedByZeroOrHypotheticalEstimate(){
        var result=RiskDetailPresentation.history(new Recorded(Set.of(MONTH.minusMonths(1)),List.of(change(1L,MONTH.minusMonths(1).atDay(1),Reason.RATE,null))),null,MONTH);
        assertThat(result.changes().getFirst().amountDelta()).isNull();assertThat(result.summary().netMonthlyBurdenDelta()).isNull();
        assertThat(result.summary().rateChangeCount()).isEqualTo(1);
    }
    @Test void observedEmptyWindowAndUnobservedWindowRemainDistinct(){
        assertThat(RiskDetailPresentation.history(new Recorded(Set.of(),List.of()),null,MONTH).summary().netMonthlyBurdenDelta()).isNull();
        assertThat(RiskDetailPresentation.history(new Recorded(Set.of(MONTH.minusMonths(1)),List.of()),null,MONTH).summary().netMonthlyBurdenDelta()).isZero();
        assertThat(RiskDetailPresentation.history(new Recorded(Set.of(MONTH),List.of()),null,MONTH).summary().netMonthlyBurdenDelta()).isNull();
    }
}
