package com.loanrun.loan;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.*;
import com.loanrun.common.contract.*;
import com.loanrun.common.contract.FinanceModels.*;
import com.loanrun.loan.read.LoanSourceRepository;
import com.loanrun.loan.service.LoanRegistrationService;
import com.loanrun.repaymentplan.service.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@Testcontainers
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties="server.servlet.session.cookie.secure=false")
@Import(MonthlySummaryIntegrationTest.Config.class)
class MonthlySummaryIntegrationTest {
    @Container static final PostgreSQLContainer<?> DB=new PostgreSQLContainer<>("postgres:16.16-bookworm");
    @DynamicPropertySource static void db(DynamicPropertyRegistry r){r.add("spring.datasource.url",DB::getJdbcUrl);r.add("spring.datasource.username",DB::getUsername);r.add("spring.datasource.password",DB::getPassword);}
    @TestConfiguration static class Config {@Bean @Primary Clock testClock(){return Clock.fixed(Instant.parse("2026-03-01T00:00:00Z"),ZoneId.of("Asia/Seoul"));}}
    @MockitoBean BudgetRecalculationPort recalc;
    @MockitoBean com.loanrun.budget.service.BudgetReadService budget;
    @MockitoBean PlanQueryPort overall;
    @MockitoBean com.loanrun.payment.read.PaymentObservationSource paymentProof;
    @MockitoBean com.loanrun.repaymentsaving.read.SavingObservationSource savingProof;
    @MockitoBean PlanPresentationPort presentation;
    @MockitoBean LoanQueryPort history;
    @Autowired com.loanrun.payment.service.PaymentObservationQueryService paymentObservation;
    @Autowired com.loanrun.repaymentsaving.service.SavingObservationQueryService savingObservation;
    @Autowired JdbcTemplate jdbc;@Autowired ObjectMapper mapper;@Autowired LoanRegistrationService registration;
    @Autowired LoanSourceRepository sources;@Autowired PlanViewQueryService views;@Autowired RevisionPort revisions;@Autowired Clock clock;
    @LocalServerPort int port;
    @BeforeEach void setup()throws Exception {
        if(jdbc.queryForObject("SELECT to_regclass('repayment_plan_versions')",String.class)==null)jdbc.execute(Files.readString(Path.of("docs/sql/repayment_plan_versions_proposal.sql")));
        when(paymentProof.find(any())).thenReturn(Optional.empty());
        when(savingProof.find(any())).thenReturn(Optional.empty());
        when(presentation.monthViews(anyLong(),any(),any(),any())).thenReturn(List.of());
        when(history.history(anyLong(),any())).thenReturn(List.of());
        when(budget.capacity(anyLong(),any())).thenAnswer(i->new BudgetCapacity(i.getArgument(1),2000000L,2000000L,0,2000000L,0,0,2000000L,0,0L,"RECORDED","OPEN",List.of()));
        when(budget.monthlyOverrides(anyLong(),any())).thenReturn(List.of());
    }
    HttpClient browser(){return HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();}
    HttpResponse<String> get(HttpClient b,String path)throws Exception{return b.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+path)).GET().build(),HttpResponse.BodyHandlers.ofString());}
    JsonNode json(HttpResponse<String> r)throws Exception{return mapper.readTree(r.body());}
    long owner(HttpClient b)throws Exception{return json(get(b,"/api/users/me/")).path("data").path("user_id").longValue();}
    long loan(long owner,boolean bullet,boolean exempt)throws Exception {
        var body=new LoanPreviewTest().body();body.put("fee_exempt",exempt);
        if(bullet){body.put("repayment_method","BULLET");body.putNull("prepayment_policy");body.put("initial_savings_balance",2000000);}
        return registration.create(owner,body,null).body().path("data").path("loan_id").longValue();
    }
    JsonNode list(HttpClient b,String query)throws Exception {var r=get(b,"/api/repayment-plans/"+query);assertThat(r.statusCode()).as(r.body()).isEqualTo(200);assertThat(json(r).path("code").asText()).isEqualTo("REPAYMENT_PLAN_LIST_SUCCESS");return json(r).path("data");}
    JsonNode summary(HttpClient b,String query)throws Exception {var r=get(b,"/api/payments/monthly-summary/"+query);assertThat(r.statusCode()).as(r.body()).isEqualTo(200);assertThat(json(r).path("code").asText()).isEqualTo("PAYMENT_MONTHLY_SUMMARY_SUCCESS");return json(r).path("data").path("months");}
    void confirmPayments(boolean zero) {
        when(paymentProof.find(any())).thenAnswer(i->{var scope=(com.loanrun.payment.read.PaymentObservation.Scope)i.getArgument(0);var s=paymentObservation.snapshot(scope.userId(),scope.month(),scope.loanId());return Optional.of(new com.loanrun.payment.read.PaymentObservation.Confirmation(scope,s.fingerprint(),zero?com.loanrun.payment.read.PaymentObservation.Kind.ZERO_CONFIRMED:com.loanrun.payment.read.PaymentObservation.Kind.RECORDING_COMPLETE,clock.instant()));});
    }
    void confirmSavings() {
        when(savingProof.find(any())).thenAnswer(i->{var scope=(com.loanrun.repaymentsaving.read.SavingObservation.Scope)i.getArgument(0);var s=savingObservation.snapshot(scope.userId(),scope.loanId(),scope.month());return Optional.of(new com.loanrun.repaymentsaving.read.SavingObservation.Confirmation(scope,s.fingerprint(),com.loanrun.repaymentsaving.read.SavingObservation.Kind.RECORDING_COMPLETE,clock.instant()));});
    }
    void payment(long user,long id,long principal,long extra,long interest,long fee,long used) {
        jdbc.update("INSERT INTO payments(user_id,loan_id,paid_at,payment_type,amount,principal_amount,extra_amount,interest_amount,fee_amount,savings_used_amount,created_at) VALUES (?,?,'2026-03-01','REGULAR',?,?,?,?,?,?,CURRENT_TIMESTAMP)",user,id,principal+extra+interest+fee,principal,extra,interest,fee,used);
    }
    @Test void defaultSixMonthsAndUnrecordedZeroAreNotConfirmedActuals()throws Exception {
        var b=browser();owner(b);var months=summary(b,"");assertThat(months.size()).isEqualTo(6);assertThat(months.get(0).path("year_month").asText()).isEqualTo("2025-10");
        for(var m:months){assertThat(m.path("observed").asBoolean()).isFalse();assertThat(m.path("actual_amount").isNull()).isTrue();assertThat(m.path("difference").isNull()).isTrue();assertThat(m.path("monthly_principal_repayment_rate").isNull()).isTrue();}
    }
    @Test void positiveRecordAloneDoesNotConfirmACompleteMonth()throws Exception {
        var b=browser();long user=owner(b),id=loan(user,false,true);payment(user,id,700,90,200,10,0);
        var m=summary(b,"?from_month=2026-03&to_month=2026-03&loan_id="+id).get(0);assertThat(m.path("payment_count").longValue()).isEqualTo(1);
        assertThat(m.path("observed").asBoolean()).isFalse();assertThat(m.path("principal_amount").isNull()).isTrue();assertThat(m.path("fee_amount").isNull()).isTrue();
    }
    @Test void explicitZeroIsZeroAndZeroOpeningDenominatorStaysNull()throws Exception {
        var b=browser();long user=owner(b),id=loan(user,false,true);confirmPayments(true);
        var m=summary(b,"?from_month=2026-03&to_month=2026-03&loan_id="+id).get(0);assertThat(m.path("observed").asBoolean()).isTrue();assertThat(m.path("actual_amount").longValue()).isZero();
        assertThat(m.path("monthly_start_principal").longValue()).isZero();assertThat(m.path("monthly_principal_repayment_rate").isNull()).isTrue();assertThat(m.path("actual_saving_amount").longValue()).isZero();
    }
    @Test void confirmedComponentsAndHistoricalOpeningUseTheSameMonthAndCorrectRatio()throws Exception {
        var b=browser();long user=owner(b),id=loan(user,false,true);payment(user,id,250000,100000,30000,0,0);confirmPayments(false);
        when(history.history(eq(user),any())).thenReturn(List.of(new HistoryMonth(YearMonth.of(2026,3),6000000,0,250000,100000,30000,0,1,0,0,true)));
        when(presentation.monthViews(eq(user),any(),eq(YearMonth.of(2026,3)),any())).thenReturn(List.of(views.current(user,id,List.of(),2000000L,null)));
        var m=summary(b,"?from_month=2026-03&to_month=2026-03").get(0);assertThat(m.path("observed").asBoolean()).isTrue();assertThat(m.path("principal_amount").longValue()).isEqualTo(250000);
        assertThat(m.path("extra_amount").longValue()).isEqualTo(100000);assertThat(m.path("interest_amount").longValue()).isEqualTo(30000);assertThat(m.path("actual_amount").longValue()).isEqualTo(380000);
        assertThat(m.path("monthly_principal_repayment_rate").decimalValue()).isEqualByComparingTo("5.83");assertThat(m.path("target_amount").longValue()).isEqualTo(1260000);assertThat(m.path("difference").longValue()).isEqualTo(-880000);
    }
    @Test void savingRequiresSeparateCompletionAndAssetUseIsNotCountedAsNewCash()throws Exception {
        var b=browser();long user=owner(b),id=loan(user,true,true);payment(user,id,300,0,200,0,100);jdbc.update("INSERT INTO repayment_savings(user_id,loan_id,saved_at,amount,created_at) VALUES (?,?,'2026-03-01',100,CURRENT_TIMESTAMP)",user,id);confirmPayments(false);
        String q="?from_month=2026-03&to_month=2026-03&loan_id="+id;assertThat(summary(b,q).get(0).path("observed").asBoolean()).isFalse();confirmSavings();var m=summary(b,q).get(0);
        assertThat(m.path("observed").asBoolean()).isTrue();assertThat(m.path("actual_saving_amount").longValue()).isEqualTo(100);assertThat(m.path("savings_used_amount").longValue()).isEqualTo(100);assertThat(m.path("actual_amount").longValue()).isEqualTo(500);
    }
    @Test void deletedPaidHistoryAndLoanOwnershipArePreserved()throws Exception {
        var b=browser();var other=browser();long user=owner(b),id=loan(user,false,true),paid=loan(user,false,true);owner(other);payment(user,id,700,90,200,10,0);payment(user,paid,100,0,10,0,0);confirmPayments(false);
        jdbc.update("UPDATE loans SET status='DELETED',initial_plan_snapshot='{}'::jsonb WHERE id=?",id);jdbc.update("UPDATE loans SET status='PAID',remaining_principal=0 WHERE id=?",paid);
        var m=summary(b,"?from_month=2026-03&to_month=2026-03").get(0);assertThat(m.path("payment_count").longValue()).isEqualTo(2);assertThat(m.path("actual_amount").longValue()).isEqualTo(1110);
        assertThat(summary(b,"?from_month=2026-03&to_month=2026-03&loan_id="+id).get(0).path("actual_amount").longValue()).isEqualTo(1000);
        assertThat(get(other,"/api/payments/monthly-summary/?loan_id="+id).statusCode()).isEqualTo(404);
    }
    @Test void changedSourceInvalidatesConfirmationAndFutureMonthCannotBeObserved()throws Exception {
        var b=browser();long user=owner(b),id=loan(user,false,true);var s=paymentObservation.snapshot(user,YearMonth.of(2026,3),id);
        when(paymentProof.find(s.scope())).thenReturn(Optional.of(new com.loanrun.payment.read.PaymentObservation.Confirmation(s.scope(),s.fingerprint(),com.loanrun.payment.read.PaymentObservation.Kind.ZERO_CONFIRMED,clock.instant())));
        payment(user,id,100,0,10,0,0);assertThat(summary(b,"?from_month=2026-03&to_month=2026-03&loan_id="+id).get(0).path("observed").asBoolean()).isFalse();confirmPayments(true);
        assertThat(summary(b,"?from_month=2026-04&to_month=2026-04&loan_id="+id).get(0).path("observed").asBoolean()).isFalse();
    }
    @ParameterizedTest @ValueSource(strings={"?from_month=","?from_month=0000-01","?to_month=2026-13","?from_month=2026-04&to_month=2026-03","?from_month=2026-3","?from_month=2026-01&from_month=2026-02","?user_id=1"})
    void invalidDateQueriesUseExactContractError(String q)throws Exception {var r=get(browser(),"/api/payments/monthly-summary/"+q);assertThat(r.statusCode()).as(r.body()).isEqualTo(400);assertThat(json(r).path("code").asText()).isEqualTo("INVALID_DATE_RANGE");}
    @Test void invalidLoanIdAndRepeatedReadNeverMutateSources()throws Exception {
        var b=browser();long user=owner(b),id=loan(user,false,true);assertThat(get(b,"/api/payments/monthly-summary/?loan_id=0").statusCode()).isEqualTo(400);
        var before=sources.loanSnapshot(user,id,LocalDate.of(2026,3,1));long revision=revisions.current(user);assertThat(summary(b,"")).isEqualTo(summary(b,""));assertThat(sources.loanSnapshot(user,id,LocalDate.of(2026,3,1))).isEqualTo(before);assertThat(revisions.current(user)).isEqualTo(revision);
    }
}
