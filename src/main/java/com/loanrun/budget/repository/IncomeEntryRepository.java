package com.loanrun.budget.repository;

import com.loanrun.budget.dto.BudgetDtos.IncomeEntry;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class IncomeEntryRepository {
    private final JdbcTemplate jdbc;
    public IncomeEntryRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public IncomeEntry insert(long userId, long budgetId, String type, LocalDate date, long amount, Instant now) {
        long id = jdbc.queryForObject("""
            INSERT INTO income_entries(user_id,budget_id,entry_type,received_date,amount,created_at)
            VALUES (?,?,?,?,?,?) RETURNING id
            """, Long.class, userId, budgetId, type, Date.valueOf(date), amount, Timestamp.from(now));
        return new IncomeEntry(id, type, date, amount);
    }
    public void update(long userId, long budgetId, long id, String type, LocalDate date, long amount) {
        jdbc.update("UPDATE income_entries SET entry_type=?,received_date=?,amount=? WHERE user_id=? AND budget_id=? AND id=?",
            type, Date.valueOf(date), amount, userId, budgetId, id);
    }
    public void delete(long userId, long budgetId, long id) {
        jdbc.update("DELETE FROM income_entries WHERE user_id=? AND budget_id=? AND id=?", userId, budgetId, id);
    }
}
