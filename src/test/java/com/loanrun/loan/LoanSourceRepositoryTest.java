package com.loanrun.loan;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.loanrun.common.contract.*;
import com.loanrun.common.contract.FinanceModels.*;
import com.loanrun.common.error.ApiException;
import com.loanrun.loan.read.*;
import com.loanrun.loan.service.LoanRegistrationService;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@Testcontainers
@SpringBootTest
@Import(LoanSourceRepositoryTest.Config.class)
class LoanSourceRepositoryTest {
    @Container static final PostgreSQLContainer<?> DB=new PostgreSQLContainer<>("postgres:16.16-bookworm");
    @DynamicPropertySource static void db(DynamicPropertyRegistry r){r.add("spring.datasource.url",DB::getJdbcUrl);r.add("spring.datasource.username",DB::getUsername);r.add("spring.datasource.password",DB::getPassword);}
    static class TestClock extends Clock {
        Instant value=Instant.parse("2026-02-28T15:30:00Z");
        public ZoneId getZone(){return ZoneId.of("Asia/Seoul");}
        public Clock withZone(ZoneId zone){return Clock.fixed(value,zone);}
        public Instant instant(){return value;}
    }
    @TestConfiguration static class Config {@Bean @Primary TestClock sourceClock(){return new TestClock();}}
    @MockitoBean BudgetRecalculationPort budgets;
    @Autowired TestClock clock;
    @Autowired JdbcTemplate jdbc;
    @Autowired LoanSourceRepository sources;
    @Autowired LoanRegistrationService registration;
    @Autowired org.springframework.beans.factory.ObjectProvider<LoanQueryPort> queryPort;
    @BeforeEach void reset(){clock.value=Instant.parse("2026-02-28T15:30:00Z");}
    LocalDate date(int day){return LocalDate.of(2026,3,day);}
    long owner(){return jdbc.queryForObject("INSERT INTO demo_users(session_digest,created_at,updated_at) VALUES (?,?,?) RETURNING id",Long.class,
        UUID.randomUUID().toString(),Timestamp.from(clock.instant()),Timestamp.from(clock.instant()));}
    long loan(long owner,boolean bullet)throws Exception {
        ObjectNode body=new LoanPreviewTest().body();
        if(bullet){body.put("repayment_method","BULLET");body.putNull("prepayment_policy");body.put("initial_savings_balance",2000000);}
        return registration.create(owner,body,null).body().path("data").path("loan_id").longValue();
    }
    long payment(long owner,long loan,int day,long principal,long extra,long interest,long fee,long used,String memo){
        return jdbc.queryForObject("""
            INSERT INTO payments(user_id,loan_id,paid_at,payment_type,amount,principal_amount,extra_amount,interest_amount,fee_amount,savings_used_amount,memo,created_at)
            VALUES (?,?,?,'REGULAR',?,?,?,?,?,?,?,CURRENT_TIMESTAMP) RETURNING id
            """,Long.class,owner,loan,date(day),principal+extra+interest+fee,principal,extra,interest,fee,used,memo);
    }
    void saving(long owner,long loan,int day,long amount){jdbc.update("INSERT INTO repayment_savings(user_id,loan_id,saved_at,amount,memo,created_at) VALUES (?,?,?,?,'실제 적립',CURRENT_TIMESTAMP)",owner,loan,date(day),amount);}
    void allocation(long owner,long loan,long payment,long due,long principal){jdbc.update("INSERT INTO payment_allocations(user_id,loan_id,payment_id,due_item_id,principal_amount,interest_amount,fee_amount) VALUES (?,?,?,?,?,0,0)",owner,loan,payment,due,principal);}

