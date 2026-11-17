package com.loanrun.budget.domain;

import com.loanrun.budget.dto.FinanceProfileResponse;
import com.loanrun.common.contract.IncomeType;
import java.time.Instant;

public record FinanceProfile(long userId, long monthlyIncome, IncomeType incomeType, Integer payday,
    long fixedEssentialExpenses, long variableEssentialExpenses, long discretionaryExpenses,
    long savings, Long emergencyFund, Instant createdAt, Instant updatedAt) {
    public FinanceProfileResponse response() {
        return new FinanceProfileResponse(monthlyIncome, incomeType, payday,
            Math.addExact(fixedEssentialExpenses, variableEssentialExpenses), discretionaryExpenses,
            savings, emergencyFund, fixedEssentialExpenses, variableEssentialExpenses);
    }
}
