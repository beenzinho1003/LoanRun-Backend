package com.loanrun;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import com.loanrun.common.contract.*;
import com.loanrun.common.contract.FinanceModels.*;
import com.loanrun.common.error.ApiException;
import com.loanrun.report.domain.ReportJob;
import com.loanrun.report.repository.ReportJobRepository;
import com.loanrun.report.service.ReportJobStore;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@Testcontainers
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties="loanrun.reports.worker-enabled=false")
@Import(ReportJobStoreIntegrationTest.TimeConfig.class)
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class ReportJobStoreIntegrationTest {
    @Container static final PostgreSQLContainer<?> DB=new PostgreSQLContainer<>("postgres:16.16-bookworm");
    @DynamicPropertySource static void database(DynamicPropertyRegistry r){r.add("spring.datasource.url",DB::getJdbcUrl);r.add("spring.datasource.username",DB::getUsername);r.add("spring.datasource.password",DB::getPassword);}
    static class MutableClock extends Clock {
        final AtomicReference<Instant> now=new AtomicReference<>(Instant.parse("2026-11-17T03:00:00Z"));
        public ZoneId getZone(){return ZoneId.of("Asia/Seoul");}public Clock withZone(ZoneId zone){return Clock.fixed(instant(),zone);}public Instant instant(){return now.get();}
    }
    @TestConfiguration static class TimeConfig {@Bean @Primary MutableClock reportTestClock(){return new MutableClock();}}
    @Autowired MutableClock clock;@Autowired ReportJobRepository jobs;@Autowired ReportJobStore store;@Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate transactions;@Autowired UserMutationLock locks;@Autowired RevisionPort revisions;@Autowired ObjectMapper mapper;
    final YearMonth month=YearMonth.of(2026,11);
    @BeforeEach void time(){clock.now.set(Instant.parse("2026-11-17T03:00:00Z"));}
    long user(){return jdbc.queryForObject("INSERT INTO demo_users(session_digest,created_at,updated_at) VALUES (?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP) RETURNING id",Long.class,UUID.randomUUID().toString().replace("-","").repeat(2));}
    ReportJob create(long user){return transactions.execute(s->{locks.lock(user);return jobs.create(user,null,0,month,"{}",clock.instant());});}
    StrategyCandidate candidate(ReportJob job){return new StrategyCandidate(job.id(),"v1","FAST","MULTI",job.revision(),clock.instant(),List.of(),
        new SimulationResult(List.of(),month.plusYears(1).atDay(25),1000L,0L,0L,1000L,false,"NONE",List.of()),true,List.of());}
    boolean complete(ReportJobStore.Lease lease,List<StrategyCandidate> candidates){return store.complete(lease,mapper.createObjectNode().put("summary","확정 결과 설명"),mapper.createObjectNode(),candidates,"FALLBACK");}
    @Test void concurrentClaimsRunOnlyOnceAndOwnedQueriesDoNotExposeOtherUsers()throws Exception{
        long user=user();var job=create(user);List<Optional<ReportJobStore.Lease>> claims;
        try(var executor=Executors.newFixedThreadPool(2)){
            var start=new CountDownLatch(1);Callable<Optional<ReportJobStore.Lease>> claim=()->{start.await();return store.start(job.id());};
            var a=executor.submit(claim);var b=executor.submit(claim);start.countDown();claims=List.of(a.get(20,TimeUnit.SECONDS),b.get(20,TimeUnit.SECONDS));
        }
        assertThat(claims.stream().filter(Optional::isPresent).count()).isEqualTo(1);assertThat(jobs.owned(user,job.id()).status()).isEqualTo(ReportJob.Status.RUNNING);
        assertThatThrownBy(()->jobs.owned(user(),job.id())).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code()).isEqualTo("REPORT_NOT_FOUND"));
        assertThat(revisions.current(user)).isZero();
    }
    @Test void failedRetryWaitsSixtySecondsAndOldLeaseCannotOverwriteNewExecution()throws Exception{
        long user=user();var job=create(user);var first=store.start(job.id()).orElseThrow();assertThat(store.fail(first,"SERVICE_UNAVAILABLE")).isTrue();
        assertThat(jobs.owned(user,job.id()).nextRetryAt()).isEqualTo(clock.instant().plusSeconds(60));
        Boolean early=transactions.execute(s->{locks.lock(user);return jobs.requeueFailed(user,job.id(),"{}",clock.instant());});assertThat(early).isFalse();
        clock.now.updateAndGet(t->t.plusSeconds(60));Boolean allowed=transactions.execute(s->{locks.lock(user);return jobs.requeueFailed(user,job.id(),"{}",clock.instant());});assertThat(allowed).isTrue();
        clock.now.set(first.job().updatedAt()); // A clock adjustment must not recreate an older execution's claim token.
        var second=store.start(job.id()).orElseThrow();assertThat(second.job().updatedAt()).isAfter(first.job().updatedAt());
        assertThat(complete(first,List.of())).isFalse();assertThat(store.fail(first,"REPORT_GENERATION_TIMEOUT")).isFalse();
        assertThat(complete(second,List.of(candidate(job)))).isTrue();assertThat(jobs.owned(user,job.id()).status()).isEqualTo(ReportJob.Status.COMPLETED);
        assertThat(revisions.current(user)).isZero();
    }
    @Test void resultAndCandidatesRollBackTogetherWhenAnyCandidateInsertFails(){
        long user=user();var job=create(user);var lease=store.start(job.id()).orElseThrow();var c=candidate(job);
        assertThatThrownBy(()->complete(lease,List.of(c,c))).isInstanceOf(DataIntegrityViolationException.class);
        var unchanged=jobs.owned(user,job.id());assertThat(unchanged.status()).isEqualTo(ReportJob.Status.RUNNING);assertThat(unchanged.resultPayload()).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM strategy_candidates WHERE report_id=?",Long.class,job.id())).isZero();
        assertThat(complete(lease,List.of(c))).isTrue();assertThat(jdbc.queryForObject("SELECT count(*) FROM strategy_candidates WHERE report_id=?",Long.class,job.id())).isEqualTo(1);
    }
    @Test void completionDerivesAuthoritativeMetadataAndMarksChangedRevisionOrMonthStale()throws Exception{
        long user=user();var job=create(user);var lease=store.start(job.id()).orElseThrow();
        transactions.executeWithoutResult(s->{locks.lock(user);revisions.bump(user,"TEST_SOURCE_CHANGED");});
        var supplied=mapper.createObjectNode().put("report_id",-1).put("stale",false);assertThat(store.complete(lease,supplied,null,List.of(),"FALLBACK")).isTrue();
        var payload=mapper.readTree(jobs.owned(user,job.id()).resultPayload());assertThat(payload.path("report_id").longValue()).isEqualTo(job.id());assertThat(payload.path("stale").asBoolean()).isTrue();
        assertThat(payload.path("generated_at").asText()).endsWith("+09:00");assertThat(supplied.path("report_id").longValue()).isEqualTo(-1);
        long other=user();var old=create(other);var oldLease=store.start(old.id()).orElseThrow();clock.now.set(Instant.parse("2026-12-01T00:00:00Z"));
        assertThat(complete(oldLease,List.of())).isTrue();assertThat(mapper.readTree(jobs.owned(other,old.id()).resultPayload()).path("stale").asBoolean()).isTrue();
    }
}