    @Test void readsOriginalNullableMetadataAndInitialAssetsWithoutManufacturingTransactions()throws Exception {
        long owner=owner(),id=loan(owner,true);var loan=sources.loan(owner,id);
        assertThat(loan.loanId()).isEqualTo(id);assertThat(loan.productId()).isNull();assertThat(loan.contractualNextAmount()).isNull();
        assertThat(loan.feeExempt()).isNull();assertThat(loan.feeRate()).isNull();assertThat(loan.originalFeeMethod()).isEqualTo(FeeMethod.UNKNOWN);
        assertThat(loan.prepaymentPolicy()).isNull();assertThat(loan.initialSavings()).isEqualTo(2000000);
        assertThat(loan.initialPlan().loanId()).isEqualTo(id);assertThat(loan.initialPlan().mode()).isEqualTo("MATURITY");
        assertThat(loan.initialPlan().monthlySaving()).isEqualTo(1000000);
        var snapshot=sources.snapshot(owner,date(1));assertThat(snapshot.payments()).isEmpty();assertThat(snapshot.savings()).isEmpty();
        assertThat(snapshot.balances()).hasSize(1);assertThat(snapshot.rates()).hasSize(1);
        assertThat(queryPort.getIfAvailable()).isNull(); // No incomplete production port is installed.
    }
    @Test void loanLookupHidesForeignAndMissingIdsAndReadSnapshotScopesEveryCollection()throws Exception {
        long a=owner(),b=owner(),loanA=loan(a,true),loanB=loan(b,true);
        payment(a,loanA,5,500,100,10,3,200,"소유자A");payment(b,loanB,5,900,0,10,0,100,"소유자B");
        saving(a,loanA,5,100);saving(b,loanB,5,500);
        for(long foreign:List.of(loanA,Long.MAX_VALUE,0L))assertThatThrownBy(()->sources.loan(b,foreign))
            .isInstanceOfSatisfying(ApiException.class,ex->{assertThat(ex.code()).isEqualTo("LOAN_NOT_FOUND");assertThat(ex.status().value()).isEqualTo(404);});
        var snapshot=sources.snapshot(a,date(15));
        assertThat(snapshot.loans()).extracting(LoanSources.Loan::loanId).containsExactly(loanA);
        assertThat(snapshot.balances()).allSatisfy(r->assertThat(r.userId()).isEqualTo(a));
        assertThat(snapshot.rates()).allSatisfy(r->assertThat(r.userId()).isEqualTo(a));
        assertThat(snapshot.payments()).hasSize(1).allSatisfy(r->assertThat(r.userId()).isEqualTo(a));
        assertThat(snapshot.savings()).hasSize(1).allSatisfy(r->assertThat(r.userId()).isEqualTo(a));
        assertThat(snapshot.dues()).hasSize(1).allSatisfy(r->assertThat(r.userId()).isEqualTo(a));
    }
    @Test void creationCutoffUsesKstAndFutureContractDueRemainsSeparateFromActualCutoff()throws Exception {
        long owner=owner(),id=loan(owner,false);
        assertThat(sources.loan(owner,id).createdAt()).isEqualTo(Instant.parse("2026-02-28T15:30:00Z"));
        assertThat(sources.snapshot(owner,LocalDate.of(2026,2,28)).loans()).isEmpty();
        var snapshot=sources.snapshot(owner,date(1));assertThat(snapshot.loans()).hasSize(1);
        assertThat(snapshot.dues()).extracting(LoanSources.Due::dueDate).containsExactly(date(25));
        assertThat(snapshot.payments()).isEmpty();
    }
    @Test void historiesUseEffectiveDatesAndOriginalDateIdOrderWithoutChangingLatestColumns()throws Exception {
        long owner=owner(),id=loan(owner,false);
        for(long amount:List.of(10000000L,9000000L))jdbc.update("INSERT INTO loan_balance_snapshots(user_id,loan_id,effective_date,amount,reason,created_at) VALUES (?,?,?,?,'CORRECTION',CURRENT_TIMESTAMP)",owner,id,date(3),amount);
        jdbc.update("INSERT INTO loan_balance_snapshots(user_id,loan_id,effective_date,amount,reason,created_at) VALUES (?,?,?,1,'CORRECTION',CURRENT_TIMESTAMP)",owner,id,date(20));
        for(String rate:List.of("7.00","7.10"))jdbc.update("INSERT INTO loan_rate_histories(user_id,loan_id,effective_date,annual_rate,created_at) VALUES (?,?,?,?,CURRENT_TIMESTAMP)",owner,id,date(2),new java.math.BigDecimal(rate));
        jdbc.update("INSERT INTO loan_rate_histories(user_id,loan_id,effective_date,annual_rate,created_at) VALUES (?,?,?,8,CURRENT_TIMESTAMP)",owner,id,date(20));
        var snapshot=sources.snapshot(owner,date(15));
        assertThat(snapshot.balances()).extracting(LoanSources.Balance::amount).containsExactly(12000000L,10000000L,9000000L);
        assertThat(snapshot.rates().stream().map(r->r.annualRate().stripTrailingZeros().toPlainString()).toList()).containsExactly("6","7","7.1");
        assertThat(snapshot.balances().get(1).id()).isLessThan(snapshot.balances().get(2).id());
        assertThat(snapshot.loans().getFirst().storedRemainingPrincipal()).isEqualTo(12000000);
        assertThat(snapshot.loans().getFirst().annualRate()).isEqualByComparingTo("6");
    }
    @Test void inclusiveActualRangesRetainFeesSavingsUseMemoAndSameDayDuplicates()throws Exception {
        long owner=owner(),id=loan(owner,true);
        payment(owner,id,4,100,0,0,0,0,null);
        long first=payment(owner,id,5,500,100,10,3,200,"원본 메모");long second=payment(owner,id,5,500,100,10,3,200,"같은금액");
        payment(owner,id,6,100,0,0,0,0,null);saving(owner,id,4,100);saving(owner,id,5,200);saving(owner,id,6,300);
        var range=new DateRange(date(5),date(5));var payments=sources.payments(owner,range);
        assertThat(payments).extracting(LoanSources.Payment::id).containsExactly(first,second);
        assertThat(payments.getFirst().amount()).isEqualTo(613);assertThat(payments.getFirst().fee()).isEqualTo(3);
        assertThat(payments.getFirst().savingsUsed()).isEqualTo(200);assertThat(payments.getFirst().memo()).isEqualTo("원본 메모");
        assertThat(sources.savings(owner,range)).extracting(LoanSources.Saving::amount).containsExactly(200L);
        assertThat(sources.snapshot(owner,date(5)).payments()).hasSize(3); // Through cutoff, not only the requested range.
    }
    @Test void allocationsFollowActualPaymentDateEvenWhenTheDueIsInTheFuture()throws Exception {
        long a=owner(),b=owner(),loanA=loan(a,true),loanB=loan(b,true);
        long dueA=jdbc.queryForObject("SELECT id FROM due_items WHERE user_id=? AND loan_id=?",Long.class,a,loanA);
        long dueB=jdbc.queryForObject("SELECT id FROM due_items WHERE user_id=? AND loan_id=?",Long.class,b,loanB);
        long paid=payment(a,loanA,5,100,0,0,0,0,null);allocation(a,loanA,paid,dueA,100);
        long future=payment(a,loanA,20,200,0,0,0,0,null);allocation(a,loanA,future,dueA,200);
        long foreign=payment(b,loanB,5,900,0,0,0,0,null);allocation(b,loanB,foreign,dueB,900);
        assertThat(sources.snapshot(a,date(4)).allocations()).isEmpty();
        var snapshot=sources.snapshot(a,date(5));assertThat(snapshot.allocations()).hasSize(1);
        assertThat(snapshot.allocations().getFirst().paymentId()).isEqualTo(paid);
        assertThat(snapshot.allocations().getFirst().dueItemId()).isEqualTo(dueA);
        assertThat(snapshot.dues()).extracting(LoanSources.Due::id).contains(dueA);
        assertThat(snapshot.dues().getFirst().confirmedUnpaid()).isFalse();
    }
    @Test void paidDeletedSourcesAndActualsArePreservedWithoutClaimingHistoricalStatus()throws Exception {
        long owner=owner(),deleted=loan(owner,true),paid=loan(owner,false);
        payment(owner,deleted,5,100,0,0,0,0,"삭제 전 기록");saving(owner,deleted,5,100);
        jdbc.update("UPDATE loans SET status='DELETED',updated_at=CURRENT_TIMESTAMP WHERE user_id=? AND id=?",owner,deleted);
        jdbc.update("UPDATE loans SET status='PAID',remaining_principal=0,updated_at=CURRENT_TIMESTAMP WHERE user_id=? AND id=?",owner,paid);
        var snapshot=sources.snapshot(owner,date(15));
        assertThat(snapshot.loans()).extracting(LoanSources.Loan::storedStatus).containsExactly(LoanStatus.DELETED,LoanStatus.PAID);
        assertThat(sources.payments(owner,new DateRange(date(1),date(31)))).hasSize(1);
        assertThat(sources.savings(owner,new DateRange(date(1),date(31)))).hasSize(1);
        assertThat(sources.loan(owner,deleted).storedStatus()).isEqualTo(LoanStatus.DELETED);
    }
    @Test void zeroBankObligationAndNullableTermsRemainDistinct()throws Exception {
        long owner=owner();var input=new LoanPreviewTest().body();input.put("contractual_monthly_payment",0);input.put("fee_exempt",false);
        long id=registration.create(owner,input,null).body().path("data").path("loan_id").longValue();
        var snapshot=sources.snapshot(owner,date(1));assertThat(snapshot.loans().getFirst().contractualNextAmount()).isZero();
        assertThat(snapshot.loans().getFirst().feeExempt()).isFalse();assertThat(snapshot.loans().getFirst().productId()).isNull();
        var due=snapshot.dues().getFirst();assertThat(due.contractualAmount()).isZero();assertThat(due.requiredAmount()).isZero();
        assertThat(due.estimatedInterest()).isEqualTo(60000);assertThat(sources.loan(owner,id).originalFeeMethod()).isEqualTo(FeeMethod.UNKNOWN);
    }
    @Test void readsAreImmutableAndDoNotAdvanceRevisionOrWriteLedgerRows()throws Exception {
        long owner=owner(),id=loan(owner,true);var before=jdbc.queryForMap("SELECT revision,updated_at FROM demo_users WHERE id=?",owner);
        var first=sources.snapshot(owner,date(15));assertThat(sources.snapshot(owner,date(15))).isEqualTo(first);
        assertThatThrownBy(()->first.loans().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(()->first.dues().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(()->sources.payments(owner,new DateRange(date(1),date(15))).clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThat(jdbc.queryForMap("SELECT revision,updated_at FROM demo_users WHERE id=?",owner)).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payments WHERE user_id=?",Long.class,owner)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM repayment_savings WHERE user_id=?",Long.class,owner)).isZero();
        assertThat(sources.loan(owner,id).initialSavings()).isEqualTo(2000000);
    }
    @Test void malformedStoredPlanFailsInsteadOfReplacingItWithEmptyOrInventedDefaults()throws Exception {
        long owner=owner(),id=loan(owner,false);jdbc.update("UPDATE loans SET initial_plan_snapshot='{}'::jsonb WHERE user_id=? AND id=?",owner,id);
        assertThatThrownBy(()->sources.loan(owner,id)).isInstanceOfSatisfying(ApiException.class,ex->assertThat(ex.code()).isEqualTo("SERVICE_UNAVAILABLE"));
        assertThatThrownBy(()->sources.snapshot(owner,date(15))).isInstanceOf(ApiException.class);
        assertThat(sources.snapshot(owner,LocalDate.of(2026,2,28)).loans()).isEmpty();
    }
}
