package com.loanrun.budget.repository;

import com.loanrun.budget.domain.MonthlyBudget;
import com.loanrun.budget.dto.BudgetDtos.*;
import com.loanrun.common.contract.IncomeType;
import com.loanrun.common.contract.FinanceModels.*;
import java.sql.Date;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class MonthlyBudgetRepository {
    private final JdbcTemplate jdbc;
    public MonthlyBudgetRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    private final RowMapper<MonthlyBudget> mapper = (rs, i) -> new MonthlyBudget(rs.getLong("id"), rs.getLong("user_id"),
        YearMonth.from(rs.getDate("year_month").toLocalDate()), IncomeType.valueOf(rs.getString("income_type")),
        rs.getLong("expected_income"), rs.getObject("payday", Integer.class), rs.getLong("fixed_essential_expenses"),
        rs.getLong("variable_essential_expenses"), rs.getLong("discretionary_expenses"), rs.getLong("discretionary_limit"),
        rs.getLong("savings"), rs.getObject("emergency_fund", Long.class), rs.getString("source"), rs.getBoolean("expenses_confirmed"),
        rs.getBoolean("income_confirmed"), rs.getBoolean("allocation_overridden"), rs.getLong("reserve_amount"), rs.getLong("carry_in"),
        rs.getObject("carry_out", Long.class), rs.getObject("reserve_settled", Long.class), rs.getString("settlement_status"),
        rs.getTimestamp("updated_at").toInstant());

    public Optional<MonthlyBudget> find(long userId, YearMonth month) {
        return jdbc.query("SELECT * FROM monthly_budgets WHERE user_id=? AND year_month=?", mapper, userId, Date.valueOf(month.atDay(1))).stream().findFirst();
    }
    public List<MonthlyBudget> through(long userId, YearMonth month) {
        return jdbc.query("SELECT * FROM monthly_budgets WHERE user_id=? AND year_month<=? ORDER BY year_month", mapper, userId, Date.valueOf(month.atDay(1)));
    }
    public List<IncomeEntry> entries(long userId, Long budgetId) {
        if (budgetId == null) return List.of();
        return jdbc.query("SELECT * FROM income_entries WHERE user_id=? AND budget_id=? ORDER BY received_date,id",
            (rs, i) -> new IncomeEntry(rs.getLong("id"), rs.getString("entry_type"), rs.getDate("received_date").toLocalDate(), rs.getLong("amount")), userId, budgetId);
    }
    public List<PlannedAmount> overrides(MonthlyBudget budget) {
        if (budget.id() == null) return List.of();
        return jdbc.query("SELECT * FROM monthly_allocations WHERE budget_id=? ORDER BY loan_id",
            (rs,i) -> new PlannedAmount(rs.getLong("loan_id"), AllocationType.valueOf(rs.getString("allocation_type")), rs.getLong("amount"), null), budget.id());
    }
    public void saveSettlement(long userId, YearMonth month, com.loanrun.budget.dto.BudgetDtos.Metrics metrics) {
        jdbc.update("""
            UPDATE monthly_budgets SET carry_in=?,carry_out=?,reserve_settled=?,settlement_status=?
            WHERE user_id=? AND year_month=?
            """,metrics.carryIn(),metrics.carryOut(),metrics.reserveSettled(),metrics.settlementStatus(),userId,Date.valueOf(month.atDay(1)));
    }
}
