package com.loanrun.payment;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.fasterxml.jackson.databind.*;
import com.loanrun.common.contract.BudgetRecalculationPort;
import java.net.*;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@Testcontainers
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties="server.servlet.session.cookie.secure=false")
@Import(DueConfirmationIntegrationTest.Config.class)
class DueConfirmationIntegrationTest {
    @Container static final PostgreSQLContainer<?> DB=new PostgreSQLContainer<>("postgres:16.16-bookworm");
    @DynamicPropertySource static void db(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",DB::getJdbcUrl);r.add("spring.datasource.username",DB::getUsername);r.add("spring.datasource.password",DB::getPassword);
    }
    @TestConfiguration static class Config {
        @Bean @Primary Clock fixed(){return Clock.fixed(Instant.parse("2026-03-30T15:30:00Z"),ZoneId.of("Asia/Seoul"));}
    }
    @MockitoBean BudgetRecalculationPort budget;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @LocalServerPort int port;
    HttpClient browser(){return HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();}
    String path(long due){return "/api/payments/schedule/"+due+"/confirmation/";}
    HttpResponse<String> request(HttpClient browser,String method,String path,String body,String key)throws Exception {
        var b=HttpRequest.newBuilder(URI.create("http://localhost:"+port+path)).header("Origin","http://localhost:5173").header("Content-Type","application/json");
        if(key!=null)b.header("Idempotency-Key",key);
        return browser.send(b.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }
    HttpResponse<String> confirm(HttpClient browser,long due,boolean value,String key)throws Exception {
        return request(browser,"PATCH",path(due),"{\"unpaid_confirmed\":"+value+"}",key);
    }
    JsonNode json(HttpResponse<String> response)throws Exception{return mapper.readTree(response.body());}
    long owner(HttpClient browser)throws Exception{return json(request(browser,"GET","/api/users/me/",null,null)).path("data").path("user_id").longValue();}
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
    long due(long owner,long loan,String date,long amount){return jdbc.queryForObject("""
        INSERT INTO due_items(user_id,loan_id,due_date,principal_amount,interest_amount,fee_amount,contractual_amount,required_amount,terms_revision,created_at)
        VALUES (?,?,?,250000,30000,0,?,?,1,CURRENT_TIMESTAMP) RETURNING id
        """,Long.class,owner,loan,LocalDate.parse(date),amount,amount);}
    long revision(long owner){return jdbc.queryForObject("SELECT revision FROM demo_users WHERE id=?",Long.class,owner);}
    boolean flag(long due){return jdbc.queryForObject("SELECT confirmed_unpaid FROM due_items WHERE id=?",Boolean.class,due);}
    long keys(long owner){return jdbc.queryForObject("SELECT count(*) FROM idempotency_records WHERE user_id=?",Long.class,owner);}
    void allocated(long owner,long loan,long due,long amount){
        long payment=jdbc.queryForObject("""
            INSERT INTO payments(user_id,loan_id,paid_at,payment_type,amount,principal_amount,extra_amount,interest_amount,fee_amount,savings_used_amount,created_at)
            VALUES (?,?,'2026-03-25','REGULAR',?,?,0,0,0,0,CURRENT_TIMESTAMP) RETURNING id
            """,Long.class,owner,loan,amount,amount);
        jdbc.update("INSERT INTO payment_allocations(user_id,loan_id,payment_id,due_item_id,principal_amount,interest_amount,fee_amount) VALUES (?,?,?,?,?,0,0)",owner,loan,payment,due,amount);
    }
    void error(HttpResponse<String> response,int status,String code)throws Exception {
        assertThat(response.statusCode()).isEqualTo(status);assertThat(json(response).path("code").asText()).isEqualTo(code);
        assertThat(json(response).has("data")).isFalse();assertThat(json(response).has("errors")).isTrue();
    }

    @Test void confirmsAndCancelsExactSchemaWithoutCreatingPaymentOrReducingAssets()throws Exception {
        var browser=browser();long owner=owner(browser),loan=loan(owner),due=due(owner,loan,"2026-03-10",280000);
        var before=jdbc.queryForMap("SELECT * FROM loans WHERE id=?",loan);
        var confirmed=confirm(browser,due,true,"confirm");assertThat(confirmed.statusCode()).isEqualTo(200);
        assertThat(json(confirmed)).isEqualTo(mapper.readTree("""
            {"success":true,"code":"DUE_CONFIRMATION_SUCCESS","message":"정기 납부 미납 여부 확인 성공",
             "data":{"due_item_id":%d,"loan_id":%d,"due_date":"2026-03-10","required_amount":280000,
              "paid_required_amount":0,"outstanding_amount":280000,"unpaid_confirmed":true,"status":"UNPAID","display_status":"UNPAID","report_stale":true}}
            """.formatted(due,loan)));
        assertThat(flag(due)).isTrue();assertThat(revision(owner)).isEqualTo(1);
        var cancelled=confirm(browser,due,false,"cancel");assertThat(cancelled.statusCode()).isEqualTo(200);
        assertThat(json(cancelled).path("data").path("status").asText()).isEqualTo("UNRECORDED");
        assertThat(json(cancelled).path("data").path("unpaid_confirmed").booleanValue()).isFalse();
        assertThat(flag(due)).isFalse();assertThat(revision(owner)).isEqualTo(2);assertThat(keys(owner)).isEqualTo(2);
        assertThat(jdbc.queryForMap("SELECT * FROM loans WHERE id=?",loan)).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payments WHERE user_id=?",Long.class,owner)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM repayment_savings WHERE user_id=?",Long.class,owner)).isZero();
        verify(budget,times(2)).recalculateFrom(owner,YearMonth.of(2026,3));
    }
    @Test void onlyPastDatesQualifyUsingKstRatherThanUtcDay()throws Exception {
        var browser=browser();long owner=owner(browser),loan=loan(owner);
        long past=due(owner,loan,"2026-03-30",100);assertThat(confirm(browser,past,true,"past").statusCode()).isEqualTo(200);
        for(String date:List.of("2026-03-31","2026-04-01")) {
            long due=due(owner,loan,date,100);
            error(confirm(browser,due,true,"date-"+date),400,"DUE_CONFIRMATION_VALIDATION_ERROR");
            error(confirm(browser,due,false,"cancel-"+date),400,"DUE_CONFIRMATION_VALIDATION_ERROR");assertThat(flag(due)).isFalse();
        }
        assertThat(revision(owner)).isEqualTo(1);assertThat(keys(owner)).isEqualTo(1);
    }
    @Test void partialPaidAndZeroBankObligationConflictForBothConfirmationAndCancellation()throws Exception {
        var browser=browser();long owner=owner(browser),loan=loan(owner);
        long partial=due(owner,loan,"2026-03-10",100),paid=due(owner,loan,"2026-03-11",100),zero=due(owner,loan,"2026-03-12",0);
        allocated(owner,loan,partial,50);allocated(owner,loan,paid,100);
        for(long due:List.of(partial,paid,zero))for(boolean flag:List.of(true,false))
            error(confirm(browser,due,flag,due+"-"+flag),409,"DUE_CONFIRMATION_CONFLICT");
        assertThat(revision(owner)).isZero();assertThat(keys(owner)).isZero();verifyNoInteractions(budget);
    }
    @Test void foreignMissingDeletedDueAndPaidLoanDoNotAllowMutation()throws Exception {
        var a=browser();var b=browser();long ownerA=owner(a);owner(b);long loan=loan(ownerA),due=due(ownerA,loan,"2026-03-10",100);
        for(long id:List.of(due,Long.MAX_VALUE,0L,-1L))error(confirm(b,id,true,"foreign-"+id),404,"DUE_ITEM_NOT_FOUND");
        jdbc.update("UPDATE loans SET status='PAID' WHERE id=?",loan);error(confirm(a,due,true,"paid-loan"),409,"DUE_CONFIRMATION_CONFLICT");
        jdbc.update("UPDATE loans SET status='DELETED' WHERE id=?",loan);error(confirm(a,due,true,"deleted"),404,"DUE_ITEM_NOT_FOUND");
        assertThat(flag(due)).isFalse();assertThat(revision(ownerA)).isZero();assertThat(keys(ownerA)).isZero();verifyNoInteractions(budget);
    }
    @Test void strictBooleanBodyRequiredKeyAndInvalidRoutesUseEnvelopesWithoutWrites()throws Exception {
        var browser=browser();long owner=owner(browser),loan=loan(owner),due=due(owner,loan,"2026-03-10",100);
        for(String body:List.of("{}","[]","null","{\"unpaid_confirmed\":null}","{\"unpaid_confirmed\":\"true\"}",
            "{\"unpaid_confirmed\":1}","{\"unpaid_confirmed\":true,\"user_id\":1}"))
            error(request(browser,"PATCH",path(due),body,"body"),400,"VALIDATION_ERROR");
        for(String key:Arrays.asList(null,"","x".repeat(129)))error(confirm(browser,due,true,key),400,"VALIDATION_ERROR");
        error(request(browser,"PATCH","/api/payments/schedule/abc/confirmation/","{\"unpaid_confirmed\":true}","bad-id"),400,"VALIDATION_ERROR");
        assertThat(request(browser,"PATCH",path(due).substring(0,path(due).length()-1),"{\"unpaid_confirmed\":true}","slash").statusCode()).isEqualTo(404);
        assertThat(flag(due)).isFalse();assertThat(revision(owner)).isZero();assertThat(keys(owner)).isZero();verifyNoInteractions(budget);
    }
    @Test void sameKeyReplaysBeforeCurrentStateAndProviderChecksWhileDifferentBodyConflicts()throws Exception {
        var browser=browser();long owner=owner(browser),loan=loan(owner),due=due(owner,loan,"2026-03-10",100);
        var first=confirm(browser,due,true,"once");assertThat(first.statusCode()).isEqualTo(200);
        allocated(owner,loan,due,100);doThrow(new IllegalStateException("test dependency failure")).when(budget).recalculateFrom(eq(owner),any());
        var replay=confirm(browser,due,true,"once");assertThat(replay.statusCode()).isEqualTo(200);assertThat(json(replay)).isEqualTo(json(first));
        error(confirm(browser,due,false,"once"),409,"DUE_CONFIRMATION_CONFLICT");
        error(confirm(browser,due,false,"new-key"),409,"DUE_CONFIRMATION_CONFLICT");
        assertThat(revision(owner)).isEqualTo(1);assertThat(keys(owner)).isEqualTo(1);verify(budget,times(1)).recalculateFrom(owner,YearMonth.of(2026,3));
    }
    @Test void sameKeyOnAnotherDueHasIndependentScopeAndNewKeyReconfirmationIsAccepted()throws Exception {
        var browser=browser();long owner=owner(browser),loan=loan(owner),first=due(owner,loan,"2026-03-10",100),second=due(owner,loan,"2026-03-11",200);
        assertThat(confirm(browser,first,true,"key").statusCode()).isEqualTo(200);
        assertThat(confirm(browser,second,true,"key").statusCode()).isEqualTo(200);
        assertThat(confirm(browser,first,true,"reconfirm").statusCode()).isEqualTo(200);
        assertThat(flag(first)).isTrue();assertThat(flag(second)).isTrue();assertThat(revision(owner)).isEqualTo(3);assertThat(keys(owner)).isEqualTo(3);
    }
    @Test void storedResponseCannotBeReplayedByAnotherAnonymousSession()throws Exception {
        var a=browser();var b=browser();long ownerA=owner(a),ownerB=owner(b),loan=loan(ownerA),due=due(ownerA,loan,"2026-03-10",100);
        assertThat(confirm(a,due,true,"shared-key").statusCode()).isEqualTo(200);
        error(confirm(b,due,true,"shared-key"),404,"DUE_ITEM_NOT_FOUND");
        assertThat(revision(ownerA)).isEqualTo(1);assertThat(revision(ownerB)).isZero();assertThat(keys(ownerB)).isZero();
    }
    @Test void actualExtraPaymentDoesNotSatisfyOrBlockTheRegularDue()throws Exception {
        var browser=browser();long owner=owner(browser),loan=loan(owner),due=due(owner,loan,"2026-03-10",100);
        jdbc.update("""
            INSERT INTO payments(user_id,loan_id,paid_at,payment_type,amount,principal_amount,extra_amount,interest_amount,fee_amount,savings_used_amount,created_at)
            VALUES (?,?,'2026-03-10','EXTRA',100,0,100,0,0,0,CURRENT_TIMESTAMP)
            """,owner,loan);
        jdbc.update("UPDATE loans SET remaining_principal=11999900 WHERE id=?",loan);
        var response=confirm(browser,due,true,"extra-separate");assertThat(response.statusCode()).isEqualTo(200);
        assertThat(json(response).path("data").path("status").asText()).isEqualTo("UNPAID");
        assertThat(json(response).path("data").path("outstanding_amount").longValue()).isEqualTo(100);
        assertThat(jdbc.queryForObject("SELECT remaining_principal FROM loans WHERE id=?",Long.class,loan)).isEqualTo(11999900);
    }
    @Test void budgetFailureRollsBackFlagRevisionAndKeyAndAllowsRetry()throws Exception {
        var browser=browser();long owner=owner(browser),loan=loan(owner),due=due(owner,loan,"2026-02-25",100);
        var sawPendingFlag=new java.util.concurrent.atomic.AtomicBoolean();
        doAnswer(invocation->{sawPendingFlag.set(flag(due));throw new IllegalStateException("test dependency failure");})
            .when(budget).recalculateFrom(owner,YearMonth.of(2026,2));
        // Recalculation sees the pending flag in the same transaction; its failure must roll it back.
        error(confirm(browser,due,true,"retry"),500,"INTERNAL_SERVER_ERROR");
        assertThat(sawPendingFlag).isTrue();
        assertThat(flag(due)).isFalse();assertThat(revision(owner)).isZero();assertThat(keys(owner)).isZero();
        doNothing().when(budget).recalculateFrom(owner,YearMonth.of(2026,2));
        assertThat(confirm(browser,due,true,"retry").statusCode()).isEqualTo(200);assertThat(flag(due)).isTrue();
        assertThat(revision(owner)).isEqualTo(1);assertThat(keys(owner)).isEqualTo(1);
    }
    @Test void simultaneousSameKeyRequestsCommitOnceUnderTheRealUserLock()throws Exception {
        var browser=browser();long owner=owner(browser),loan=loan(owner),due=due(owner,loan,"2026-03-10",100);
        var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var tasks=new ArrayList<Future<HttpResponse<String>>>();
            for(int i=0;i<2;i++)tasks.add(pool.submit(()->{start.await();return confirm(browser,due,true,"parallel");}));
            start.countDown();var bodies=new HashSet<String>();
            for(var task:tasks){var response=task.get(15,TimeUnit.SECONDS);assertThat(response.statusCode()).isEqualTo(200);bodies.add(response.body());}
            assertThat(bodies).hasSize(1);
        }
        assertThat(flag(due)).isTrue();assertThat(revision(owner)).isEqualTo(1);assertThat(keys(owner)).isEqualTo(1);
        verify(budget,times(1)).recalculateFrom(owner,YearMonth.of(2026,3));
    }
}
