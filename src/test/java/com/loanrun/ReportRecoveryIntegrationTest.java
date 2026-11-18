package com.loanrun;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loanrun.common.contract.*;
import com.loanrun.report.domain.ReportJob;
import com.loanrun.report.repository.ReportJobRepository;
import com.loanrun.report.service.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@Testcontainers
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"loanrun.reports.worker-enabled=true","loanrun.reports.recovery-interval-ms=3600000"})
@Import(ReportJobStoreIntegrationTest.TimeConfig.class)
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class ReportRecoveryIntegrationTest {
    @Container static final PostgreSQLContainer<?> DB=new PostgreSQLContainer<>("postgres:16.16-bookworm");
    @DynamicPropertySource static void database(DynamicPropertyRegistry r){r.add("spring.datasource.url",DB::getJdbcUrl);r.add("spring.datasource.username",DB::getUsername);r.add("spring.datasource.password",DB::getPassword);}
    @Autowired ReportJobRepository jobs;@Autowired ReportJobStore store;@Autowired ReportRecovery recovery;@Autowired UserMutationLock locks;
    @Autowired RevisionPort revisions;@Autowired TransactionTemplate transactions;@Autowired JdbcTemplate jdbc;@Autowired ObjectMapper mapper;
    @Autowired ReportJobStoreIntegrationTest.MutableClock clock;
    @BeforeEach void time(){clock.now.set(Instant.parse("2026-11-17T03:00:00Z"));}
    long user(){return jdbc.queryForObject("INSERT INTO demo_users(session_digest,created_at,updated_at) VALUES (?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP) RETURNING id",Long.class,UUID.randomUUID().toString().replace("-","").repeat(2));}
    ReportJob create(long user){return transactions.execute(s->{locks.lock(user);return jobs.create(user,null,0,YearMonth.of(2026,11),"{}",clock.instant());});}
    @Test void strandedPendingAndRunningFailOnceWithoutGeneratingOrRetryingReports(){
        long a=user(),b=user();var pending=create(a);var running=create(b);store.start(running.id()).orElseThrow();
        assertThat(recovery.recoverOnce()).isZero();clock.now.updateAndGet(t->t.plusSeconds(601));assertThat(recovery.recoverOnce()).isEqualTo(2);
        for(var job:List.of(jobs.owned(a,pending.id()),jobs.owned(b,running.id()))){assertThat(job.status()).isEqualTo(ReportJob.Status.FAILED);assertThat(job.errorCode()).isEqualTo("REPORT_GENERATION_TIMEOUT");assertThat(job.resultPayload()).isNull();assertThat(job.nextRetryAt()).isEqualTo(clock.instant().plusSeconds(60));}
        assertThat(recovery.recoverOnce()).isZero();assertThat(revisions.current(a)).isZero();assertThat(revisions.current(b)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM report_jobs WHERE user_id IN (?,?)",Long.class,a,b)).isEqualTo(2);
    }
    @Test void staleScanCannotFailAFreshClaimOrCompletedJob(){
        long user=user();var pending=create(user);clock.now.updateAndGet(t->t.plusSeconds(601));var cutoff=clock.instant().minusSeconds(600);
        var observed=jobs.expiredBefore(cutoff,100).getFirst();var lease=store.start(pending.id()).orElseThrow();
        assertThat(store.expire(observed,cutoff)).isFalse();assertThat(jobs.owned(user,pending.id()).status()).isEqualTo(ReportJob.Status.RUNNING);
        assertThat(store.complete(lease,mapper.createObjectNode(),null,List.of(),"FALLBACK")).isTrue();assertThat(store.expire(observed,cutoff)).isFalse();
        assertThat(jobs.owned(user,pending.id()).status()).isEqualTo(ReportJob.Status.COMPLETED);
    }
}
