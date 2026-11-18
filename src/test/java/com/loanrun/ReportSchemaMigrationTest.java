package com.loanrun;

import static org.assertj.core.api.Assertions.*;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@Testcontainers
class ReportSchemaMigrationTest {
    @Container static final PostgreSQLContainer<?> DB=new PostgreSQLContainer<>("postgres:16.16-bookworm");
    static Flyway flyway;static JdbcTemplate jdbc;
    @BeforeAll static void migrate(){
        flyway=Flyway.configure().dataSource(DB.getJdbcUrl(),DB.getUsername(),DB.getPassword()).locations("classpath:db/migration").load();flyway.migrate();
        jdbc=new JdbcTemplate(new DriverManagerDataSource(DB.getJdbcUrl(),DB.getUsername(),DB.getPassword()));
    }
    long user(){return jdbc.queryForObject("INSERT INTO demo_users(session_digest,created_at,updated_at) VALUES (?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP) RETURNING id",
        Long.class,UUID.randomUUID().toString().replace("-","").repeat(2));}
    long job(long user,long revision,String month,String status){return jdbc.queryForObject("""
        INSERT INTO report_jobs(user_id,scope_key,data_revision,basis_month,status,input_payload,created_at,updated_at)
        VALUES (?,'HOUSEHOLD',?,CAST(? AS DATE),?,'{}',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP) RETURNING id
        """,Long.class,user,revision,month,status);}
    @Test void migrationUsesReservedVersionsAndRevalidationDoesNotEraseSavedJobs(){
        var versions=jdbc.queryForList("SELECT version FROM flyway_schema_history WHERE success=TRUE ORDER BY installed_rank",String.class);
        assertThat(versions).contains("1","5");
        assertThat(versions.indexOf("1")).isLessThan(versions.indexOf("5"));
        long id=job(user(),0,"2026-11-01","COMPLETED");
        jdbc.update("UPDATE report_jobs SET result_payload=?,generated_at=CURRENT_TIMESTAMP WHERE id=?","{\"source_type\":\"DEMO\"}",id);
        flyway.validate();assertThat(flyway.migrate().migrationsExecuted).isZero();
        assertThat(jdbc.queryForObject("SELECT result_payload FROM report_jobs WHERE id=?",String.class,id)).isEqualTo("{\"source_type\":\"DEMO\"}");
    }
    @Test void consentRequiresValidCategoriesButAllowsUnknownValuesWithoutConsent(){
        long id=user();jdbc.update("INSERT INTO comparison_settings(user_id) VALUES (?)",id);
        assertThat(jdbc.queryForObject("SELECT consented FROM comparison_settings WHERE user_id=?",Boolean.class,id)).isFalse();
        assertThatThrownBy(()->jdbc.update("UPDATE comparison_settings SET consented=TRUE WHERE user_id=?",id)).isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("UPDATE comparison_settings SET consented=TRUE,age_group='AGE_20S',income_decile=3 WHERE user_id=?",id);
        assertThatThrownBy(()->jdbc.update("UPDATE comparison_settings SET income_decile=6 WHERE user_id=?",id)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(()->jdbc.update("UPDATE comparison_settings SET age_group='AGE_10S' WHERE user_id=?",id)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(()->jdbc.update("INSERT INTO comparison_settings(user_id) VALUES (-1)")).isInstanceOf(DataIntegrityViolationException.class);
    }
    @Test void reportScopeIsUniquePerOwnerRevisionAndMonthAndRejectsInvalidReferences(){
        long id=user();long first=job(id,0,"2026-11-01","PENDING");
        assertThatThrownBy(()->job(id,0,"2026-11-01","RUNNING")).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(job(id,1,"2026-11-01","PENDING")).isNotEqualTo(first);assertThat(job(user(),0,"2026-11-01","PENDING")).isNotEqualTo(first);
        assertThatThrownBy(()->job(id,0,"2026-11-02","PENDING")).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(()->job(id,-1,"2026-12-01","PENDING")).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(()->job(id,0,"2026-12-01","UNKNOWN")).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(()->jdbc.update("INSERT INTO report_attempts(user_id,attempted_at) VALUES (-1,CURRENT_TIMESTAMP)")).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(()->jdbc.update("""
            INSERT INTO strategy_candidates(report_id,strategy_version,code,strategy_type,data_revision,computed_at,allocations_payload,simulation_payload,feasible)
            VALUES (-1,'v1','FAST','FAST',0,CURRENT_TIMESTAMP,'[]','{}',TRUE)
            """)).isInstanceOf(DataIntegrityViolationException.class);
    }
}
