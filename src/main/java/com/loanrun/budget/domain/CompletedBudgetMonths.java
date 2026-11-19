package com.loanrun.budget.domain;

import java.time.YearMonth;
import java.util.List;

/** Derived maintenance signal, dispatched only after the financial transaction commits. */
public record CompletedBudgetMonths(long userId,List<YearMonth> months){
    public CompletedBudgetMonths {months=months.stream().distinct().sorted().toList();}
}
