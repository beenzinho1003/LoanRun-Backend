package com.loanrun.payment;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import com.loanrun.common.contract.FinanceModels.DateRange;
import com.loanrun.payment.repository.PaymentReadRepository;
import java.net.*;
import java.net.http.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@Testcontainers
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties="server.servlet.session.cookie.secure=false")
@Import({PaymentDetailIntegrationTest.Config.class, com.loanrun.loan.WithoutBudgetModule.class})
class PaymentDetailIntegrationTest {
    @Container static final PostgreSQLContainer<?> DB=new PostgreSQLContainer<>("postgres:16.16-bookworm");
    @DynamicPropertySource static void db(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",DB::getJdbcUrl);r.add("spring.datasource.username",DB::getUsername);r.add("spring.datasource.password",DB::getPassword);
    }
    @TestConfiguration static class Config {
        @Bean @Primary Clock fixed(){return Clock.fixed(Instant.parse("2026-03-31T01:00:00Z"),ZoneId.of("Asia/Seoul"));}
    }
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired PaymentReadRepository payments;
    @Autowired com.loanrun.payment.service.StoredDueQueryService storedDues;
    @Autowired com.loanrun.payment.service.PaymentActualsQueryService actualPayments;
    @Autowired com.loanrun.payment.service.PaymentObservationQueryService paymentObservations;
    @LocalServerPort int port;
    // Synthetic V2 source fixtures only. No runtime provider or test budget/query-port replacement.
    HttpClient browser(){return HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();}
    HttpResponse<String> get(HttpClient browser,String path)throws Exception {
        return browser.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+path)).GET().build(),HttpResponse.BodyHandlers.ofString());
    }
    JsonNode json(HttpResponse<String> response)throws Exception{return mapper.readTree(response.body());}
    long owner(HttpClient browser)throws Exception{return json(get(browser,"/api/users/me/")).path("data").path("user_id").longValue();}
    long loan(long owner){
        return jdbc.queryForObject("""
            INSERT INTO loans(user_id,institution_name,product_name,loan_form,loan_type,repayment_method,
                original_principal,remaining_principal,annual_rate,rate_type,start_date,maturity_date,payment_day,
                next_due_date,grace_months,prepayment_policy,prepayment_fee_method,initial_savings_balance,status,
                snapshot_date,snapshot_principal,initial_plan_snapshot,created_at,updated_at)
            VALUES (?,'가상은행','가상대출','GENERAL','MORTGAGE','BULLET',12000000,12000000,6,'FIXED',
                '2025-01-25','2026-12-25',25,'2026-03-25',0,NULL,'UNKNOWN',2000000,'ACTIVE',
                '2026-03-01',12000000,'{}',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP) RETURNING id
            """,Long.class,owner);
    }
    long payment(long owner,long loan,String date,String kind,long principal,long extra,long interest,long fee,long used){
        return jdbc.queryForObject("""
            INSERT INTO payments(user_id,loan_id,paid_at,payment_type,amount,principal_amount,extra_amount,interest_amount,
                fee_amount,savings_used_amount,memo,created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?) RETURNING id
            """,Long.class,owner,loan,LocalDate.parse(date),kind,principal+extra+interest+fee,principal,extra,interest,fee,used,
                "조회 응답에 추가하지 않는 원본 메모",Timestamp.from(Instant.parse("2026-03-24T15:30:00Z")));
    }
    long due(long owner,long loan,String date){
        return jdbc.queryForObject("""
            INSERT INTO due_items(user_id,loan_id,due_date,principal_amount,interest_amount,fee_amount,required_amount,terms_revision,created_at)
            VALUES (?,?,?,500000,30000,100,530100,1,CURRENT_TIMESTAMP) RETURNING id
            """,Long.class,owner,loan,LocalDate.parse(date));
    }
    void allocate(long owner,long loan,long payment,long due,long principal,long interest,long fee){
        jdbc.update("""
            INSERT INTO payment_allocations(user_id,loan_id,payment_id,due_item_id,principal_amount,interest_amount,fee_amount)
            VALUES (?,?,?,?,?,?,?)
            """,owner,loan,payment,due,principal,interest,fee);
    }
    String path(long payment){return "/api/payments/"+payment+"/";}
    JsonNode detail(HttpClient browser,long id)throws Exception {
        var response=get(browser,path(id));assertThat(response.statusCode()).isEqualTo(200);return json(response).path("data");
    }
    void missing(HttpClient browser,long id)throws Exception {
        var response=get(browser,path(id));assertThat(response.statusCode()).isEqualTo(404);
        assertThat(json(response)).isEqualTo(mapper.readTree("""
            {"success":false,"code":"PAYMENT_NOT_FOUND","message":"납부 기록을 찾을 수 없습니다.","errors":{}}
            """));
    }

    JsonNode list(HttpClient browser,String query)throws Exception {
        var response=get(browser,"/api/payments/"+query);assertThat(response.statusCode()).isEqualTo(200);return json(response).path("data");
    }
    List<Long> ids(JsonNode data) {
        var ids=new ArrayList<Long>();data.path("results").forEach(row->ids.add(row.path("payment_id").longValue()));return ids;
    }
    DateRange period(String from,String to){return new DateRange(LocalDate.parse(from),LocalDate.parse(to));}

    @Test void positiveAndEmptyStoredMonthsWithoutCompletionEvidenceRemainUnobservedAndNullable()throws Exception {
        var browser=browser();long owner=owner(browser),loan=loan(owner);payment(owner,loan,"2026-03-25","EXTRA",0,100,0,0,0);
        var march=paymentObservations.observe(owner,YearMonth.of(2026,3),loan);var feb=paymentObservations.observe(owner,YearMonth.of(2026,2),loan);
        assertThat(march.observed()).isFalse();assertThat(march.actuals()).isNull();assertThat(march.source().recorded().amount()).isEqualTo(100);
        assertThat(feb.observed()).isFalse();assertThat(feb.actuals()).isNull();assertThat(feb.source().recorded().paymentCount()).isZero();
    }
    @Test void fingerprintsAreStableForFinancialFactsAndDoNotTreatMemoAsNewMonthlyPerformance()throws Exception {
        var browser=browser();long owner=owner(browser),loan=loan(owner),p=payment(owner,loan,"2026-03-25","EXTRA",0,100,0,10,50);
        var first=paymentObservations.snapshot(owner,YearMonth.of(2026,3),loan);
        assertThat(paymentObservations.snapshot(owner,YearMonth.of(2026,3),loan)).isEqualTo(first);
        jdbc.update("UPDATE payments SET memo='표시용 메모만 변경' WHERE id=?",p);
        assertThat(paymentObservations.snapshot(owner,YearMonth.of(2026,3),loan)).isEqualTo(first);
    }
    @Test void replacedPaymentWithSameAmountsStillInvalidatesOldCompletionEvidence()throws Exception {
        var browser=browser();long owner=owner(browser),loan=loan(owner),p=payment(owner,loan,"2026-03-25","EXTRA",0,100,0,0,0);
        var before=paymentObservations.snapshot(owner,YearMonth.of(2026,3),loan);
        var proof=new com.loanrun.payment.read.PaymentObservation.Confirmation(before.scope(),before.fingerprint(),
            com.loanrun.payment.read.PaymentObservation.Kind.RECORDING_COMPLETE,Instant.parse("2026-03-31T00:00:00Z"));
        jdbc.update("DELETE FROM payments WHERE id=?",p);payment(owner,loan,"2026-03-25","EXTRA",0,100,0,0,0);
        var after=paymentObservations.snapshot(owner,YearMonth.of(2026,3),loan);
        assertThat(after.recorded()).isEqualTo(before.recorded());assertThat(after.fingerprint()).isNotEqualTo(before.fingerprint());
        var projected=com.loanrun.payment.domain.PaymentObservationPolicy.evaluate(after,Optional.of(proof),Instant.parse("2026-03-31T01:00:00Z"));
        assertThat(projected.observed()).isFalse();assertThat(projected.actuals()).isNull();
    }
    @Test void componentChangesWithSamePaymentAmountInvalidateEvidenceButOtherMonthsDoNot()throws Exception {
        var browser=browser();long owner=owner(browser),loan=loan(owner),p=payment(owner,loan,"2026-03-25","REGULAR",100,0,10,0,0);
        var before=paymentObservations.snapshot(owner,YearMonth.of(2026,3),loan);
        payment(owner,loan,"2026-02-25","EXTRA",0,500,0,0,0);jdbc.update("UPDATE demo_users SET revision=revision+1 WHERE id=?",owner);
        assertThat(paymentObservations.snapshot(owner,YearMonth.of(2026,3),loan)).isEqualTo(before);
        jdbc.update("UPDATE payments SET principal_amount=90,interest_amount=20 WHERE id=?",p);
        var after=paymentObservations.snapshot(owner,YearMonth.of(2026,3),loan);
        assertThat(after.recorded().amount()).isEqualTo(before.recorded().amount());assertThat(after.fingerprint()).isNotEqualTo(before.fingerprint());
    }
    @Test void observationSnapshotsBindUserLoanSelectionAndMonthAndHideForeignLoans()throws Exception {
        var a=browser();var b=browser();long userA=owner(a),userB=owner(b),loanA=loan(userA),loanB=loan(userB);
        var single=paymentObservations.snapshot(userA,YearMonth.of(2026,3),loanA);
        var all=paymentObservations.snapshot(userA,YearMonth.of(2026,3),null);
        var otherUser=paymentObservations.snapshot(userB,YearMonth.of(2026,3),loanB);
        var otherMonth=paymentObservations.snapshot(userA,YearMonth.of(2026,2),loanA);
        assertThat(Set.of(single.fingerprint(),all.fingerprint(),otherUser.fingerprint(),otherMonth.fingerprint())).hasSize(4);
        assertThatThrownBy(()->paymentObservations.snapshot(userA,YearMonth.of(2026,3),loanB))
            .isInstanceOfSatisfying(com.loanrun.common.error.ApiException.class,e->assertThat(e.code()).isEqualTo("LOAN_NOT_FOUND"));
    }
    @Test void actualZeroConfirmationIsExplicitAndObservationReadsWriteNoFinancialState()throws Exception {
        var browser=browser();long owner=owner(browser),loan=loan(owner);
        var userBefore=jdbc.queryForMap("SELECT revision,updated_at FROM demo_users WHERE id=?",owner);
        var source=paymentObservations.snapshot(owner,YearMonth.of(2026,3),loan);
        var proof=new com.loanrun.payment.read.PaymentObservation.Confirmation(source.scope(),source.fingerprint(),
            com.loanrun.payment.read.PaymentObservation.Kind.ZERO_CONFIRMED,Instant.parse("2026-03-31T00:00:00Z"));
        var result=com.loanrun.payment.domain.PaymentObservationPolicy.evaluate(source,Optional.of(proof),Instant.parse("2026-03-31T01:00:00Z"));
        assertThat(result.observed()).isTrue();assertThat(result.actuals()).isNotNull();assertThat(result.actuals().amount()).isZero();
        paymentObservations.observe(owner,YearMonth.of(2026,3),loan);
        assertThat(jdbc.queryForMap("SELECT revision,updated_at FROM demo_users WHERE id=?",owner)).isEqualTo(userBefore);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payments WHERE user_id=?",Long.class,owner)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM idempotency_records WHERE user_id=?",Long.class,owner)).isZero();
    }

    @Test void actualTotalsSeparatePaymentKindsExtraPrincipalFeesSavingsAndCash()throws Exception {
        var browser=browser();long owner=owner(browser),loan=loan(owner);
        jdbc.update("UPDATE loans SET original_principal=350000,remaining_principal=0,snapshot_principal=350000,initial_savings_balance=130000,status='PAID',maturity_date='2026-03-25' WHERE id=?",loan);
        // BULLET: early principal is EXTRA; remaining maturity principal is REGULAR, never both in one payment.
        payment(owner,loan,"2026-03-05","EXTRA",0,100000,0,2000,80000);
        payment(owner,loan,"2026-03-06","EXTRA",0,200000,0,1000,50000);
        payment(owner,loan,"2026-03-25","REGULAR",50000,0,3000,0,0);
        var totals=actualPayments.aggregate(owner,period("2026-03-01","2026-03-31"),null);
        assertThat(totals.paymentCount()).isEqualTo(3);assertThat(totals.regularCount()).isEqualTo(1);assertThat(totals.extraPaymentCount()).isEqualTo(2);
        assertThat(totals.extraPrincipalCount()).isEqualTo(2);assertThat(totals.regularPrincipal()).isEqualTo(50000);assertThat(totals.extraPrincipal()).isEqualTo(300000);
        assertThat(totals.interest()).isEqualTo(3000);assertThat(totals.fee()).isEqualTo(3000);assertThat(totals.amount()).isEqualTo(356000);
        assertThat(totals.savingsUsed()).isEqualTo(130000);assertThat(totals.cashPaid()).isEqualTo(226000);
        assertThat(totals.amount()).isEqualTo(totals.regularPrincipal()+totals.extraPrincipal()+totals.interest()+totals.fee());
    }
    @Test void actualMonthsFollowPaidDateNotCreatedDateOrDueMonthAndDoNotInventEmptyMonths()throws Exception {
        var browser=browser();long owner=owner(browser),loan=loan(owner);
        long feb=payment(owner,loan,"2026-02-28","REGULAR",100,0,10,0,0),mar=payment(owner,loan,"2026-03-01","REGULAR",200,0,20,0,0);
        long due=due(owner,loan,"2026-03-25");allocate(owner,loan,feb,due,100,10,0);allocate(owner,loan,mar,due,200,20,0);
        var months=actualPayments.monthly(owner,period("2026-01-01","2026-04-30"),null);
        assertThat(months).extracting(v->v.month().toString()).containsExactly("2026-02","2026-03");
        assertThat(months).extracting(v->v.totals().amount()).containsExactly(110L,220L);
        assertThat(months).allSatisfy(v->assertThat(v.loanId()).isEqualTo(loan));
    }
    @Test void actualMonthsGroupByLoanKeepDuplicatesAndIncludePaidDeletedHistory()throws Exception {
        var browser=browser();long owner=owner(browser),a=loan(owner),b=loan(owner);
        payment(owner,a,"2026-03-05","EXTRA",0,100,0,0,0);payment(owner,a,"2026-03-05","EXTRA",0,100,0,0,0);
        payment(owner,b,"2026-03-05","REGULAR",200,0,10,0,0);
        jdbc.update("UPDATE loans SET status='DELETED' WHERE id=?",a);jdbc.update("UPDATE loans SET status='PAID' WHERE id=?",b);
        var months=actualPayments.monthly(owner,period("2026-03-01","2026-03-31"),null);
        assertThat(months).extracting(v->v.loanId()).containsExactly(a,b);
        assertThat(months.getFirst().totals().paymentCount()).isEqualTo(2);assertThat(months.getFirst().totals().extraPrincipal()).isEqualTo(200);
        assertThat(months.getLast().totals().amount()).isEqualTo(210);
        assertThat(actualPayments.aggregate(owner,period("2026-03-01","2026-03-31"),a).amount()).isEqualTo(200);
    }
    @Test void actualDateBoundariesAndLoanOwnershipAreAppliedToEveryAggregate()throws Exception {
        var a=browser();var b=browser();long userA=owner(a),userB=owner(b),loanA=loan(userA),empty=loan(userA),loanB=loan(userB);
        payment(userA,loanA,"2026-03-04","REGULAR",100,0,0,0,0);payment(userA,loanA,"2026-03-05","REGULAR",200,0,0,0,0);
        payment(userA,loanA,"2026-03-06","REGULAR",400,0,0,0,0);payment(userB,loanB,"2026-03-05","REGULAR",900,0,0,0,0);
        var oneDay=period("2026-03-05","2026-03-05");
        assertThat(actualPayments.aggregate(userA,oneDay,null).amount()).isEqualTo(200);
        assertThat(actualPayments.monthly(userA,oneDay,loanA)).hasSize(1);
        var noRows=actualPayments.aggregate(userA,oneDay,empty);assertThat(noRows.paymentCount()).isZero();assertThat(noRows.cashPaid()).isZero();
        assertThat(actualPayments.monthly(userA,oneDay,empty)).isEmpty();
        for(long missing:List.of(loanB,Long.MAX_VALUE)) {
            assertThatThrownBy(()->actualPayments.aggregate(userA,oneDay,missing)).isInstanceOfSatisfying(com.loanrun.common.error.ApiException.class,e->assertThat(e.code()).isEqualTo("LOAN_NOT_FOUND"));
            assertThatThrownBy(()->actualPayments.monthly(userA,oneDay,missing)).isInstanceOfSatisfying(com.loanrun.common.error.ApiException.class,e->assertThat(e.code()).isEqualTo("LOAN_NOT_FOUND"));
        }
    }
    @Test void actualSumsNeverJoinAllocationsAndExistingListTotalsUseTheSameActualAmounts()throws Exception {
        var browser=browser();long owner=owner(browser),loan=loan(owner),d1=due(owner,loan,"2026-03-10"),d2=due(owner,loan,"2026-03-25");
        jdbc.update("UPDATE loans SET repayment_method='EQUAL_PRINCIPAL',prepayment_policy='KEEP_PAYMENT',initial_savings_balance=0 WHERE id=?",loan);
        // A combined regular/extra installment payment uses no BULLET savings.
        long p=payment(owner,loan,"2026-03-27","REGULAR",500000,100000,60000,100,0);
        allocate(owner,loan,p,d1,250000,30000,0);allocate(owner,loan,p,d2,250000,30000,100);
        var actual=actualPayments.aggregate(owner,period("2026-03-01","2026-03-31"),null);
        assertThat(actual.paymentCount()).isEqualTo(1);assertThat(actual.amount()).isEqualTo(660100);assertThat(actual.cashPaid()).isEqualTo(660100);
        assertThat(actualPayments.monthly(owner,period("2026-03-01","2026-03-31"),null).getFirst().totals()).isEqualTo(actual);
        var data=list(browser,"?page_size=1");assertThat(data.path("count").longValue()).isEqualTo(actual.paymentCount());
        assertThat(data.path("totals").path("amount").longValue()).isEqualTo(actual.amount());
        assertThat(data.path("totals").path("savings_used_amount").longValue()).isEqualTo(actual.savingsUsed());
        assertThat(data.path("results").get(0).path("due_allocations").size()).isEqualTo(2);
    }
    @Test void initialSavingsAndSavingAdditionsAreNotPaymentOrCashOutflowRecords()throws Exception {
        var browser=browser();long owner=owner(browser),loan=loan(owner);
        jdbc.update("INSERT INTO repayment_savings(user_id,loan_id,saved_at,amount,created_at) VALUES (?,?,'2026-03-05',100000,CURRENT_TIMESTAMP)",owner,loan);
        var range=period("2026-03-01","2026-03-31");var actual=actualPayments.aggregate(owner,range,null);
        assertThat(actual.paymentCount()).isZero();assertThat(actual.amount()).isZero();assertThat(actual.savingsUsed()).isZero();assertThat(actual.cashPaid()).isZero();
        assertThat(actualPayments.monthly(owner,range,null)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT initial_savings_balance FROM loans WHERE id=?",Long.class,loan)).isEqualTo(2000000);
    }
    @Test void zeroActualCashFromSavingsFundingRemainsDifferentFromAnEmptyPaymentSet()throws Exception {
        var browser=browser();long owner=owner(browser),loan=loan(owner);
        jdbc.update("UPDATE loans SET maturity_date='2026-03-25' WHERE id=?",loan);
        payment(owner,loan,"2026-03-25","REGULAR",100000,0,0,0,100000);
        var paid=actualPayments.aggregate(owner,period("2026-03-01","2026-03-31"),null);
        var empty=actualPayments.aggregate(owner,period("2026-02-01","2026-02-28"),null);
        assertThat(paid.cashPaid()).isZero();assertThat(paid.paymentCount()).isEqualTo(1);assertThat(paid.amount()).isEqualTo(100000);
        assertThat(empty.cashPaid()).isZero();assertThat(empty.paymentCount()).isZero();assertThat(empty.amount()).isZero();
    }
    @Test void actualMonthlyAndWholeRangeLongOverflowsFailInsteadOfReturningWrappedFinancialData()throws Exception {
        var browser=browser();long owner=owner(browser),loan=loan(owner);
        for(int i=0;i<2;i++)payment(owner,loan,"2026-03-25","REGULAR",0,0,Long.MAX_VALUE-1,0,0);
        var range=period("2026-03-01","2026-03-31");
        assertThatThrownBy(()->actualPayments.aggregate(owner,range,null)).isInstanceOfSatisfying(com.loanrun.common.error.ApiException.class,e->assertThat(e.code()).isEqualTo("SERVICE_UNAVAILABLE"));
        assertThatThrownBy(()->actualPayments.monthly(owner,range,null)).isInstanceOfSatisfying(com.loanrun.common.error.ApiException.class,e->assertThat(e.code()).isEqualTo("SERVICE_UNAVAILABLE"));
    }
    @Test void actualQueriesAreImmutableAndLeaveAllFinancialSourceAndRevisionStateUntouched()throws Exception {
        var browser=browser();long owner=owner(browser),loan=loan(owner),payment=payment(owner,loan,"2026-03-25","EXTRA",0,100,0,10,50);
        var userBefore=jdbc.queryForMap("SELECT revision,updated_at FROM demo_users WHERE id=?",owner);
        var loanBefore=jdbc.queryForMap("SELECT * FROM loans WHERE id=?",loan);var paymentBefore=jdbc.queryForMap("SELECT * FROM payments WHERE id=?",payment);
        var range=period("2026-03-01","2026-03-31");var first=actualPayments.monthly(owner,range,null);
        assertThat(actualPayments.monthly(owner,range,null)).isEqualTo(first);assertThatThrownBy(first::clear).isInstanceOf(UnsupportedOperationException.class);
        assertThat(jdbc.queryForMap("SELECT revision,updated_at FROM demo_users WHERE id=?",owner)).isEqualTo(userBefore);
        assertThat(jdbc.queryForMap("SELECT * FROM loans WHERE id=?",loan)).isEqualTo(loanBefore);
        assertThat(jdbc.queryForMap("SELECT * FROM payments WHERE id=?",payment)).isEqualTo(paymentBefore);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM idempotency_records WHERE user_id=?",Long.class,owner)).isZero();
    }

    @Test void confirmationWithoutRealBudgetProviderReturns503AndSavesNoFinancialOrReplayState()throws Exception {
        var browser=browser();long owner=owner(browser),loan=loan(owner),due=due(owner,loan,"2026-03-10");
        var response=browser.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/payments/schedule/"+due+"/confirmation/"))
            .header("Origin","http://localhost:5173").header("Content-Type","application/json").header("Idempotency-Key","missing-budget")
            .method("PATCH",HttpRequest.BodyPublishers.ofString("{\"unpaid_confirmed\":true}")).build(),HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(503);assertThat(json(response).path("code").asText()).isEqualTo("SERVICE_UNAVAILABLE");
        assertThat(jdbc.queryForObject("SELECT confirmed_unpaid FROM due_items WHERE id=?",Boolean.class,due)).isFalse();
        assertThat(jdbc.queryForObject("SELECT revision FROM demo_users WHERE id=?",Long.class,owner)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM idempotency_records WHERE user_id=?",Long.class,owner)).isZero();
    }
    @Test void unchangedConfirmationNeedsNoBudgetProviderAndPreservesRevision()throws Exception {
        var browser=browser();long owner=owner(browser),loan=loan(owner),due=due(owner,loan,"2026-03-10");
        var before=jdbc.queryForMap("SELECT revision,updated_at FROM demo_users WHERE id=?",owner);
        var response=browser.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/payments/schedule/"+due+"/confirmation/"))
            .header("Origin","http://localhost:5173").header("Content-Type","application/json").header("Idempotency-Key","no-change")
            .method("PATCH",HttpRequest.BodyPublishers.ofString("{\"unpaid_confirmed\":false}")).build(),HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);assertThat(json(response).path("data").path("report_stale").booleanValue()).isFalse();
        assertThat(jdbc.queryForMap("SELECT revision,updated_at FROM demo_users WHERE id=?",owner)).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT confirmed_unpaid FROM due_items WHERE id=?",Boolean.class,due)).isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM idempotency_records WHERE user_id=?",Long.class,owner)).isEqualTo(1);
    }
    @Test void storedDueStatesUseCurrentKstDateAndPreserveAllFiveStatesWithoutGeneratingRows()throws Exception {
        var browser=browser();long owner=owner(browser),loan=loan(owner);
        long unrecorded=due(owner,loan,"2026-03-10"),unpaid=due(owner,loan,"2026-03-11"),partial=due(owner,loan,"2026-03-12"),
            paid=due(owner,loan,"2026-03-13"),today=due(owner,loan,"2026-03-31"),future=due(owner,loan,"2026-04-01");
        jdbc.update("UPDATE due_items SET confirmed_unpaid=true WHERE id IN (?,?)",unpaid,partial);
        long p=payment(owner,loan,"2026-03-25","REGULAR",500100,0,30000,100,0);
        allocate(owner,loan,p,partial,100,0,0);allocate(owner,loan,p,paid,500000,30000,100);
        var data=storedDues.range(owner,new com.loanrun.common.contract.FinanceModels.DateRange(LocalDate.of(2026,3,10),LocalDate.of(2026,4,1)),null);
        assertThat(data).extracting(v->v.source().due().id()).containsExactly(unrecorded,unpaid,partial,paid,today,future);
        assertThat(data).extracting(v->v.state().status().name()).containsExactly("UNRECORDED","UNPAID","PARTIAL","PAID","UPCOMING","UPCOMING");
        assertThat(data).allSatisfy(v->assertThat(v.evaluatedOn()).isEqualTo(LocalDate.of(2026,3,31)));
        assertThat(storedDues.find(owner,partial).source().due().confirmedUnpaid()).isTrue();
        assertThat(storedDues.range(owner,new com.loanrun.common.contract.FinanceModels.DateRange(LocalDate.of(2026,4,2),LocalDate.of(2026,4,30)),null)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM due_items WHERE user_id=?",Long.class,owner)).isEqualTo(6);
    }
    @Test void storedDueAggregatesActualComponentsAcrossPaymentsAndKeepsMultiDueAllocationsSeparate()throws Exception {
        var browser=browser();long owner=owner(browser),loan=loan(owner),first=due(owner,loan,"2026-03-10"),second=due(owner,loan,"2026-03-11");
        long p1=payment(owner,loan,"2026-03-25","REGULAR",350000,0,30010,100,0),p2=payment(owner,loan,"2026-03-26","REGULAR",250000,0,0,0,0);
        allocate(owner,loan,p1,first,250000,30000,100);allocate(owner,loan,p2,first,250000,0,0);allocate(owner,loan,p1,second,100000,10,0);
        var a=storedDues.find(owner,first);assertThat(a.source().allocationCount()).isEqualTo(2);
        assertThat(a.source().principal()).isEqualTo(500000);assertThat(a.source().interest()).isEqualTo(30000);assertThat(a.source().fee()).isEqualTo(100);
        assertThat(a.state().paidRequiredAmount()).isEqualTo(530100);assertThat(a.state().outstandingAmount()).isZero();
        var b=storedDues.find(owner,second);assertThat(b.source().allocationCount()).isEqualTo(1);assertThat(b.state().allocatedAmount()).isEqualTo(100010);
        assertThat(b.state().outstandingAmount()).isEqualTo(430090);
    }
    @Test void storedZeroBankAmountIsNotReplacedByEstimatedComponentsOrPaidActuals()throws Exception {
        var browser=browser();long owner=owner(browser),loan=loan(owner),due=due(owner,loan,"2026-03-10");
        jdbc.update("UPDATE due_items SET contractual_amount=0,required_amount=0 WHERE id=?",due);
        var data=storedDues.find(owner,due);assertThat(data.source().due().contractualAmount()).isZero();
        assertThat(data.source().due().estimatedInterest()).isEqualTo(30000);assertThat(data.state().status().name()).isEqualTo("PAID");
        assertThat(data.state().allocatedAmount()).isZero();assertThat(data.state().outstandingAmount()).isZero();
    }
    @Test void storedDuesAreOwnerScopedAndRetainPaidDeletedLoanSourceHistory()throws Exception {
        var a=browser();var b=browser();long ownerA=owner(a),ownerB=owner(b),loanA=loan(ownerA),loanB=loan(ownerB);
        long dueA=due(ownerA,loanA,"2026-03-10"),dueB=due(ownerB,loanB,"2026-03-10");
        var range=new com.loanrun.common.contract.FinanceModels.DateRange(LocalDate.of(2026,3,10),LocalDate.of(2026,3,10));
        assertThat(storedDues.range(ownerA,range,null)).extracting(v->v.source().due().id()).containsExactly(dueA);
        assertThatThrownBy(()->storedDues.find(ownerA,dueB)).isInstanceOfSatisfying(com.loanrun.common.error.ApiException.class,ex->assertThat(ex.code()).isEqualTo("DUE_ITEM_NOT_FOUND"));
        assertThatThrownBy(()->storedDues.range(ownerA,range,loanB)).isInstanceOfSatisfying(com.loanrun.common.error.ApiException.class,ex->assertThat(ex.code()).isEqualTo("LOAN_NOT_FOUND"));
        for(String status:List.of("PAID","DELETED")) {
            jdbc.update("UPDATE loans SET status=? WHERE id=?",status,loanA);
            var result=storedDues.range(ownerA,range,loanA);assertThat(result).hasSize(1);
            assertThat(result.getFirst().source().loanStatus().name()).isEqualTo(status);
        }
    }
    @Test void extraRepaymentIsNotRegularFulfillmentAndInvalidExtraAllocationFailsClosed()throws Exception {
        var browser=browser();long owner=owner(browser),loan=loan(owner),due=due(owner,loan,"2026-03-10");
        long extra=payment(owner,loan,"2026-03-25","EXTRA",0,100,0,0,0);
        assertThat(storedDues.find(owner,due).state().status().name()).isEqualTo("UNRECORDED");
        allocate(owner,loan,extra,due,100,0,0);
        assertThatThrownBy(()->storedDues.find(owner,due)).isInstanceOfSatisfying(com.loanrun.common.error.ApiException.class,ex->assertThat(ex.code()).isEqualTo("SERVICE_UNAVAILABLE"));
    }
    @Test void storedDueOverflowDoesNotWrapAndQueriesNeverWriteFinancialSources()throws Exception {
        var browser=browser();long owner=owner(browser),loan=loan(owner),due=due(owner,loan,"2026-03-10");
        var before=jdbc.queryForMap("SELECT * FROM due_items WHERE id=?",due);var userBefore=jdbc.queryForMap("SELECT revision,updated_at FROM demo_users WHERE id=?",owner);
        var first=storedDues.find(owner,due);assertThat(storedDues.find(owner,due)).isEqualTo(first);
        var results=storedDues.range(owner,new com.loanrun.common.contract.FinanceModels.DateRange(LocalDate.of(2026,3,10),LocalDate.of(2026,3,10)),null);
        assertThatThrownBy(results::clear).isInstanceOf(UnsupportedOperationException.class);
        assertThat(jdbc.queryForMap("SELECT * FROM due_items WHERE id=?",due)).isEqualTo(before);
        assertThat(jdbc.queryForMap("SELECT revision,updated_at FROM demo_users WHERE id=?",owner)).isEqualTo(userBefore);
        for(int i=0;i<2;i++) {
            long p=payment(owner,loan,"2026-03-25","REGULAR",Long.MAX_VALUE-1,0,0,0,0);allocate(owner,loan,p,due,Long.MAX_VALUE-1,0,0);
        }
        assertThatThrownBy(()->storedDues.find(owner,due)).isInstanceOfSatisfying(com.loanrun.common.error.ApiException.class,ex->assertThat(ex.code()).isEqualTo("SERVICE_UNAVAILABLE"));
    }

    @Test void emptyListUsesExactDefaultsEnvelopeAndZeroQueryTotals()throws Exception {
        var browser=browser();owner(browser);
        var response=get(browser,"/api/payments/");assertThat(response.statusCode()).isEqualTo(200);
        assertThat(json(response)).isEqualTo(mapper.readTree("""
            {"success":true,"code":"PAYMENT_LIST_SUCCESS","message":"상환 내역 목록 정보를 조회했습니다.",
             "data":{"count":0,"page":1,"page_size":20,"next_page":null,"previous_page":null,"results":[],
              "totals":{"amount":0,"principal_amount":0,"interest_amount":0,"extra_amount":0,"fee_amount":0,"savings_used_amount":0}}}
            """));
    }
    @Test void pagesAreStableByActualDateAndIdRetainDuplicatesAndKeepFullFilterTotals()throws Exception {
        var browser=browser();long owner=owner(browser),loan=loan(owner);
        long oldest=payment(owner,loan,"2026-03-01","REGULAR",100,0,10,1,0);
        long newer=payment(owner,loan,"2026-03-02","EXTRA",0,200,0,2,100);
        long newest=payment(owner,loan,"2026-03-02","EXTRA",0,200,0,2,100);
        var first=list(browser,"?page_size=2");assertThat(ids(first)).containsExactly(newest,newer);
        assertThat(first.path("count").longValue()).isEqualTo(3);assertThat(first.path("page").intValue()).isEqualTo(1);
        assertThat(first.path("next_page").intValue()).isEqualTo(2);assertThat(first.path("previous_page").isNull()).isTrue();
        assertThat(first.path("totals")).isEqualTo(mapper.readTree("""
            {"amount":515,"principal_amount":100,"interest_amount":10,"extra_amount":400,"fee_amount":5,"savings_used_amount":200}
            """));
        var second=list(browser,"?page=2&page_size=2");assertThat(ids(second)).containsExactly(oldest);
        assertThat(second.path("next_page").isNull()).isTrue();assertThat(second.path("previous_page").intValue()).isEqualTo(1);
        assertThat(second.path("totals")).isEqualTo(first.path("totals"));
        var beyond=list(browser,"?page=4&page_size=2");assertThat(ids(beyond)).isEmpty();
        assertThat(beyond.path("count").longValue()).isEqualTo(3);assertThat(beyond.path("totals")).isEqualTo(first.path("totals"));
        assertThat(beyond.path("previous_page").intValue()).isEqualTo(3);assertThat(beyond.path("next_page").isNull()).isTrue();
    }
    @Test void pageLimitAppliesToPaymentsNotJoinedAllocationsAndListRowsMatchDetail()throws Exception {
        var browser=browser();long owner=owner(browser),loan=loan(owner);
        long later=due(owner,loan,"2026-03-25"),earlier=due(owner,loan,"2026-03-10");
        var expected=new ArrayList<Long>();
        for(int i=0;i<3;i++) {
            long id=payment(owner,loan,"2026-03-27","REGULAR",500000,0,60000,100,0);
            allocate(owner,loan,id,later,250000,30000,100);allocate(owner,loan,id,earlier,250000,30000,0);expected.addFirst(id);
        }
        var first=list(browser,"?page_size=2");assertThat(ids(first)).containsExactlyElementsOf(expected.subList(0,2));
        assertThat(first.path("count").longValue()).isEqualTo(3);assertThat(first.path("totals").path("amount").longValue()).isEqualTo(1680300);
        for(var row:first.path("results")) {
            assertThat(row.path("due_allocations").size()).isEqualTo(2);
            assertThat(row).isEqualTo(detail(browser,row.path("payment_id").longValue()));
        }
        var second=list(browser,"?page=2&page_size=2");assertThat(ids(second)).containsExactly(expected.getLast());
        assertThat(second.path("results").get(0).path("due_allocations").size()).isEqualTo(2);
    }
    @Test void filtersUseInclusiveActualDatesAndCombineLoanAndPaymentType()throws Exception {
        var browser=browser();long owner=owner(browser),loan=loan(owner),other=loan(owner);
        payment(owner,loan,"2026-03-04","REGULAR",100,0,0,0,0);
        long regular=payment(owner,loan,"2026-03-05","REGULAR",200,0,0,0,0);
        long extra=payment(owner,loan,"2026-03-05","EXTRA",0,300,0,0,0);
        payment(owner,loan,"2026-03-06","REGULAR",400,0,0,0,0);
        payment(owner,other,"2026-03-05","REGULAR",900,0,0,0,0);
        String sameDay="?loan_id="+loan+"&from=2026-03-05&to=2026-03-05";
        var both=list(browser,sameDay);assertThat(ids(both)).containsExactly(extra,regular);
        assertThat(both.path("totals").path("amount").longValue()).isEqualTo(500);
        assertThat(ids(list(browser,sameDay+"&type=REGULAR"))).containsExactly(regular);
        assertThat(ids(list(browser,sameDay+"&type=EXTRA"))).containsExactly(extra);
        assertThat(ids(list(browser,"?loan_id="+loan+"&from=2026-03-06"))).hasSize(1);
        assertThat(ids(list(browser,"?loan_id="+loan+"&to=2026-03-04"))).hasSize(1);
        // Every created_at is March25; filtering March5 must still match paid_at, not created_at.
    }
    @Test void ownedEmptyLoanFilterReturnsEmptyWhileForeignAndMissingLoansReturnSame404()throws Exception {
        var a=browser();var b=browser();long ownerA=owner(a),ownerB=owner(b),loanA=loan(ownerA),loanB=loan(ownerB);
        payment(ownerB,loanB,"2026-03-05","EXTRA",0,900,0,0,0);
        assertThat(list(a,"?loan_id="+loanA).path("count").longValue()).isZero();
        assertThat(list(a,"").path("count").longValue()).isZero();
        for(long id:List.of(loanB,Long.MAX_VALUE)) {
            var response=get(a,"/api/payments/?loan_id="+id);assertThat(response.statusCode()).isEqualTo(404);
            assertThat(json(response)).isEqualTo(mapper.readTree("""
                {"success":false,"code":"LOAN_NOT_FOUND","message":"대출을 찾을 수 없습니다.","errors":{}}
                """));
        }
        assertThat(list(b,"?loan_id="+loanB).path("totals").path("amount").longValue()).isEqualTo(900);
    }
    @Test void listIncludesPaidDeletedLoansAndPreservesSnapshotEditability()throws Exception {
        var browser=browser();long owner=owner(browser),loan=loan(owner);
        long earlier=payment(owner,loan,"2026-03-09","EXTRA",0,100,0,0,0);
        long later=payment(owner,loan,"2026-03-11","EXTRA",0,200,0,0,0);
        jdbc.update("UPDATE loans SET snapshot_date='2026-03-10' WHERE user_id=? AND id=?",owner,loan);
        for(String status:List.of("PAID","DELETED")) {
            jdbc.update("UPDATE loans SET status=? WHERE user_id=? AND id=?",status,owner,loan);
            for(String query:List.of("","?loan_id="+loan)) {
                var data=list(browser,query);assertThat(ids(data)).containsExactly(later,earlier);
                assertThat(data.path("results").get(0).path("editable").booleanValue()).isTrue();
                assertThat(data.path("results").get(1).path("editable").booleanValue()).isFalse();
            }
        }
    }
    @Test void invalidDateValuesAndReverseRangeUseExactDateRangeError()throws Exception {
        var browser=browser();owner(browser);
        for(String query:List.of("from=2026-02-30","to=2026-13-01","from=2026-3-01","from=0000-01-01","from=",
            "from=2026-03-06&to=2026-03-05","to=not-a-date")) {
            var response=get(browser,"/api/payments/?"+query);assertThat(response.statusCode()).as(query).isEqualTo(400);
            assertThat(json(response)).isEqualTo(mapper.readTree("""
                {"success":false,"code":"INVALID_DATE_RANGE","message":"조회 기간을 확인해 주세요.","errors":{}}
                """));
        }
    }
    @Test void invalidPaginationIdsAndEnumsUseCommonValidationWithoutChangingRevision()throws Exception {
        var browser=browser();long owner=owner(browser);var before=jdbc.queryForMap("SELECT revision,updated_at FROM demo_users WHERE id=?",owner);
        for(String query:List.of("page=0","page=-1","page=1.5","page=1e2","page=","page=2147483648","page_size=0",
            "page_size=101","page_size=abc","loan_id=0","loan_id=-1","loan_id=1.5","loan_id=9223372036854775808","type=regular","type=","type=UNKNOWN")) {
            var response=get(browser,"/api/payments/?"+query);assertThat(response.statusCode()).as(query).isEqualTo(400);
            assertThat(json(response).path("code").asText()).isEqualTo("VALIDATION_ERROR");
            assertThat(json(response).has("data")).isFalse();assertThat(json(response).has("errors")).isTrue();
        }
        assertThat(jdbc.queryForMap("SELECT revision,updated_at FROM demo_users WHERE id=?",owner)).isEqualTo(before);
    }
    @Test void defaultAndMaximumPageSizesAndLargeOffsetsHaveNoTruncationOrIntegerOverflow()throws Exception {
        var browser=browser();long owner=owner(browser),loan=loan(owner);
        for(int i=0;i<21;i++)payment(owner,loan,"2026-03-25","EXTRA",0,100,0,0,0);
        var defaults=list(browser,"");assertThat(ids(defaults)).hasSize(20);assertThat(defaults.path("next_page").intValue()).isEqualTo(2);
        assertThat(ids(list(browser,"?page_size=100"))).hasSize(21);
        var large=list(browser,"?page=2147483647&page_size=100");assertThat(ids(large)).isEmpty();
        assertThat(large.path("count").longValue()).isEqualTo(21);assertThat(large.path("previous_page").intValue()).isEqualTo(2147483646);
        assertThat(large.path("next_page").isNull()).isTrue();
    }
    @Test void listTotalsUseLongAndRefuseOverflowInsteadOfWrappingOrReturningPartialSums()throws Exception {
        var browser=browser();long owner=owner(browser),loan=loan(owner);
        jdbc.update("UPDATE loans SET original_principal=12000000000,remaining_principal=12000000000 WHERE id=?",loan);
        for(int i=0;i<2;i++)payment(owner,loan,"2026-03-25","REGULAR",2000000000L,0,0,0,0);
        assertThat(list(browser,"").path("totals").path("principal_amount").longValue()).isEqualTo(4000000000L);
        for(int i=0;i<2;i++)payment(owner,loan,"2026-03-26","REGULAR",0,0,Long.MAX_VALUE-1,0,0);
        var response=get(browser,"/api/payments/");assertThat(response.statusCode()).isEqualTo(503);
        assertThat(json(response).path("code").asText()).isEqualTo("SERVICE_UNAVAILABLE");assertThat(json(response).has("data")).isFalse();
    }
    @Test void listIsReadOnlyKeepsSourceCollectionsImmutableAndRequiresFinalSlash()throws Exception {
        var browser=browser();long owner=owner(browser),loan=loan(owner),payment=payment(owner,loan,"2026-03-25","REGULAR",100,0,10,0,0);
        long due=due(owner,loan,"2026-03-25");allocate(owner,loan,payment,due,100,10,0);
        var userBefore=jdbc.queryForMap("SELECT revision,updated_at FROM demo_users WHERE id=?",owner);
        var paymentBefore=jdbc.queryForMap("SELECT * FROM payments WHERE id=?",payment);
        var allocationBefore=jdbc.queryForList("SELECT * FROM payment_allocations WHERE payment_id=?",payment);
        var first=list(browser,"");assertThat(list(browser,"")).isEqualTo(first);
        assertThat(jdbc.queryForMap("SELECT revision,updated_at FROM demo_users WHERE id=?",owner)).isEqualTo(userBefore);
        assertThat(jdbc.queryForMap("SELECT * FROM payments WHERE id=?",payment)).isEqualTo(paymentBefore);
        assertThat(jdbc.queryForList("SELECT * FROM payment_allocations WHERE payment_id=?",payment)).isEqualTo(allocationBefore);
        var source=payments.list(owner,new com.loanrun.payment.read.PaymentListFilter(null,null,null,null,1,20));
        assertThatThrownBy(()->source.records().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThat(get(browser,"/api/payments").statusCode()).isEqualTo(404);
    }

    @Test void returnsExactStoredAmountsAllocationAndKstTimestampWithoutOtherRuntimePorts()throws Exception {
        var browser=browser();long owner=owner(browser),loan=loan(owner);
        long payment=payment(owner,loan,"2026-03-25","REGULAR",250000,100000,30000,100,200000);
        long due=due(owner,loan,"2026-03-25");allocate(owner,loan,payment,due,250000,30000,100);
        var response=get(browser,path(payment));assertThat(response.statusCode()).isEqualTo(200);
        assertThat(json(response)).isEqualTo(mapper.readTree("""
            {"success":true,"code":"PAYMENT_DETAIL_SUCCESS","message":"납부 기록 상세 정보를 조회했습니다.",
             "data":{"payment_id":%d,"loan_id":%d,"paid_at":"2026-03-25","amount":380100,"payment_type":"REGULAR",
              "due_item_id":%d,"principal_amount":250000,"interest_amount":30000,"extra_amount":100000,
              "created_at":"2026-03-25T00:30:00+09:00","fee_amount":100,"savings_used_amount":200000,
              "editable":true,"delay_days":0,"due_allocations":[{"due_item_id":%d,"required_paid_amount":280000,"delay_days":0}]}}
            """.formatted(payment,loan,due,due)));
    }
    @Test void multipleAllocationsUseOldestDueAsRepresentativeAndKeepEveryActualAllocation()throws Exception {
        var browser=browser();long owner=owner(browser),loan=loan(owner);
        long payment=payment(owner,loan,"2026-03-27","REGULAR",500000,0,60000,100,0);
        long later=due(owner,loan,"2026-03-25"),earlier=due(owner,loan,"2026-03-10");
        allocate(owner,loan,payment,later,250000,30000,100);allocate(owner,loan,payment,earlier,250000,30000,0);
        var data=detail(browser,payment);
        assertThat(data.path("due_item_id").longValue()).isEqualTo(earlier);assertThat(data.path("delay_days").longValue()).isEqualTo(17);
        var allocations=data.path("due_allocations");assertThat(allocations.size()).isEqualTo(2);
        assertThat(allocations.get(0).path("due_item_id").longValue()).isEqualTo(earlier);
        assertThat(allocations.get(1).path("due_item_id").longValue()).isEqualTo(later);
        assertThat(allocations.get(1).path("delay_days").longValue()).isEqualTo(2);
        assertThat(allocations.get(1).path("required_paid_amount").longValue()).isEqualTo(280000); // Fee remains separate.
    }
    @Test void advancePaymentHasZeroDelayAndExtraHasNoInferredRegularDue()throws Exception {
        var browser=browser();long owner=owner(browser),loan=loan(owner);
        long regular=payment(owner,loan,"2026-03-20","REGULAR",0,0,30000,0,0),due=due(owner,loan,"2026-03-25");
        allocate(owner,loan,regular,due,0,30000,0);
        assertThat(detail(browser,regular).path("delay_days").longValue()).isZero();
        long extra=payment(owner,loan,"2026-03-25","EXTRA",0,100000,0,100,50000);
        var data=detail(browser,extra);assertThat(data.path("payment_type").asText()).isEqualTo("EXTRA");
        assertThat(data.path("due_item_id").isNull()).isTrue();assertThat(data.path("delay_days").isNull()).isTrue();
        assertThat(data.path("due_allocations").isArray()).isTrue();assertThat(data.path("due_allocations").size()).isZero();
        assertThat(data.path("amount").longValue()).isEqualTo(100100);assertThat(data.path("savings_used_amount").longValue()).isEqualTo(50000);
    }
    @Test void missingStoredAllocationsRemainUnknownRatherThanMatchingPaymentToNearbyDue()throws Exception {
        var browser=browser();long owner=owner(browser),loan=loan(owner);
        long payment=payment(owner,loan,"2026-03-25","REGULAR",100,0,0,0,0);due(owner,loan,"2026-03-25");
        var data=detail(browser,payment);assertThat(data.path("due_item_id").isNull()).isTrue();
        assertThat(data.path("delay_days").isNull()).isTrue();assertThat(data.path("due_allocations").size()).isZero();
    }
    @Test void foreignMissingAndNonpositiveIdsAreIndistinguishableAndLoanAllocationsDoNotLeak()throws Exception {
        var a=browser();var b=browser();long ownerA=owner(a),ownerB=owner(b),loanA=loan(ownerA),loanB=loan(ownerB);
        long paymentA=payment(ownerA,loanA,"2026-03-25","REGULAR",100,0,10,0,0);
        long paymentB=payment(ownerB,loanB,"2026-03-25","REGULAR",900,0,90,0,0);
        long dueA=due(ownerA,loanA,"2026-03-25"),dueB=due(ownerB,loanB,"2026-03-25");
        allocate(ownerA,loanA,paymentA,dueA,100,10,0);allocate(ownerB,loanB,paymentB,dueB,900,90,0);
        missing(b,paymentA);missing(a,paymentB);missing(a,Long.MAX_VALUE);missing(a,0);missing(a,-1);
        var data=detail(a,paymentA);assertThat(data.path("amount").longValue()).isEqualTo(110);
        assertThat(data.path("due_allocations").size()).isEqualTo(1);
        assertThat(data.path("due_allocations").get(0).path("due_item_id").longValue()).isEqualTo(dueA);
    }
    @Test void deletedAndPaidLoansRetainActualHistoryAndDoNotChangeStoredAmounts()throws Exception {
        var browser=browser();long owner=owner(browser),loan=loan(owner),payment=payment(owner,loan,"2026-03-25","EXTRA",0,100,0,0,0);
        for(String status:List.of("PAID","DELETED")) {
            jdbc.update("UPDATE loans SET status=? WHERE user_id=? AND id=?",status,owner,loan);
            var data=detail(browser,payment);assertThat(data.path("loan_id").longValue()).isEqualTo(loan);
            assertThat(data.path("extra_amount").longValue()).isEqualTo(100);
        }
    }
    @Test void snapshotDateFreezesEarlierRecordsAndKeepsSameDayAndLaterRecordsEditable()throws Exception {
        var browser=browser();long owner=owner(browser),loan=loan(owner);
        long before=payment(owner,loan,"2026-03-09","EXTRA",0,100,0,0,0);
        long same=payment(owner,loan,"2026-03-10","EXTRA",0,100,0,0,0);
        long after=payment(owner,loan,"2026-03-11","EXTRA",0,100,0,0,0);
        jdbc.update("UPDATE loans SET snapshot_date='2026-03-10' WHERE user_id=? AND id=?",owner,loan);
        assertThat(detail(browser,before).path("editable").booleanValue()).isFalse();
        assertThat(detail(browser,same).path("editable").booleanValue()).isTrue();
        assertThat(detail(browser,after).path("editable").booleanValue()).isTrue();
    }
    @Test void malformedPathUsesCommon400AndFinalSlashIsRequired()throws Exception {
        var browser=browser();long owner=owner(browser),loan=loan(owner),payment=payment(owner,loan,"2026-03-25","EXTRA",0,100,0,0,0);
        for(String value:List.of("abc","9223372036854775808")) {
            var response=get(browser,"/api/payments/"+value+"/");assertThat(response.statusCode()).isEqualTo(400);
            assertThat(json(response).path("code").asText()).isEqualTo("VALIDATION_ERROR");
            assertThat(json(response).has("data")).isFalse();assertThat(json(response).has("errors")).isTrue();
        }
        assertThat(get(browser,"/api/payments/"+payment).statusCode()).isEqualTo(404);
    }
    @Test void repeatedReadsDoNotRewriteSourcesRevisionOrUserTimestampAndSourceListIsImmutable()throws Exception {
        var browser=browser();long owner=owner(browser),loan=loan(owner),payment=payment(owner,loan,"2026-03-25","REGULAR",100,0,10,0,0);
        long due=due(owner,loan,"2026-03-25");allocate(owner,loan,payment,due,100,10,0);
        var userBefore=jdbc.queryForMap("SELECT revision,updated_at FROM demo_users WHERE id=?",owner);
        var loanBefore=jdbc.queryForMap("SELECT * FROM loans WHERE id=?",loan);
        var paymentBefore=jdbc.queryForMap("SELECT * FROM payments WHERE id=?",payment);
        var allocationsBefore=jdbc.queryForList("SELECT * FROM payment_allocations WHERE payment_id=?",payment);
        var first=detail(browser,payment);assertThat(detail(browser,payment)).isEqualTo(first);
        assertThat(jdbc.queryForMap("SELECT revision,updated_at FROM demo_users WHERE id=?",owner)).isEqualTo(userBefore);
        assertThat(jdbc.queryForMap("SELECT * FROM loans WHERE id=?",loan)).isEqualTo(loanBefore);
        assertThat(jdbc.queryForMap("SELECT * FROM payments WHERE id=?",payment)).isEqualTo(paymentBefore);
        assertThat(jdbc.queryForList("SELECT * FROM payment_allocations WHERE payment_id=?",payment)).isEqualTo(allocationsBefore);
        assertThatThrownBy(()->payments.detail(owner,payment).allocations().clear()).isInstanceOf(UnsupportedOperationException.class);
    }
}
