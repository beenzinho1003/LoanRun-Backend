package com.loanrun;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.loanrun.common.contract.RevisionPort;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"loanrun.demo-mode=true", "server.servlet.session.cookie.secure=false"})
@Import(BootstrapIntegrationTest.TestConfig.class)
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class FinanceProfileIntegrationTest {
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
    @Autowired RevisionPort revisions;

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

    @Test
    void financeProfilePersistsZeroAndNullAndKeepsEachUsersBudgetSeparate() throws Exception {
        var client = browser();
        long id = userId(client);
        assertThat(request(client, "GET", "/api/budgets/profile/", null, null).statusCode()).isEqualTo(404);
        var saved = request(client, "PUT", "/api/budgets/profile/", regularFinance(), "http://localhost:5173");
        assertThat(saved.statusCode()).isEqualTo(200);
        var profile = json(saved).path("data");
        assertThat(profile.path("essential_expenses").longValue()).isEqualTo(1000000);
        assertThat(profile.path("emergency_fund").isNull()).isTrue();
        assertThat(json(request(client, "GET", "/api/users/me/", null, null)).path("data").path("income_type").asText()).isEqualTo("REGULAR");
        assertThat(request(browser(), "GET", "/api/budgets/profile/", null, null).statusCode()).isEqualTo(404);
        var changed = regularFinance().replace("500000", "700000").replace("\"emergency_fund\":null", "\"emergency_fund\":0");
        assertThat(request(client, "PUT", "/api/budgets/profile/", changed, "http://localhost:5173").statusCode()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT discretionary_limit FROM monthly_budgets WHERE user_id=?", Long.class, id)).isEqualTo(500000);
        assertThat(jdbc.queryForObject("SELECT discretionary_expenses FROM monthly_budgets WHERE user_id=?", Long.class, id)).isEqualTo(700000);
        assertThat(json(request(client, "GET", "/api/budgets/profile/", null, null)).path("data").path("emergency_fund").longValue()).isZero();
        assertThat(request(client, "PATCH", "/api/users/me/", "{\"income_type\":\"IRREGULAR\"}", "http://localhost:5173").statusCode()).isEqualTo(400);
    }

    @Test
    void financeValidationRejectsTypeCoercionNegativeAmountsAndInvalidPaydaysAtomically() throws Exception {
        var client = browser();
        for (String body : new String[]{regularFinance().replace("3000000", "\"3000000\""), regularFinance().replace("3000000", "3.1"),
                regularFinance().replace("3000000", "-1"), regularFinance().replace("\"payday\":25", "\"payday\":null"),
                regularFinance().replace("\"payday\":25", "\"payday\":32"), regularFinance().replace("REGULAR", "IRREGULAR"),
                regularFinance().replace("3000000", "9223372036854775808"), regularFinance().replace("800000", "9223372036854775807"),
                regularFinance().replace("\"savings\":200000", "\"savings\":200000,\"user_id\":1")}) {
            var response = request(client, "PUT", "/api/budgets/profile/", body, "http://localhost:5173");
            assertThat(response.statusCode()).isEqualTo(400);
            assertThat(json(response).path("code").asText()).isEqualTo("BUDGET_VALIDATION_ERROR");
        }
        assertThat(request(client, "GET", "/api/budgets/profile/", null, null).statusCode()).isEqualTo(404);
        String irregular = regularFinance().replace("REGULAR", "IRREGULAR").replace("\"payday\":25", "\"payday\":null").replace("3000000", "0");
        assertThat(request(client, "PUT", "/api/budgets/profile/", irregular, "http://localhost:5173").statusCode()).isEqualTo(200);
        assertThat(json(request(client, "GET", "/api/budgets/profile/", null, null)).path("data").path("payday").isNull()).isTrue();
    }

    @Test
    void profileChangesPreservePastConfirmedAndInputMonthsAndEveryOriginalDiscretionaryLimit() throws Exception {
        var client = browser();
        long id = userId(client);
        request(client, "PUT", "/api/budgets/profile/", regularFinance(), "http://localhost:5173");
        for (String month : new String[]{"2026-10-01", "2026-12-01", "2027-01-01", "2027-02-01", "2027-03-01"}) {
            jdbc.update("""
                INSERT INTO monthly_budgets(user_id,year_month,income_type,expected_income,payday,fixed_essential_expenses,
                    variable_essential_expenses,discretionary_expenses,discretionary_limit,savings,created_at,updated_at)
                SELECT user_id,CAST(? AS date),income_type,expected_income,payday,fixed_essential_expenses,
                    variable_essential_expenses,discretionary_expenses,discretionary_limit,savings,created_at,updated_at
                FROM monthly_budgets WHERE user_id=? AND year_month='2026-11-01'
                """, month, id);
        }
        jdbc.update("UPDATE monthly_budgets SET source='INPUT' WHERE user_id=? AND year_month='2027-01-01'", id);
        jdbc.update("UPDATE monthly_budgets SET expenses_confirmed=TRUE WHERE user_id=? AND year_month='2027-02-01'", id);
        jdbc.update("UPDATE monthly_budgets SET income_confirmed=TRUE WHERE user_id=? AND year_month='2027-03-01'", id);
        long before = revisions.current(id);
        var changed = (ObjectNode) mapper.readTree(regularFinance());
        changed.put("monthly_income", 3500000).put("discretionary_expenses", 700000);
        assertThat(request(client, "PUT", "/api/budgets/profile/", changed.toString(), "http://localhost:5173").statusCode()).isEqualTo(200);
        assertThat(revisions.current(id)).isEqualTo(before + 1);
        for (String month : new String[]{"2026-11-01", "2026-12-01"}) {
            assertThat(jdbc.queryForObject("SELECT expected_income FROM monthly_budgets WHERE user_id=? AND year_month=CAST(? AS date)", Long.class, id, month)).isEqualTo(3500000);
        }
        for (String month : new String[]{"2026-10-01", "2027-01-01", "2027-02-01", "2027-03-01"}) {
            assertThat(jdbc.queryForObject("SELECT expected_income FROM monthly_budgets WHERE user_id=? AND year_month=CAST(? AS date)", Long.class, id, month)).isEqualTo(3000000);
        }
        assertThat(jdbc.queryForList("SELECT discretionary_limit FROM monthly_budgets WHERE user_id=?", Long.class, id))
            .hasSize(6).containsOnly(500000L);
        request(client, "GET", "/api/budgets/profile/", null, null);
        assertThat(revisions.current(id)).isEqualTo(before + 1);
    }

    @Test
    void invalidReplacementDoesNotChangeExistingProfileIncomeTypeRevisionOrMonthlySnapshot() throws Exception {
        var client = browser();
        long id = userId(client);
        request(client, "PUT", "/api/budgets/profile/", regularFinance(), "http://localhost:5173");
        long revision = revisions.current(id);
        var invalid = regularFinance().replace("REGULAR", "IRREGULAR").replace("\"payday\":25", "\"payday\":null")
            .replace("800000", "9223372036854775807");
        assertThat(request(client, "PUT", "/api/budgets/profile/", invalid, "http://localhost:5173").statusCode()).isEqualTo(400);
        assertThat(revisions.current(id)).isEqualTo(revision);
        assertThat(json(request(client, "GET", "/api/budgets/profile/", null, null)).path("data").path("income_type").asText()).isEqualTo("REGULAR");
        assertThat(json(request(client, "GET", "/api/users/me/", null, null)).path("data").path("income_type").asText()).isEqualTo("REGULAR");
        assertThat(jdbc.queryForObject("SELECT expected_income FROM monthly_budgets WHERE user_id=?", Long.class, id)).isEqualTo(3000000);
    }

}
