package com.loanrun.payment;

import static org.assertj.core.api.Assertions.*;
import static com.loanrun.payment.domain.DueStateCalculator.Status.*;
import com.loanrun.payment.domain.DueStateCalculator;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class DueStateCalculatorTest {
    final LocalDate today=LocalDate.of(2026,3,31);
    @Test void dueDayIsInclusiveAndOnlyPastUnrecordedDuesBecomeMissedOrUnrecorded() {
        for(boolean confirmed:new boolean[]{false,true})for(LocalDate due:new LocalDate[]{today,today.plusDays(1)})
            assertThat(DueStateCalculator.calculate(100,0,0,0,0,confirmed,due,today).status()).isEqualTo(UPCOMING);
        assertThat(DueStateCalculator.calculate(100,0,0,0,0,false,today.minusDays(1),today).status()).isEqualTo(UNRECORDED);
        assertThat(DueStateCalculator.calculate(100,0,0,0,0,true,today.minusDays(1),today).status()).isEqualTo(UNPAID);
    }
    @Test void actualPartialOrFullFulfillmentOverridesConfirmationAndCalendarDate() {
        for(LocalDate due:new LocalDate[]{today.minusDays(1),today,today.plusDays(1)}) {
            var partial=DueStateCalculator.calculate(100,50,0,0,1,true,due,today);
            assertThat(partial.status()).isEqualTo(PARTIAL);assertThat(partial.paidRequiredAmount()).isEqualTo(50);assertThat(partial.outstandingAmount()).isEqualTo(50);
            assertThat(DueStateCalculator.calculate(100,100,0,0,1,true,due,today).status()).isEqualTo(PAID);
        }
    }
    @Test void regularFeesAndInterestCountTowardTheObligationWithoutBecomingPrincipal() {
        var state=DueStateCalculator.calculate(100,60,30,10,2,false,today,today);
        assertThat(state.status()).isEqualTo(PAID);assertThat(state.allocatedAmount()).isEqualTo(100);assertThat(state.outstandingAmount()).isZero();
        assertThat(DueStateCalculator.calculate(100,0,0,10,1,false,today,today).status()).isEqualTo(PARTIAL);
    }
    @Test void explicitZeroObligationIsPaidAndOverAllocationPreservesRawAmountButCapsRequiredPortion() {
        var zero=DueStateCalculator.calculate(0,0,0,0,0,true,today,today);
        assertThat(zero.status()).isEqualTo(PAID);assertThat(zero.paidRequiredAmount()).isZero();
        var over=DueStateCalculator.calculate(100,120,20,10,3,false,today,today);
        assertThat(over.allocatedAmount()).isEqualTo(150);assertThat(over.paidRequiredAmount()).isEqualTo(100);assertThat(over.outstandingAmount()).isZero();
    }
    @Test void savedZeroAllocationDoesNotMasqueradeAsAnUnrecordedDue() {
        var state=DueStateCalculator.calculate(100,0,0,0,1,true,today.minusDays(1),today);
        assertThat(state.status()).isEqualTo(PARTIAL);assertThat(state.paidRequiredAmount()).isZero();assertThat(state.outstandingAmount()).isEqualTo(100);
    }
    @Test void inconsistentNegativeOrUnrepresentableSourcesAreNotCoercedIntoStates() {
        assertThatThrownBy(()->DueStateCalculator.calculate(-1,0,0,0,0,false,today,today)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->DueStateCalculator.calculate(100,1,0,0,0,false,today,today)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->DueStateCalculator.calculate(100,Long.MAX_VALUE,1,0,2,false,today,today)).isInstanceOf(ArithmeticException.class);
    }
}
