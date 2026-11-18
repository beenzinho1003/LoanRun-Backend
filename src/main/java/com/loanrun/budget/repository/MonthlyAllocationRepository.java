package com.loanrun.budget.repository;

import com.loanrun.budget.domain.MonthlyBudget;
import com.loanrun.common.contract.FinanceModels.PlannedAmount;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class MonthlyAllocationRepository {
    private final JdbcTemplate jdbc;
    public MonthlyAllocationRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public void replace(MonthlyBudget budget, List<PlannedAmount> amounts, long reserve, Instant now) {
        jdbc.update("DELETE FROM monthly_allocations WHERE budget_id=?",budget.id());
        for(var p:amounts) jdbc.update("INSERT INTO monthly_allocations(budget_id,loan_id,allocation_type,amount) VALUES (?,?,?,?)",
            budget.id(),p.loanId(),p.allocationType().name(),p.cashAmount());
        jdbc.update("UPDATE monthly_budgets SET reserve_amount=?,allocation_overridden=TRUE,updated_at=? WHERE id=? AND user_id=?",
            reserve,Timestamp.from(now),budget.id(),budget.userId());
    }
}
