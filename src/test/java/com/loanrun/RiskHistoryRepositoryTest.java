package com.loanrun;

import static org.assertj.core.api.Assertions.*;
import com.loanrun.common.error.ApiException;
import com.loanrun.risk.domain.RiskHistoryModels.*;
import com.loanrun.risk.repository.RiskHistoryRepository;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@Testcontainers
@SpringBootTest
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class RiskHistoryRepositoryTest {
    @Container static final PostgreSQLContainer<?> DB=new PostgreSQLContainer<>("postgres:16.16-bookworm");
    @DynamicPropertySource static void database(DynamicPropertyRegistry r){r.add("spring.datasource.url",DB::getJdbcUrl);r.add("spring.datasource.username",DB::getUsername);r.add("spring.datasource.password",DB::getPassword);}
    @Autowired JdbcTemplate jdbc;@Autowired RiskHistoryRepository history;@Autowired TransactionTemplate tx;
    final YearMonth month=YearMonth.of(2026,10);final Instant now=Instant.parse("2026-11-19T03:00:00Z");
    @BeforeEach void schema()throws Exception{if(jdbc.queryForObject("SELECT to_regclass('finance_change_events')",String.class)==null)jdbc.execute(Files.readString(Path.of("docs/sql/risk_history_proposal.sql")));}
    long user(){return jdbc.queryForObject("INSERT INTO demo_users(session_digest,name,created_at,updated_at) VALUES (?,'가상',?,?) RETURNING id",Long.class,UUID.randomUUID().toString(),java.sql.Timestamp.from(now),java.sql.Timestamp.from(now));}
    Change change(long user,long revision,Long before,Long after){return new Change(user,revision,Origin.MONTHLY_BUDGET,month,month.atEndOfMonth(),Kind.EXPENSE,before,after,before!=null&&after!=null,now);}
    Assessment assessment(long user,long revision,String household){return new Assessment(user,month,revision,month.atEndOfMonth(),"a".repeat(64),household,"{\"7\":{\"score\":null}}",now);}
    @Test void changeAndFinancialMutationRollBackTogetherAndUnknownIsDistinctFromZero(){
        long id=user();assertThatThrownBy(()->tx.executeWithoutResult(s->{jdbc.update("UPDATE demo_users SET revision=1 WHERE id=?",id);
            history.append(change(id,1,100L,0L));throw new IllegalStateException("rollback");})).isInstanceOf(IllegalStateException.class);
        assertThat(history.changes(id,month.atDay(1),month.atEndOfMonth())).isEmpty();
        assertThat(jdbc.queryForObject("SELECT revision FROM demo_users WHERE id=?",Long.class,id)).isZero();
        tx.executeWithoutResult(s->{history.append(change(id,1,100L,0L));history.append(change(id,2,null,0L));});
        var rows=history.changes(id,month.atDay(1),month.atEndOfMonth());assertThat(rows).hasSize(2);
        assertThat(rows.get(0).afterAmount()).isZero();assertThat(rows.get(1).beforeAmount()).isNull();assertThat(rows.get(1).cashImpactKnown()).isFalse();
    }
    @Test void identicalRetryReusesOriginalAndConflictingPayloadIs409(){
        long id=user();var first=change(id,1,100L,200L);tx.executeWithoutResult(s->history.append(first));
        tx.executeWithoutResult(s->history.append(new Change(id,1,first.origin(),month,first.eventDate(),first.kind(),100L,200L,true,now.plusSeconds(60))));
        assertThat(history.changes(id,month.atDay(1),month.atEndOfMonth())).containsExactly(first);
        assertThatThrownBy(()->tx.executeWithoutResult(s->history.append(change(id,1,100L,300L)))).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code()).isEqualTo("RISK_HISTORY_CONFLICT"));
    }
    @Test void frozenVersionsRemainImmutableAndLatestAndObservedMonthsAreOwnerScoped(){
        long id=user(),other=user();tx.executeWithoutResult(s->{history.append(assessment(id,1,"{\"score\":null}"));history.append(assessment(id,2,"{\"score\":0}"));});
        assertThat(history.latest(id,month).orElseThrow().revision()).isEqualTo(2);
        assertThat(history.latest(other,month)).isEmpty();assertThat(history.confirmedMonths(other,month.minusMonths(1),month)).isEmpty();
        assertThat(history.confirmedMonths(id,month.minusMonths(1),month)).containsExactly(month);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM risk_assessment_versions WHERE user_id=?",Long.class,id)).isEqualTo(2);
        assertThatThrownBy(()->jdbc.update("UPDATE risk_assessment_versions SET household_payload='{}' WHERE user_id=?",id)).hasMessageContaining("append-only");
        assertThatThrownBy(()->jdbc.update("DELETE FROM risk_assessment_versions WHERE user_id=?",id)).hasMessageContaining("append-only");
    }
    @Test void jsonKeyOrderDoesNotCreateAFalseConflictButChangedResultDoes(){
        long id=user();tx.executeWithoutResult(s->history.append(assessment(id,1,"{\"score\":0,\"level\":\"STABLE\"}")));
        tx.executeWithoutResult(s->history.append(assessment(id,1,"{\"level\":\"STABLE\",\"score\":0}")));
        assertThatThrownBy(()->tx.executeWithoutResult(s->history.append(assessment(id,1,"{\"score\":10}")))).isInstanceOf(ApiException.class);
    }
    @Test void concurrentIdenticalAppendsProduceOneVersion()throws Exception{
        long id=user();try(var pool=Executors.newFixedThreadPool(2)){
            var first=pool.submit(()->tx.executeWithoutResult(s->history.append(assessment(id,1,"{\"score\":0}"))));
            var second=pool.submit(()->tx.executeWithoutResult(s->history.append(assessment(id,1,"{\"score\":0}"))));
            first.get(10,TimeUnit.SECONDS);second.get(10,TimeUnit.SECONDS);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM risk_assessment_versions WHERE user_id=?",Long.class,id)).isEqualTo(1);
    }
    @Test void databaseRejectsInvalidReferencesAmountsMonthEndAndMutationOfEvents(){
        long id=user();assertThatThrownBy(()->tx.executeWithoutResult(s->history.append(change(-1,1,1L,2L)))).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(()->tx.executeWithoutResult(s->history.append(change(id,1,-1L,2L)))).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        var value=assessment(id,1,"{}");assertThatThrownBy(()->tx.executeWithoutResult(s->history.append(new Assessment(id,month,1,month.atDay(1),value.inputHash(),"{}","{}",now)))).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        tx.executeWithoutResult(s->history.append(change(id,1,1L,2L)));
        assertThatThrownBy(()->jdbc.update("DELETE FROM finance_change_events WHERE user_id=?",id)).hasMessageContaining("append-only");
    }
    @Test void invalidationIsTransactionalOwnerScopedAndOlderCompletionCannotClearNewerRequest(){
        long id=user(),other=user();
        assertThatThrownBy(()->tx.executeWithoutResult(s->{history.request(id,month,1);throw new IllegalStateException("rollback");})).isInstanceOf(IllegalStateException.class);
        assertThat(history.pending(id,month)).isFalse();
        tx.executeWithoutResult(s->history.request(id,month,2));assertThat(history.pending(id,month)).isTrue();assertThat(history.pending(other,month)).isFalse();
        tx.executeWithoutResult(s->history.finishRequest(id,month,1));assertThat(history.pending(id,month)).isTrue();
        tx.executeWithoutResult(s->history.finishRequest(id,month,2));assertThat(history.pending(id,month)).isFalse();
    }
}
