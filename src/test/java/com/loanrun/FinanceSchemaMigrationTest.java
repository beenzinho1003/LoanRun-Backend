package com.loanrun;

import static org.assertj.core.api.Assertions.*;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class FinanceSchemaMigrationTest {
    @Container static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("postgres:16.16-bookworm");

    @Test void upgradesExistingBaselineAndEnforcesOwnershipUniquenessAndFinanceInputConstraints() {
        var source = new DriverManagerDataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword());
        var jdbc = new JdbcTemplate(source);
        Flyway.configure().dataSource(source).target("1").load().migrate();
        long user = jdbc.queryForObject("""
            INSERT INTO demo_users(session_digest,created_at,updated_at)
            VALUES ('schema-fixture',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP) RETURNING id
            """, Long.class);

        var flyway = Flyway.configure().dataSource(source).target("3").load();
        assertThat(flyway.migrate().migrationsExecuted).isGreaterThanOrEqualTo(1);
        assertThat(flyway.info().current().getVersion().toString()).isEqualTo("3");
        flyway.validate();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM demo_users WHERE id=?", Long.class, user)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM information_schema.tables WHERE table_schema='public'
            AND table_name IN ('finance_profiles','monthly_budgets','income_entries','monthly_allocations',
                              'financial_institutions','loan_products')
            """, Long.class)).isEqualTo(6);

        String insert = """
            INSERT INTO finance_profiles(user_id,monthly_income,income_type,payday,fixed_essential_expenses,
                variable_essential_expenses,discretionary_expenses,savings,emergency_fund,created_at,updated_at)
            VALUES (?,?,'REGULAR',?,0,0,0,0,NULL,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
            """;
        assertThatThrownBy(() -> jdbc.update(insert, user + 100, 0, 25))
            .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(insert, user, -1, 25))
            .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(insert, user, 0, 32))
            .isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update(insert, user, 0, 25);
        assertThat(jdbc.queryForObject("SELECT monthly_income FROM finance_profiles WHERE user_id=?", Long.class, user)).isZero();
        assertThat(jdbc.queryForObject("SELECT emergency_fund FROM finance_profiles WHERE user_id=?", Long.class, user)).isNull();
        assertThatThrownBy(() -> jdbc.update(insert, user, 1, 25))
            .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM finance_profiles WHERE user_id=?", Long.class, user)).isEqualTo(1);
    }
}
