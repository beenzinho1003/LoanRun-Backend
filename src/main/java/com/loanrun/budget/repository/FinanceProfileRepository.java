package com.loanrun.budget.repository;

import com.loanrun.budget.domain.FinanceProfile;
import com.loanrun.common.contract.IncomeType;
import java.sql.Timestamp;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class FinanceProfileRepository {
    private final JdbcTemplate jdbc;
    public FinanceProfileRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Optional<FinanceProfile> find(long userId) {
        return jdbc.query("SELECT * FROM finance_profiles WHERE user_id=?", (rs, i) -> new FinanceProfile(
            userId, rs.getLong("monthly_income"), IncomeType.valueOf(rs.getString("income_type")),
            rs.getObject("payday", Integer.class), rs.getLong("fixed_essential_expenses"), rs.getLong("variable_essential_expenses"),
            rs.getLong("discretionary_expenses"), rs.getLong("savings"), rs.getObject("emergency_fund", Long.class),
            rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant()), userId).stream().findFirst();
    }

    public void save(FinanceProfile profile) {
        jdbc.update("""
            INSERT INTO finance_profiles(user_id,monthly_income,income_type,payday,fixed_essential_expenses,
            variable_essential_expenses,discretionary_expenses,savings,emergency_fund,created_at,updated_at)
            VALUES (?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(user_id) DO UPDATE SET monthly_income=excluded.monthly_income,
            income_type=excluded.income_type,payday=excluded.payday,fixed_essential_expenses=excluded.fixed_essential_expenses,
            variable_essential_expenses=excluded.variable_essential_expenses,discretionary_expenses=excluded.discretionary_expenses,
            savings=excluded.savings,emergency_fund=excluded.emergency_fund,updated_at=excluded.updated_at
            """, profile.userId(), profile.monthlyIncome(), profile.incomeType().name(), profile.payday(),
            profile.fixedEssentialExpenses(), profile.variableEssentialExpenses(), profile.discretionaryExpenses(),
            profile.savings(), profile.emergencyFund(), Timestamp.from(profile.createdAt()), Timestamp.from(profile.updatedAt()));
    }
}
