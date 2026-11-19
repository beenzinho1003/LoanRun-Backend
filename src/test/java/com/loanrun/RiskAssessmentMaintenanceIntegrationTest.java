package com.loanrun;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loanrun.budget.domain.CompletedBudgetMonths;
import com.loanrun.budget.service.*;
import com.loanrun.common.contract.*;
import com.loanrun.common.contract.FinanceModels.*;
import com.loanrun.common.error.ApiException;
import com.loanrun.risk.repository.RiskHistoryRepository;
import com.loanrun.risk.service.*;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.*;
import org.springframework.transaction.support.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@Testcontainers
@SpringBootTest(properties="loanrun.risk-history.enabled=true")
@Import(BootstrapIntegrationTest.TestConfig.class)
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class RiskAssessmentMaintenanceIntegrationTest {
    @Container static final PostgreSQLContainer<?> DB=new PostgreSQLContainer<>("postgres:16.16-bookworm");
    @DynamicPropertySource static void database(DynamicPropertyRegistry r){r.add("spring.datasource.url",DB::getJdbcUrl);r.add("spring.datasource.username",DB::getUsername);r.add("spring.datasource.password",DB::getPassword);}
    @Autowired JdbcTemplate jdbc;@Autowired RevisionPort revisions;@Autowired TransactionTemplate tx;@Autowired ObjectMapper json;
    @Autowired FinanceProfileService profiles;@Autowired MonthlyBudgetReader budgets;@Autowired RiskAssessmentMaintenance maintenance;
    @Autowired RiskHistoryRepository history;@Autowired RiskHistorySourceAdapter source;@Autowired ApplicationEventPublisher events;
    @MockitoBean LoanQueryPort loans;@MockitoBean PlanQueryPort plans;@MockitoBean LoanEventQueryPort rates;
    @MockitoSpyBean RepaymentEngine engine;
    static final YearMonth MONTH=YearMonth.of(2026,10);
    LoanTerms loan(long user,long id,LocalDate asOf,LoanStatus status){return new LoanTerms(id,user,"가상",LoanType.CREDIT,500000,status==LoanStatus.PAID?0:500000,
        asOf,BigDecimal.ZERO,RateType.FIXED,RepaymentMethod.EQUAL_PRINCIPAL,null,null,asOf.plusMonths(1).withDayOfMonth(25),25,asOf.plusYears(1),
        100000L,null,100000L,PrepaymentPolicy.KEEP_PAYMENT,FeeMethod.NONE,BigDecimal.ZERO,null,null,0,0,status,null);}
    List<LoanTerms> owned(long user){return List.of(loan(user,1,MONTH.atEndOfMonth(),LoanStatus.ACTIVE),loan(user,7,MONTH.atEndOfMonth(),LoanStatus.PAID));}
    @BeforeEach void setup()throws Exception{
        if(jdbc.queryForObject("SELECT to_regclass('finance_change_events')",String.class)==null)jdbc.execute(Files.readString(Path.of("docs/sql/risk_history_proposal.sql")));
        when(loans.activeLoans(anyLong(),any())).thenAnswer(i->List.of(loan(i.getArgument(0),1,i.getArgument(1),LoanStatus.ACTIVE)));
        when(loans.historicalLoans(anyLong(),any())).thenAnswer(i->{assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(((DateRange)i.getArgument(1)).endInclusive()).isEqualTo(MONTH.atEndOfMonth());return owned(i.getArgument(0));});
        when(loans.dueSummary(anyLong(),any())).thenAnswer(i->new DueSummary(i.getArgument(1),100000,0,100000,0,0,null,1,1,0,0,0));
        when(loans.paymentSummary(anyLong(),any())).thenAnswer(i->new PaymentSummary(i.getArgument(1),100000,0,0,0,100000,0,0,true,0,0,Map.of()));
        when(loans.savingSummary(anyLong(),any())).thenAnswer(i->new SavingSummary(i.getArgument(1),0,0,0,0,true,0,Map.of()));
        when(plans.currentPlans(anyLong(),any())).thenReturn(List.of(new PlanSnapshot(1,true,MONTH.plusYears(1).atEndOfMonth(),null,0,0,0,"EXTRA_AMOUNT",null,MONTH.atDay(1),1)));
        when(plans.plannedAllocations(anyLong(),any())).thenReturn(List.of());when(rates.rateChanges(anyLong(),any())).thenReturn(List.of());
        doAnswer(i->{assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(((SimulationInput)i.getArgument(0)).asOf()).isEqualTo(MONTH.atEndOfMonth());return i.callRealMethod();}).when(engine).simulate(any());
    }
    long initialize(boolean confirmed)throws Exception{
        long id=jdbc.queryForObject("INSERT INTO demo_users(session_digest,name,created_at,updated_at) VALUES (?,'가상',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP) RETURNING id",Long.class,UUID.randomUUID().toString());
        profiles.save(id,json.readTree("{\"monthly_income\":1000000,\"income_type\":\"REGULAR\",\"payday\":1,\"fixed_essential_expenses\":100000,\"variable_essential_expenses\":100000,\"discretionary_expenses\":100000,\"savings\":100000}"));
        jdbc.update("""
            INSERT INTO monthly_budgets(user_id,year_month,income_type,expected_income,payday,fixed_essential_expenses,variable_essential_expenses,
                discretionary_expenses,discretionary_limit,savings,source,expenses_confirmed,income_confirmed,created_at,updated_at)
            VALUES (?,'2026-10-01','REGULAR',1000000,1,100000,100000,100000,100000,100000,'INPUT',?,TRUE,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
            """,id,confirmed);return id;
    }
    @Test void actualLazySettlementCapturesHistoricalSourcesAndRealEngineOutsideTransactionAndReusesVersion()throws Exception{
        long id=initialize(true),revision=revisions.current(id);budgets.get(id,"2026-10");
        var frozen=source.frozen(id,null,MONTH);assertThat(frozen.score()).isEqualByComparingTo("7.5");
        assertThat(source.frozen(id,7L,MONTH).score()).isEqualByComparingTo(frozen.score());
        assertThat(revisions.current(id)).isEqualTo(revision);verify(engine,atLeastOnce()).simulate(any());
        budgets.get(id,"2026-10");assertThat(jdbc.queryForObject("SELECT count(*) FROM risk_assessment_versions WHERE user_id=?",Long.class,id)).isEqualTo(1);
        assertThat(source.frozen(id,null,MONTH)).isEqualTo(frozen);
    }
    @Test void rollbackDoesNotDispatchAssessmentOrLeaveAnyFrozenResult()throws Exception{
        long id=initialize(true),revision=revisions.current(id);
        assertThatThrownBy(()->tx.executeWithoutResult(s->{revisions.bump(id,"TEST_ROLLBACK");events.publishEvent(new CompletedBudgetMonths(id,List.of(MONTH)));
            assertThat(history.latest(id,MONTH)).isEmpty();throw new IllegalStateException("rollback");})).isInstanceOf(IllegalStateException.class);
        assertThat(history.latest(id,MONTH)).isEmpty();assertThat(revisions.current(id)).isEqualTo(revision);verifyNoInteractions(engine);
    }
    @Test void sourceFailureKeepsCommittedFinanceAndLaterSettlementRetries()throws Exception{
        long id=initialize(true);budgets.get(id,"2026-10"); // Preserve an older result, then invalidate it atomically.
        doThrow(ApiException.unavailable()).when(loans).historicalLoans(eq(id),any());
        long revision=tx.execute(s->{long value=revisions.bump(id,"TEST_COMMIT");events.publishEvent(new CompletedBudgetMonths(id,List.of(MONTH)));return value;});
        assertThat(revisions.current(id)).isEqualTo(revision);assertThat(history.latest(id,MONTH)).isPresent();assertThat(history.pending(id,MONTH)).isTrue();
        assertThatThrownBy(()->source.frozen(id,null,MONTH)).isInstanceOf(ApiException.class);
        doAnswer(i->owned(id)).when(loans).historicalLoans(eq(id),any());budgets.get(id,"2026-10");
        assertThat(history.latest(id,MONTH).orElseThrow().revision()).isEqualTo(revision);assertThat(history.pending(id,MONTH)).isFalse();
    }
    @Test void concurrentInputChangeDiscardsResultAndNextReadCanSaveCurrentRevision()throws Exception{
        long id=initialize(true);AtomicBoolean once=new AtomicBoolean();
        doAnswer(i->{assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();if(once.compareAndSet(false,true))tx.executeWithoutResult(s->revisions.bump(id,"TEST_CONCURRENT"));return i.callRealMethod();}).when(engine).simulate(any());
        budgets.get(id,"2026-10");assertThat(history.latest(id,MONTH)).isEmpty();
        budgets.get(id,"2026-10");assertThat(history.latest(id,MONTH).orElseThrow().revision()).isEqualTo(revisions.current(id));
    }
    @Test void unconfirmedAndCurrentMonthsCannotBecomeFrozenAssessments()throws Exception{
        long id=initialize(false);budgets.get(id,"2026-10");
        assertThatThrownBy(()->maintenance.refresh(id,MONTH)).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->maintenance.refresh(id,MONTH.plusMonths(1))).isInstanceOf(ApiException.class);
        assertThat(history.latest(id,MONTH)).isEmpty();verifyNoInteractions(engine);
    }
    @Test void foreignHistoricalLoanSourceIsRejectedWithoutEvaluatingOrWriting()throws Exception{
        long id=initialize(true);doReturn(owned(id+9999)).when(loans).historicalLoans(eq(id),any());
        assertThatThrownBy(()->maintenance.refresh(id,MONTH)).isInstanceOf(ApiException.class);
        assertThat(history.latest(id,MONTH)).isEmpty();verifyNoInteractions(engine);
    }
}
