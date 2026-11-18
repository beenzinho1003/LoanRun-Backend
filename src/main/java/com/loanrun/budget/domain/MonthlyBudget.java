package com.loanrun.budget.domain;

import com.loanrun.common.contract.IncomeType;
import java.time.Instant;
import java.time.YearMonth;

public record MonthlyBudget(Long id, long userId, YearMonth month, IncomeType incomeType, Long expectedIncome,
    Integer payday, Long fixedEssentialExpenses, Long variableEssentialExpenses, Long discretionaryExpenses,
    Long discretionaryLimit, Long savings, Long emergencyFund, String source, boolean expensesConfirmed,
    boolean incomeConfirmed, boolean allocationOverridden, long reserveAmount, long carryIn,
    Long carryOut, Long reserveSettled, String settlementStatus, Instant updatedAt) {
    public static MonthlyBudget forecast(FinanceProfile p, YearMonth month) {
        return new MonthlyBudget(null, p.userId(), month, p.incomeType(), p.monthlyIncome(), p.payday(),
            p.fixedEssentialExpenses(), p.variableEssentialExpenses(), p.discretionaryExpenses(), p.discretionaryExpenses(),
            p.savings(), p.emergencyFund(), "DEFAULT", false, false, false, 0, 0, null, null, "OPEN", null);
    }
}
