package com.loanrun.loan;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import com.loanrun.common.contract.*;
import com.loanrun.common.contract.FinanceModels.FeeMethod;
import com.loanrun.loan.domain.PreparedLoan;
import com.loanrun.loan.repository.LoanRegistrationRepository;
import com.loanrun.loan.service.LoanInputValidator;
import com.loanrun.repaymentplan.service.PlanSourceQueryService;
import com.loanrun.common.error.ApiException;
import java.net.*;
import java.net.http.*;
import java.time.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

/** Production wiring with no replacement budget bean. Fixture setup is not a public registration success claim. */
@Testcontainers
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties="server.servlet.session.cookie.secure=false")
@Import({SavingCreateUnavailableTest.Config.class, WithoutBudgetModule.class})
class SavingCreateUnavailableTest {
    @Container static final PostgreSQLContainer<?> DB=new PostgreSQLContainer<>("postgres:16.16-bookworm");
    @DynamicPropertySource static void db(DynamicPropertyRegistry r){r.add("spring.datasource.url",DB::getJdbcUrl);r.add("spring.datasource.username",DB::getUsername);r.add("spring.datasource.password",DB::getPassword);}
    @TestConfiguration static class Config {@Bean @Primary Clock savingClock(){return Clock.fixed(Instant.parse("2026-02-28T15:30:00Z"),ZoneId.of("Asia/Seoul"));}}
    @Autowired ObjectProvider<BudgetRecalculationPort> budgets;
    @Autowired LoanRegistrationRepository fixtureRepository;
    @Autowired LoanInputValidator validator;
    @Autowired PlatformTransactionManager transactions;
    @Autowired Clock clock;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlanSourceQueryService planSources;
    @LocalServerPort int port;
    @Test void actualHttpWithoutBudgetImplementationReturns503AndStoresNothing()throws Exception {
        assertThat(budgets.getIfAvailable()).isNull();
        var browser=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();
        var profile=browser.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/users/me/")).GET().build(),HttpResponse.BodyHandlers.ofString());
        long user=mapper.readTree(profile.body()).path("data").path("user_id").longValue();
        var input=new LoanPreviewTest().body();input.put("repayment_method","BULLET");input.putNull("prepayment_policy");
        long loan=new TransactionTemplate(transactions).execute(s->fixtureRepository.insert(new PreparedLoan(validator.validate(user,input),FeeMethod.UNKNOWN,null,null,1200000,List.of()),clock.instant()));
        String body="{\"loan_id\":"+loan+",\"saved_at\":\"2026-03-01\",\"amount\":100}";
        for(int attempt=0;attempt<2;attempt++) {
            var response=browser.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/repayment-savings/"))
                .header("Origin","http://localhost:5173").header("Content-Type","application/json").header("Idempotency-Key","missing-budget")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).as(response.body()).isEqualTo(503);
            assertThat(mapper.readTree(response.body()).path("code").asText()).isEqualTo("SERVICE_UNAVAILABLE");
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM repayment_savings WHERE user_id=?",Long.class,user)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM idempotency_records WHERE user_id=?",Long.class,user)).isZero();
        assertThat(jdbc.queryForObject("SELECT revision FROM demo_users WHERE id=?",Long.class,user)).isZero();
    }
    @Test void updateWithoutBudgetFailsAtomicallyWhileNoChangeSucceedsWithoutInvalidation()throws Exception {
        assertThat(budgets.getIfAvailable()).isNull();
        var browser=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();
        var profile=browser.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/users/me/")).GET().build(),HttpResponse.BodyHandlers.ofString());
        long user=mapper.readTree(profile.body()).path("data").path("user_id").longValue();
        var input=new LoanPreviewTest().body();input.put("repayment_method","BULLET");input.putNull("prepayment_policy");
        long loan=new TransactionTemplate(transactions).execute(s->fixtureRepository.insert(new PreparedLoan(validator.validate(user,input),FeeMethod.UNKNOWN,null,null,1200000,List.of()),clock.instant()));
        long saving=jdbc.queryForObject("INSERT INTO repayment_savings(user_id,loan_id,saved_at,amount,created_at) VALUES (?,?,'2026-03-01',100,CURRENT_TIMESTAMP) RETURNING id",Long.class,user,loan);
        var uri=URI.create("http://localhost:"+port+"/api/repayment-savings/"+saving+"/");
        var changed=browser.send(HttpRequest.newBuilder(uri).header("Origin","http://localhost:5173").header("Content-Type","application/json").header("Idempotency-Key","change")
            .method("PATCH",HttpRequest.BodyPublishers.ofString("{\"amount\":200}")).build(),HttpResponse.BodyHandlers.ofString());
        assertThat(changed.statusCode()).as(changed.body()).isEqualTo(503);
        assertThat(jdbc.queryForObject("SELECT amount FROM repayment_savings WHERE id=?",Long.class,saving)).isEqualTo(100);
        assertThat(jdbc.queryForObject("SELECT revision FROM demo_users WHERE id=?",Long.class,user)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM idempotency_records WHERE user_id=?",Long.class,user)).isZero();
        var unchanged=browser.send(HttpRequest.newBuilder(uri).header("Origin","http://localhost:5173").header("Content-Type","application/json").header("Idempotency-Key","no-change")
            .method("PATCH",HttpRequest.BodyPublishers.ofString("{}")).build(),HttpResponse.BodyHandlers.ofString());
        assertThat(unchanged.statusCode()).as(unchanged.body()).isEqualTo(200);
        assertThat(mapper.readTree(unchanged.body()).path("data").path("report_stale").booleanValue()).isFalse();
        assertThat(jdbc.queryForObject("SELECT revision FROM demo_users WHERE id=?",Long.class,user)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM idempotency_records WHERE user_id=?",Long.class,user)).isEqualTo(1);
    }
    @Test void deleteWithoutRealBudgetReturns503AndRetainsOriginalAndRevision()throws Exception {
        assertThat(budgets.getIfAvailable()).isNull();
        var browser=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();
        var profile=browser.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/users/me/")).GET().build(),HttpResponse.BodyHandlers.ofString());
        long user=mapper.readTree(profile.body()).path("data").path("user_id").longValue();
        var input=new LoanPreviewTest().body();input.put("repayment_method","BULLET");input.putNull("prepayment_policy");
        long loan=new TransactionTemplate(transactions).execute(s->fixtureRepository.insert(new PreparedLoan(validator.validate(user,input),FeeMethod.UNKNOWN,null,null,1200000,List.of()),clock.instant()));
        long saving=jdbc.queryForObject("INSERT INTO repayment_savings(user_id,loan_id,saved_at,amount,created_at) VALUES (?,?,'2026-03-01',100,CURRENT_TIMESTAMP) RETURNING id",Long.class,user,loan);
        var original=jdbc.queryForMap("SELECT * FROM repayment_savings WHERE id=?",saving);
        var r=browser.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/repayment-savings/"+saving+"/"))
            .header("Origin","http://localhost:5173").header("Idempotency-Key","missing-budget-delete").DELETE().build(),HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).as(r.body()).isEqualTo(503);assertThat(mapper.readTree(r.body()).path("code").asText()).isEqualTo("SERVICE_UNAVAILABLE");
        assertThat(jdbc.queryForMap("SELECT * FROM repayment_savings WHERE id=?",saving)).isEqualTo(original);
        assertThat(jdbc.queryForObject("SELECT revision FROM demo_users WHERE id=?",Long.class,user)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM idempotency_records WHERE user_id=?",Long.class,user)).isZero();
    }
    @Test void loanDeletionWithoutRealBudgetReturns503AndPreservesStatusWithOrWithoutKey()throws Exception {
        assertThat(budgets.getIfAvailable()).isNull();
        var browser=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();
        var profile=browser.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/users/me/")).GET().build(),HttpResponse.BodyHandlers.ofString());
        long user=mapper.readTree(profile.body()).path("data").path("user_id").longValue();
        var input=new LoanPreviewTest().body();input.put("repayment_method","BULLET");input.putNull("prepayment_policy");
        long loan=new TransactionTemplate(transactions).execute(s->fixtureRepository.insert(new PreparedLoan(validator.validate(user,input),FeeMethod.UNKNOWN,null,null,1200000,List.of()),clock.instant()));
        var original=jdbc.queryForMap("SELECT * FROM loans WHERE id=?",loan);
        for(boolean keyed:List.of(false,true)) {
            var builder=HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/loans/"+loan+"/")).header("Origin","http://localhost:5173");
            if(keyed)builder.header("Idempotency-Key","missing-budget-loan");
            var r=browser.send(builder.DELETE().build(),HttpResponse.BodyHandlers.ofString());assertThat(r.statusCode()).as(r.body()).isEqualTo(503);
            assertThat(mapper.readTree(r.body()).path("code").asText()).isEqualTo("SERVICE_UNAVAILABLE");
        }
        assertThat(jdbc.queryForMap("SELECT * FROM loans WHERE id=?",loan)).isEqualTo(original);
        assertThat(jdbc.queryForObject("SELECT revision FROM demo_users WHERE id=?",Long.class,user)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM idempotency_records WHERE user_id=?",Long.class,user)).isZero();
    }
    @Test void v2BaselineCanBeReadButMissingVersionTableIsNotTreatedAsNoChanges()throws Exception {
        long user=jdbc.queryForObject("INSERT INTO demo_users(session_digest,created_at,updated_at) VALUES (?,?,?) RETURNING id",Long.class,
            java.util.UUID.randomUUID().toString(),java.sql.Timestamp.from(clock.instant()),java.sql.Timestamp.from(clock.instant()));
        var input=new LoanPreviewTest().body();var validated=validator.validate(user,input);
        long loan=new TransactionTemplate(transactions).execute(s->fixtureRepository.insert(new PreparedLoan(validated,FeeMethod.UNKNOWN,null,1200000L,0,List.of()),clock.instant()));
        assertThat(planSources.registration(user,loan).version()).isEqualTo(1);
        assertThatThrownBy(()->planSources.forDate(user,loan,LocalDate.of(2026,3,25),LocalDate.of(2026,3,1)))
            .isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code()).isEqualTo("SERVICE_UNAVAILABLE"));
        assertThat(jdbc.queryForObject("SELECT to_regclass('repayment_plan_versions')",String.class)).isNull();
    }
}
