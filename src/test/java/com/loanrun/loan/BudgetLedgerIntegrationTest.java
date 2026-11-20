package com.loanrun.loan;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loanrun.common.contract.BudgetQueryPort;
import com.loanrun.common.contract.BudgetRecalculationPort;
import com.loanrun.common.contract.LoanQueryPort;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.YearMonth;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Real ledger and budget providers; no financial port is replaced with a mock. */
@Testcontainers
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties="server.servlet.session.cookie.secure=false")
@Import(LoanRegistrationIntegrationTest.Config.class)
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class BudgetLedgerIntegrationTest {
    @Container static final PostgreSQLContainer<?> DB=new PostgreSQLContainer<>("postgres:16.16-bookworm");
    @DynamicPropertySource static void db(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",DB::getJdbcUrl);
        r.add("spring.datasource.username",DB::getUsername);
        r.add("spring.datasource.password",DB::getPassword);
    }
    @LocalServerPort int port;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired BudgetQueryPort budgets;
    @Autowired BudgetRecalculationPort recalculation;
    @Autowired LoanQueryPort loans;

    HttpClient browser(){return HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();}
    HttpResponse<String> call(HttpClient c,String method,String path,String body)throws Exception {
        return c.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+path))
            .header("Origin","http://localhost:5173").header("Content-Type","application/json")
            .method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body))
            .build(),HttpResponse.BodyHandlers.ofString());
    }
    @Test void realRegistrationRecalculatesBudgetAndSeparatesOwners()throws Exception {
        var browser=browser();
        long owner=mapper.readTree(call(browser,"GET","/api/users/me/",null).body()).path("data").path("user_id").longValue();
        var profile="""
            {"monthly_income":3000000,"income_type":"REGULAR","payday":25,
             "fixed_essential_expenses":800000,"variable_essential_expenses":200000,
             "discretionary_expenses":500000,"savings":200000}
            """;
        assertThat(call(browser,"PUT","/api/budgets/profile/",profile).statusCode()).isEqualTo(200);
        var month=YearMonth.of(2026,1);
        assertThat(budgets.capacity(owner,month).availableCash()).isEqualTo(1300000);
        var input=new LoanPreviewTest().body();
        var response=call(browser,"POST","/api/loans/",input.toString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        long due=loans.dueSummary(owner,month).scheduledCash();
        assertThat(due).isPositive();
        assertThat(budgets.capacity(owner,month).availableCash()).isEqualTo(1300000-due);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM loans WHERE user_id=?",Long.class,owner)).isEqualTo(1);
        var other=browser();
        long otherOwner=mapper.readTree(call(other,"GET","/api/users/me/",null).body()).path("data").path("user_id").longValue();
        assertThat(budgets.capacity(otherOwner,month).availableCash()).isNull();
        assertThat(loans.activeLoans(otherOwner,month.atDay(1))).isEmpty();
    }
}
