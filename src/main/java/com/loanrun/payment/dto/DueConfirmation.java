package com.loanrun.payment.dto;

import java.time.LocalDate;

public record DueConfirmation(long dueItemId, long loanId, LocalDate dueDate, long requiredAmount,
    long paidRequiredAmount, long outstandingAmount, boolean unpaidConfirmed, String status,
    String displayStatus, boolean reportStale) {}
