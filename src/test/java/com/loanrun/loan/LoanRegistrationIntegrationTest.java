package com.loanrun.loan;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.loanrun.common.contract.*;
import com.loanrun.loan.service.LoanRegistrationService;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@Testcontainers
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties="server.servlet.session.cookie.secure=false")
@Import(LoanRegistrationIntegrationTest.Config.class)
class LoanRegistrationIntegrationTest {
    @Container static final PostgreSQLContainer<?> DB=new PostgreSQLContainer<>("postgres:16.16-bookworm");
    @DynamicPropertySource static void db(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",DB::getJdbcUrl);r.add("spring.datasource.username",DB::getUsername);r.add("spring.datasource.password",DB::getPassword);
    }
    @TestConfiguration static class Config {
        @Bean @Primary Clock fixed(){return Clock.fixed(Instant.parse("2025-12-31T15:00:00Z"),ZoneId.of("Asia/Seoul"));}
    }
    @MockitoBean BudgetRecalculationPort budget;
    @MockitoBean ProductQueryPort products;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired LoanRegistrationService registration;
    @LocalServerPort int port;
    HttpClient browser(){return HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();}
    HttpResponse<String> request(HttpClient browser,String path,String body,String key) throws Exception {
        var b=HttpRequest.newBuilder(URI.create("http://localhost:"+port+path)).header("Origin","http://localhost:5173").header("Content-Type","application/json");
        if(key!=null)b.header("Idempotency-Key",key);
        return browser.send(body==null?b.GET().build():b.POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }
    long user(HttpClient browser)throws Exception{return mapper.readTree(request(browser,"/api/users/me/",null,null).body()).path("data").path("user_id").longValue();}
    ObjectNode input()throws Exception{return new LoanPreviewTest().body();}
    JsonNode json(HttpResponse<String> response)throws Exception{return mapper.readTree(response.body());}
    long count(String table,long owner){return jdbc.queryForObject("SELECT count(*) FROM "+table+" WHERE user_id=?",Long.class,owner);}
    long revision(long owner){return jdbc.queryForObject("SELECT revision FROM demo_users WHERE id=?",Long.class,owner);}

    @Test void persistsOriginalsDefaultPlanFirstDueAndExact201Schema()throws Exception {
        var browser=browser();long owner=user(browser);var input=input();input.put("remaining_principal",6000000);input.put("contractual_monthly_payment",280000);
        var response=request(browser,"/api/loans/",input.toString(),null);assertThat(response.statusCode()).isEqualTo(201);
        var root=json(response);assertThat(root.path("code").asText()).isEqualTo("LOAN_CREATE_SUCCESS");assertThat(root.has("errors")).isFalse();
        var data=root.path("data");long id=data.path("loan_id").longValue();
        assertThat(data.path("estimated_monthly_payment").longValue()).isEqualTo(280000);
        assertThat(data.path("paid_principal").longValue()).isEqualTo(6000000);assertThat(data.path("progress_percent").decimalValue()).isEqualByComparingTo("50.00");
        assertThat(data.path("snapshot_date").asText()).isEqualTo("2026-01-01");assertThat(data.path("rate_updated_at").asText()).endsWith("+09:00");
        assertThat(data.path("prepayment_fee_rate").isNull()).isTrue();assertThat(data.path("status").asText()).isEqualTo("ACTIVE");
        assertThat(count("loans",owner)).isEqualTo(1);assertThat(count("loan_balance_snapshots",owner)).isEqualTo(1);
        assertThat(count("loan_rate_histories",owner)).isEqualTo(1);assertThat(count("due_items",owner)).isEqualTo(1);
        assertThat(count("payments",owner)).isZero();assertThat(count("repayment_savings",owner)).isZero();assertThat(revision(owner)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT initial_plan_snapshot->>'mode' FROM loans WHERE id=?",String.class,id)).isEqualTo("MATURITY");
        assertThat(jdbc.queryForObject("SELECT (initial_plan_snapshot->>'loan_id')::bigint FROM loans WHERE id=?",Long.class,id)).isEqualTo(id);
        assertThat(jdbc.queryForObject("SELECT fixed_principal FROM loans WHERE id=?",Long.class,id)).isEqualTo(500000);
        assertThat(jdbc.queryForObject("SELECT principal_amount FROM due_items WHERE loan_id=?",Long.class,id)).isEqualTo(250000);
        verify(budget).recalculateFrom(owner,YearMonth.of(2026,1));
        assertThat(request(browser,"/api/loans",input.toString(),null).statusCode()).isEqualTo(404);
    }
    @Test void bulletInitialAssetsAreNotActualSavingsAndZeroPrincipalIsPaid()throws Exception {
        var browser=browser();long owner=user(browser);var body=input();body.put("repayment_method","BULLET");body.putNull("prepayment_policy");body.put("initial_savings_balance",2000000);
        var response=request(browser,"/api/loans/",body.toString(),null);assertThat(response.statusCode()).isEqualTo(201);var data=json(response).path("data");
        assertThat(data.path("required_monthly_saving").longValue()).isEqualTo(833334);assertThat(data.path("savings_balance").longValue()).isEqualTo(2000000);
        assertThat(count("repayment_savings",owner)).isZero();assertThat(count("payments",owner)).isZero();
        long id=data.path("loan_id").longValue();assertThat(jdbc.queryForObject("SELECT (initial_plan_snapshot->>'monthly_saving')::bigint FROM loans WHERE id=?",Long.class,id)).isEqualTo(833334);
        body=input();body.put("remaining_principal",0);response=request(browser,"/api/loans/",body.toString(),null);
        assertThat(response.statusCode()).isEqualTo(201);assertThat(json(response).path("data").path("status").asText()).isEqualTo("PAID");
        assertThat(count("due_items",owner)).isEqualTo(1);assertThat(revision(owner)).isEqualTo(2);
    }
    @Test void optionalIdempotencyReplaysBeforeRevalidationAndDifferentBodyConflicts()throws Exception {
        var browser=browser();long owner=user(browser);var body=input();var first=request(browser,"/api/loans/",body.toString(),"same");
        assertThat(first.statusCode()).isEqualTo(201);
        // Replay does not invoke recalculation again; reordered JSON fields retain the same canonical hash.
        doThrow(new IllegalStateException("test failure")).when(budget).recalculateFrom(eq(owner),any());
        var reversed=mapper.createObjectNode();var fields=new ArrayList<String>();body.fieldNames().forEachRemaining(fields::add);Collections.reverse(fields);fields.forEach(k->reversed.set(k,body.get(k)));
        var second=request(browser,"/api/loans/",reversed.toString(),"same");assertThat(second.statusCode()).isEqualTo(201);assertThat(json(second)).isEqualTo(json(first));
        body.put("annual_rate",7);var conflict=request(browser,"/api/loans/",body.toString(),"same");assertThat(conflict.statusCode()).isEqualTo(409);assertThat(json(conflict).path("code").asText()).isEqualTo("IDEMPOTENCY_CONFLICT");
        assertThat(count("loans",owner)).isEqualTo(1);assertThat(count("idempotency_records",owner)).isEqualTo(1);assertThat(revision(owner)).isEqualTo(1);
    }
    @Test void sameInputWithoutKeyCreatesIndependentLoansAndOwnersAreIsolated()throws Exception {
        var a=browser();var b=browser();long userA=user(a),userB=user(b);String body=input().toString();
        var one=request(a,"/api/loans/",body,null);var two=request(a,"/api/loans/",body,null);var three=request(b,"/api/loans/",body,"one");
        assertThat(one.statusCode()).isEqualTo(201);assertThat(two.statusCode()).isEqualTo(201);assertThat(three.statusCode()).isEqualTo(201);
        assertThat(json(one).path("data").path("loan_id")).isNotEqualTo(json(two).path("data").path("loan_id"));
        assertThat(count("loans",userA)).isEqualTo(2);assertThat(count("loans",userB)).isEqualTo(1);
        var bad=input();bad.put("user_id",userA);assertThat(request(b,"/api/loans/",bad.toString(),null).statusCode()).isEqualTo(400);
        assertThat(count("loans",userB)).isEqualTo(1);
    }
    @Test void recalculationFailureRollsBackEveryFinancialWriteAndResponseKey()throws Exception {
        var browser=browser();long owner=user(browser);doAnswer(invocation->{assertThat(count("loans",owner)).isEqualTo(1);throw new IllegalStateException("test failure");}).when(budget).recalculateFrom(eq(owner),any());
        var response=request(browser,"/api/loans/",input().toString(),"retry");assertThat(response.statusCode()).isEqualTo(500);
        for(var table:List.of("loans","loan_balance_snapshots","loan_rate_histories","due_items","idempotency_records"))assertThat(count(table,owner)).as(table).isZero();
        assertThat(revision(owner)).isZero();doNothing().when(budget).recalculateFrom(eq(owner),any());
        assertThat(request(browser,"/api/loans/",input().toString(),"retry").statusCode()).isEqualTo(201);assertThat(revision(owner)).isEqualTo(1);
    }
    @Test void concurrentRegistrationHonorsSevenActiveLimit()throws Exception {
        var browser=browser();long owner=user(browser);String body=input().toString();
        try(var pool=Executors.newFixedThreadPool(8)) {
            List<Callable<Integer>> jobs=new ArrayList<>();for(int i=0;i<8;i++){String key="create-"+i;jobs.add(()->request(browser,"/api/loans/",body,key).statusCode());}
            var futures=pool.invokeAll(jobs);List<Integer> statuses=new ArrayList<>();for(var future:futures)statuses.add(future.get());
            assertThat(statuses.stream().filter(s->s==201).count()).isEqualTo(7);assertThat(statuses.stream().filter(s->s==409).count()).isEqualTo(1);
        }
        assertThat(count("loans",owner)).isEqualTo(7);assertThat(count("loan_balance_snapshots",owner)).isEqualTo(7);assertThat(revision(owner)).isEqualTo(7);
        var limit=request(browser,"/api/loans/",body,null);assertThat(json(limit).path("code").asText()).isEqualTo("LOAN_LIMIT_EXCEEDED");
    }
    @Test void concurrentIdenticalKeyCommitsOnlyOneAggregate()throws Exception {
        var browser=browser();long owner=user(browser);String body=input().toString();
        try(var pool=Executors.newFixedThreadPool(3)) {
            List<Callable<HttpResponse<String>>> jobs=List.of(()->request(browser,"/api/loans/",body,"race"),()->request(browser,"/api/loans/",body,"race"),()->request(browser,"/api/loans/",body,"race"));
            var futures=pool.invokeAll(jobs);Set<String> responses=new HashSet<>();for(var future:futures){var response=future.get();assertThat(response.statusCode()).isEqualTo(201);responses.add(response.body());}
            assertThat(responses).hasSize(1);
        }
        assertThat(count("loans",owner)).isEqualTo(1);assertThat(count("idempotency_records",owner)).isEqualTo(1);assertThat(revision(owner)).isEqualTo(1);
    }
    @Test void postgresOwnershipFkRejectsCrossOwnerHistory()throws Exception {
        var a=browser();var b=browser();user(a);long foreign=user(b);var response=request(a,"/api/loans/",input().toString(),null);long id=json(response).path("data").path("loan_id").longValue();
        assertThatThrownBy(()->jdbc.update("INSERT INTO loan_rate_histories(user_id,loan_id,annual_rate,effective_date,created_at) VALUES (?,?,6,CURRENT_DATE,CURRENT_TIMESTAMP)",foreign,id)).isInstanceOf(DataIntegrityViolationException.class);
    }
    @Test void graceVariableAndExemptFeeConditionsPreserveSourceTerms()throws Exception {
        var browser=browser();long owner=user(browser);var body=input();
        body.put("repayment_method","GRACE_INSTALLMENT");body.put("grace_months",15);body.put("post_grace_method","EQUAL_PAYMENT");
        body.put("rate_type","VARIABLE");body.put("rate_reset_months",6);body.put("next_rate_reset_date","2026-06-01");
        body.put("prepayment_fee_method","FIXED");body.put("prepayment_fee_rate",1);body.put("fee_exempt",true);
        var response=request(browser,"/api/loans/",body.toString(),null);assertThat(response.statusCode()).isEqualTo(201);var data=json(response).path("data");
        assertThat(data.path("estimated_monthly_payment").longValue()).isEqualTo(60000);
        assertThat(data.path("rate_sync_status").asText()).isEqualTo("MANUAL_REQUIRED");
        assertThat(data.path("prepayment_fee_method").asText()).isEqualTo("FIXED");assertThat(data.path("fee_exempt").booleanValue()).isTrue();
        long id=data.path("loan_id").longValue();assertThat(jdbc.queryForObject("SELECT base_anchor_amount FROM loans WHERE id=?",Long.class,id)).isPositive();
        assertThat(jdbc.queryForObject("SELECT principal_amount FROM due_items WHERE loan_id=?",Long.class,id)).isZero();
        assertThat(count("loan_rate_histories",owner)).isEqualTo(1);
    }

    @Test void explicitZeroBankObligationIsPreservedSeparatelyFromEstimatedInterest()throws Exception {
        var browser=browser();user(browser);var body=input();body.put("contractual_monthly_payment",0);
        var response=request(browser,"/api/loans/",body.toString(),null);assertThat(response.statusCode()).isEqualTo(201);
        long id=json(response).path("data").path("loan_id").longValue();
        assertThat(jdbc.queryForObject("SELECT contractual_amount FROM due_items WHERE loan_id=?",Long.class,id)).isZero();
        assertThat(jdbc.queryForObject("SELECT required_amount FROM due_items WHERE loan_id=?",Long.class,id)).isZero();
        assertThat(jdbc.queryForObject("SELECT interest_amount FROM due_items WHERE loan_id=?",Long.class,id)).isEqualTo(60000);
    }

    @Test void catalogResolutionRunsOutsideTransactionAndReplayDoesNotQueryIt()throws Exception {
        var browser=browser();user(browser);var body=input();body.put("product_id",42);body.remove("product_name");body.remove("institution_name");
        when(products.findEligibleProduct(42)).thenAnswer(invocation->{
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return new FinanceModels.EligibleProduct(42,1,"서버은행","서버상품",FinanceModels.LoanType.MORTGAGE,List.of(FinanceModels.RepaymentMethod.EQUAL_PRINCIPAL));
        });
        var first=request(browser,"/api/loans/",body.toString(),"catalog-key");assertThat(first.statusCode()).isEqualTo(201);
        assertThat(json(first).path("data").path("product_name").asText()).isEqualTo("서버상품");
        when(products.findEligibleProduct(42)).thenThrow(new IllegalStateException("catalog unavailable"));
        var replay=request(browser,"/api/loans/",body.toString(),"catalog-key");assertThat(replay.statusCode()).isEqualTo(201);assertThat(json(replay)).isEqualTo(json(first));
        verify(products,times(1)).findEligibleProduct(42);
    }
    @Test void v2UpgradePreservesExistingV1UserAndValidates() {
        var flyway=org.flywaydb.core.Flyway.configure().dataSource(jdbc.getDataSource()).schemas("registration_upgrade").defaultSchema("registration_upgrade").target("1").load();
        flyway.migrate();
        jdbc.update("INSERT INTO registration_upgrade.demo_users(session_digest,name,created_at,updated_at) VALUES ('upgrade-user','보존사용자',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        var upgrade=org.flywaydb.core.Flyway.configure().dataSource(jdbc.getDataSource()).schemas("registration_upgrade").defaultSchema("registration_upgrade").load();
        assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(1);upgrade.validate();
        assertThat(jdbc.queryForObject("SELECT name FROM registration_upgrade.demo_users WHERE session_digest='upgrade-user'",String.class)).isEqualTo("보존사용자");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM registration_upgrade.loans",Long.class)).isZero();
    }

}
