package com.loanrun;

import static org.assertj.core.api.Assertions.*;
import com.loanrun.common.contract.FinanceModels.HistoryMonth;
import com.loanrun.report.service.ReportStatistics;
import java.time.YearMonth;
import java.util.*;
import org.junit.jupiter.api.Test;

class ReportStatisticsTest {
    final YearMonth basis=YearMonth.of(2026,11);
    HistoryMonth month(int ago,long opening,long registered,long regular,long extra,long interest,boolean observed){
        return new HistoryMonth(basis.minusMonths(ago),opening,registered,regular,extra,interest,999,1,50,20,observed);
    }
    @Test void usesOnlyObservedCompletedMonthsInTheSixMonthWindowWithoutFillingGaps(){
        var result=ReportStatistics.calculate(List.of(month(0,1000,0,999,0,0,true),month(1,1000,0,100,0,0,true),month(2,1000,0,900,0,0,false),
            month(6,1000,0,200,0,0,true),month(7,1000,0,999,0,0,true),month(-1,1000,0,999,0,0,true)),Map.of(),basis);
        assertThat(result.observedMonthCount()).isEqualTo(2);assertThat(result.principalRepaid()).isEqualTo(300);
        assertThat(result.monthlyPrincipalRepaymentRate()).isEqualByComparingTo("15");assertThat(result.principalReductionPct()).isEqualByComparingTo("30");
        assertThat(result.averageDebtServiceRatio()).isNull();
    }
    @Test void includesMidMonthRegistrationAndActualExtraPrincipalOnceButSeparatesFeesAndSavings(){
        var first=new HistoryMonth(basis.minusMonths(2),1000,1000,150,50,30,999,1,50,20,true);
        var last=new HistoryMonth(basis.minusMonths(1),1800,200,80,20,20,999,2,70,30,true);
        var result=ReportStatistics.calculate(List.of(last,first),Map.of(first.month(),1000L,last.month(),1000L),basis);
        assertThat(result.monthlyPrincipalRepaymentRate()).isEqualByComparingTo("7.50");assertThat(result.principalRepaid()).isEqualTo(300);
        assertThat(result.principalReductionPct()).isEqualByComparingTo("13.64");assertThat(result.averageDebtServiceRatio()).isEqualByComparingTo("17.50");
        assertThat(result.extraCount()).isEqualTo(3);assertThat(result.savingsDeposited()).isEqualTo(120);assertThat(result.savingsUsed()).isEqualTo(50);
    }
    @Test void confirmedZeroIsKnownButMissingAndZeroIncomeDoNotCreateDebtRatios(){
        var first=new HistoryMonth(basis.minusMonths(2),1000,0,0,0,0,0,0,0,0,true);
        var last=new HistoryMonth(basis.minusMonths(1),1000,0,0,0,0,0,0,0,0,true);
        var result=ReportStatistics.calculate(List.of(first,last),Map.of(first.month(),0L),basis);
        assertThat(result.principalRepaid()).isZero();assertThat(result.monthlyPrincipalRepaymentRate()).isEqualByComparingTo("0");
        assertThat(result.savingsDeposited()).isZero();assertThat(result.extraCount()).isZero();assertThat(result.averageDebtServiceRatio()).isNull();
        assertThat(ReportStatistics.calculate(List.of(first,last),Map.of(last.month(),1000L),basis).averageDebtServiceRatio()).isEqualByComparingTo("0");
    }
    @Test void emptyHistoryAndZeroBalanceDenominatorsDoNotInventRatesOrActualSums(){
        var empty=ReportStatistics.calculate(List.of(),Map.of(),basis);assertThat(empty.principalRepaid()).isNull();assertThat(empty.observedMonthCount()).isZero();assertThat(empty.savingsDeposited()).isNull();
        var zero=new HistoryMonth(basis.minusMonths(1),0,0,0,0,0,0,0,0,0,true);
        var result=ReportStatistics.calculate(List.of(zero),Map.of(),basis);assertThat(result.principalRepaid()).isZero();assertThat(result.monthlyPrincipalRepaymentRate()).isNull();
        assertThat(result.principalReductionPct()).isNull();assertThat(result.dataStatus()).isEqualTo("INSUFFICIENT");
    }
    @Test void duplicateMonthCannotDoubleCountConfirmedPayments(){
        var row=month(1,1000,0,100,0,0,true);
        assertThatThrownBy(()->ReportStatistics.calculate(List.of(row,row),Map.of(),basis)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void overflowFailsInsteadOfWrappingActualPrincipalIntoANegativeAmount(){
        var row=month(1,Long.MAX_VALUE,0,Long.MAX_VALUE,1,0,true);
        assertThatThrownBy(()->ReportStatistics.calculate(List.of(row),Map.of(),basis)).isInstanceOf(ArithmeticException.class);
    }
}
