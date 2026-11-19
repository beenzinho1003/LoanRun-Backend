package com.loanrun.payment.domain;

import java.time.LocalDate;
import java.util.Objects;

/** Pure regular-due state. Actual extra repayments are never inputs to due fulfillment. */
public final class DueStateCalculator {
    private DueStateCalculator() {}
    public enum Status { UPCOMING, PAID, PARTIAL, UNRECORDED, UNPAID }
    public record State(long allocatedAmount,long paidRequiredAmount,long outstandingAmount,Status status) {}

    public static State calculate(long required,long principal,long interest,long fee,long allocationCount,
        boolean confirmed,LocalDate dueDate,LocalDate today) {
        Objects.requireNonNull(dueDate);Objects.requireNonNull(today);
        if(required<0||principal<0||interest<0||fee<0||allocationCount<0)throw new IllegalArgumentException("Negative due source");
        long allocated=Math.addExact(Math.addExact(principal,interest),fee);
        if(allocationCount==0&&allocated!=0)throw new IllegalArgumentException("Allocation total without original rows");
        long paid=Math.min(required,allocated),outstanding=required-paid;
        Status status;
        if(outstanding==0)status=Status.PAID;
        else if(allocationCount>0)status=Status.PARTIAL;
        else if(!dueDate.isBefore(today))status=Status.UPCOMING;
        else status=confirmed?Status.UNPAID:Status.UNRECORDED;
        return new State(allocated,paid,outstanding,status);
    }
}
