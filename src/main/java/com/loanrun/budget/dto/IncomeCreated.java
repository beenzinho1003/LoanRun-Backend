package com.loanrun.budget.dto;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.loanrun.budget.dto.BudgetDtos.*;

public record IncomeCreated(IncomeEntry entry, String yearMonth, Long monthlyIncome,
    boolean allocationNeedsReview, @JsonUnwrapped Metrics metrics) {}
