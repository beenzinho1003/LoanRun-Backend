package com.loanrun.budget.dto;

import com.loanrun.common.contract.IncomeType;

public record FinanceProfileResponse(long monthlyIncome, IncomeType incomeType, Integer payday,
    long essentialExpenses, long discretionaryExpenses, long savings, Long emergencyFund,
    long fixedEssentialExpenses, long variableEssentialExpenses) {}
