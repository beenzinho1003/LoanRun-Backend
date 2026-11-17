package com.loanrun;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loanrun.common.contract.CurrentUserProvider;
import com.loanrun.common.contract.RevisionPort;
import com.loanrun.common.contract.LoanQueryPort;
import com.loanrun.common.contract.PlanQueryPort;
import com.loanrun.common.contract.FinanceModels.*;
import com.loanrun.common.idempotency.IdempotencyService;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.LocalDate;
import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"loanrun.demo-mode=true", "server.servlet.session.cookie.secure=false"})
@Import(BootstrapIntegrationTest.TestConfig.class)
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class OnboardingIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("postgres:16.16-bookworm");
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DB::getJdbcUrl);
        registry.add("spring.datasource.username", DB::getUsername);
        registry.add("spring.datasource.password", DB::getPassword);
    }

    @LocalServerPort int port;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired Clock clock;
    @Autowired IdempotencyService idempotency;
    @Autowired RevisionPort revisions;
    @Autowired TransactionTemplate transactions;
    @MockitoBean LoanQueryPort loans;
    @MockitoBean PlanQueryPort plans;

    private HttpClient browser() {
        return HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
    }
    private HttpResponse<String> request(HttpClient client, String method, String path, String body, String origin) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (origin != null) builder.header("Origin", origin);
        builder.header("Content-Type", "application/json");
        return client.send(builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
    private JsonNode json(HttpResponse<String> response) throws Exception { return mapper.readTree(response.body()); }
    private long userId(HttpClient browser) throws Exception {
        return json(request(browser, "GET", "/api/users/me/", null, null)).path("data").path("user_id").longValue();
    }

    private String regularFinance() {
        return """
            {"monthly_income":3000000,"income_type":"REGULAR","payday":25,"fixed_essential_expenses":800000,
            "variable_essential_expenses":200000,"discretionary_expenses":500000,"savings":200000,"emergency_fund":null}
            """;
    }

    private LoanTerms loan(long user, RepaymentMethod method, PrepaymentPolicy policy) {
        return new LoanTerms(101, user, "가상 대출", LoanType.CREDIT, 12000000, 12000000, LocalDate.of(2026,11,17),
            new BigDecimal("6"), RateType.FIXED, method, null, null, LocalDate.of(2026,11,25), 25,
            LocalDate.of(2027,11,25), null, null, null, policy, FeeMethod.NONE, null, null, null, 0, 0, LoanStatus.ACTIVE, null);
    }

    @Test void missingPolicyOrIncompleteBulletGoalCannotCompleteOrChangeRevision() throws Exception {
        var client=browser(); long id=userId(client);
        request(client,"PUT","/api/budgets/profile/",regularFinance(),"http://localhost:5173");
        long revision=revisions.current(id);
        var installment=loan(id,RepaymentMethod.EQUAL_PAYMENT,null);
        when(loans.activeLoans(eq(id),any())).thenReturn(List.of(installment));
        when(plans.currentPlans(eq(id),any())).thenReturn(List.of(new PlanSnapshot(101,true,installment.maturityDate(),null,0,0,0,"MATURITY",null,null,1)));
        assertThat(request(client,"POST","/api/onboarding/complete/",null,"http://localhost:5173").statusCode()).isEqualTo(400);
        var bullet=loan(id,RepaymentMethod.BULLET,PrepaymentPolicy.KEEP_PAYMENT);
        when(loans.activeLoans(eq(id),any())).thenReturn(List.of(bullet));
        for(LocalDate target:new LocalDate[]{null,bullet.maturityDate().plusDays(1)}){
            when(plans.currentPlans(eq(id),any())).thenReturn(List.of(new PlanSnapshot(101,true,target,null,0,0,0,"MATURITY",null,null,1)));
            assertThat(json(request(client,"GET","/api/onboarding/",null,null)).path("data").path("next_step").asText()).isEqualTo("REPAYMENT_GOAL");
            assertThat(request(client,"POST","/api/onboarding/complete/",null,"http://localhost:5173").statusCode()).isEqualTo(400);
        }
        assertThat(revisions.current(id)).isEqualTo(revision);
        assertThat(json(request(client,"GET","/api/users/me/",null,null)).path("data").path("onboarding_completed").booleanValue()).isFalse();
        when(plans.currentPlans(eq(id),any())).thenReturn(List.of(new PlanSnapshot(101,true,bullet.maturityDate(),null,0,0,0,"MATURITY",null,null,1)));
        assertThat(request(client,"POST","/api/onboarding/complete/",null,"http://localhost:5173").statusCode()).isEqualTo(200);
    }

    @Test void everyActiveLoanNeedsItsOwnConfiguredPlan() throws Exception {
        var client=browser();long id=userId(client);
        request(client,"PUT","/api/budgets/profile/",regularFinance(),"http://localhost:5173");
        var terms=loan(id,RepaymentMethod.EQUAL_PAYMENT,PrepaymentPolicy.KEEP_PAYMENT);
        when(loans.activeLoans(eq(id),any())).thenReturn(List.of(terms));
        for(var saved:List.of(new PlanSnapshot(999,true,terms.maturityDate(),null,0,0,0,"MATURITY",null,null,1),
                new PlanSnapshot(101,false,terms.maturityDate(),null,0,0,0,"MATURITY",null,null,1),
                new PlanSnapshot(101,true,null,null,0,0,0,"TARGET_DATE",null,null,1),
                new PlanSnapshot(101,true,null,null,0,0,0,"PREFERENCE",null,null,1))){
            when(plans.currentPlans(eq(id),any())).thenReturn(List.of(saved));
            var response=request(client,"POST","/api/onboarding/complete/",null,"http://localhost:5173");
            assertThat(response.statusCode()).isEqualTo(400);
            assertThat(json(response).path("errors").path("missing_steps").toString()).contains("REPAYMENT_GOAL");
        }
    }

    @Test
    void onboardingRequiresOwnedActiveLoansFinanceAndEverySavedGoalAndCompletesOnlyOnce() throws Exception {
        var client = browser();
        long id = userId(client);
        when(loans.activeLoans(eq(id), any())).thenReturn(List.of());
        assertThat(json(request(client, "GET", "/api/onboarding/", null, null)).path("data").path("next_step").asText()).isEqualTo("LOAN");
        var incomplete = request(client, "POST", "/api/onboarding/complete/", null, "http://localhost:5173");
        assertThat(incomplete.statusCode()).isEqualTo(400);
        assertThat(json(incomplete).path("code").asText()).isEqualTo("ONBOARDING_INCOMPLETE");
        var loan = new LoanTerms(101, id, "가상 대출", LoanType.CREDIT, 12000000, 12000000, LocalDate.of(2026,11,17),
            new BigDecimal("6.0"), RateType.FIXED, RepaymentMethod.EQUAL_PAYMENT, null, null, LocalDate.of(2026,11,25), 25,
            LocalDate.of(2027,11,25), null, null, null, PrepaymentPolicy.KEEP_PAYMENT, FeeMethod.NONE, null, null, null, 0, 0, LoanStatus.ACTIVE, null);
        when(loans.activeLoans(eq(id), any())).thenReturn(List.of(loan));
        assertThat(json(request(client, "GET", "/api/onboarding/", null, null)).path("data").path("next_step").asText()).isEqualTo("FINANCE");
        request(client, "PUT", "/api/budgets/profile/", regularFinance(), "http://localhost:5173");
        assertThat(json(request(client, "GET", "/api/onboarding/", null, null)).path("data").path("next_step").asText()).isEqualTo("REPAYMENT_GOAL");
        when(plans.currentPlans(eq(id), any())).thenReturn(List.of(new PlanSnapshot(101, true, loan.maturityDate(), loan.maturityDate(), 0, 0, 0, "MATURITY", null, LocalDate.of(2026,11,25), 1)));
        long revision = revisions.current(id);
        var completed = request(client, "POST", "/api/onboarding/complete/", null, "http://localhost:5173");
        assertThat(completed.statusCode()).isEqualTo(200);
        assertThat(json(completed).path("data").path("dashboard_ready").booleanValue()).isTrue();
        request(client, "POST", "/api/onboarding/complete/", null, "http://localhost:5173");
        assertThat(revisions.current(id)).isEqualTo(revision + 1);
        assertThat(json(request(browser(), "GET", "/api/onboarding/", null, null)).path("data").path("registered_loan_ids").isEmpty()).isTrue();
    }

}
