package com.loanrun.loan;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import com.loanrun.common.contract.BudgetRecalculationPort;
import com.loanrun.loan.read.LoanSourceRepository;
import java.math.BigDecimal;
import java.net.*;
import java.net.http.*;
import java.time.*;
import java.util.*;
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
@Import(LoanRateHistoryIntegrationTest.Config.class)
class LoanRateHistoryIntegrationTest {
    @Container static final PostgreSQLContainer<?> DB=new PostgreSQLContainer<>("postgres:16.16-bookworm");
    @DynamicPropertySource static void db(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",DB::getJdbcUrl);r.add("spring.datasource.username",DB::getUsername);r.add("spring.datasource.password",DB::getPassword);
    }
    @TestConfiguration static class Config {
        @Bean @Primary Clock fixed(){return Clock.fixed(Instant.parse("2026-02-28T15:30:00Z"),ZoneId.of("Asia/Seoul"));}
    }
    // Registration fixtures use its actual transaction/repository and only replace the absent T2 budget hook.
    @MockitoBean BudgetRecalculationPort budget;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired LoanSourceRepository sources;
    @LocalServerPort int port;

    HttpClient browser(){return HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();}
    HttpResponse<String> get(HttpClient browser,String path)throws Exception {
        return browser.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+path)).GET().build(),HttpResponse.BodyHandlers.ofString());
    }
    JsonNode json(HttpResponse<String> response)throws Exception{return mapper.readTree(response.body());}
    long owner(HttpClient browser)throws Exception{return json(get(browser,"/api/users/me/")).path("data").path("user_id").longValue();}
    long loan(HttpClient browser)throws Exception {
        var response=browser.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/loans/"))
            .header("Origin","http://localhost:5173").header("Content-Type","application/json")
            .POST(HttpRequest.BodyPublishers.ofString(new LoanPreviewTest().body().toString())).build(),HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(201);return json(response).path("data").path("loan_id").longValue();
    }
    String path(long loan){return "/api/loans/"+loan+"/rate-history/";}
    long rate(long owner,long loan,String date,String rate){
        return jdbc.queryForObject("""
            INSERT INTO loan_rate_histories(user_id,loan_id,effective_date,annual_rate,created_at)
            VALUES (?,?,?,?,CURRENT_TIMESTAMP) RETURNING id
            """,Long.class,owner,loan,LocalDate.parse(date),new BigDecimal(rate));
    }
    void missing(HttpClient browser,long id)throws Exception {
        var response=get(browser,path(id));assertThat(response.statusCode()).isEqualTo(404);
        assertThat(json(response)).isEqualTo(mapper.readTree("""
            {"success":false,"code":"LOAN_NOT_FOUND","message":"대출을 찾을 수 없습니다.","errors":{}}
            """));
    }

    @Test void registrationRateUsesExactSchemaKstDateAndUnknownPreviousRate()throws Exception {
        var browser=browser();owner(browser);long id=loan(browser);
        long rateId=jdbc.queryForObject("SELECT id FROM loan_rate_histories WHERE loan_id=?",Long.class,id);
        var response=get(browser,path(id));assertThat(response.statusCode()).isEqualTo(200);
        var expected=mapper.readTree("""
            {"success":true,"code":"LOAN_RATE_HISTORY_SUCCESS","message":"대출 금리 변경 이력 정보를 조회했습니다.",
             "data":{"loan_id":%d,"rates":[{"rate_id":%d,"loan_id":%d,"effective_date":"2026-03-01",
             "previous_rate":null,"new_rate":6,"source":"MANUAL"}]}}
            """.formatted(id,rateId,id));
        assertThat(json(response)).isEqualTo(expected);
    }
    @Test void ordersByEffectiveDateThenIdAndDerivesPreviousFromOriginalHistoryIncludingZero()throws Exception {
        var browser=browser();long owner=owner(browser),id=loan(browser);
        // Insert out of date order; each same-date row is retained, even when the rate repeats.
        long last=rate(owner,id,"2026-03-10","8.25");
        long first=rate(owner,id,"2026-03-02","0");
        long second=rate(owner,id,"2026-03-02","7.125");
        long third=rate(owner,id,"2026-03-02","7.125");
        jdbc.update("UPDATE loans SET annual_rate=19 WHERE user_id=? AND id=?",owner,id);
        var response=get(browser,path(id));assertThat(response.statusCode()).isEqualTo(200);
        var rates=json(response).path("data").path("rates");
        var ids=new ArrayList<Long>();rates.forEach(r->ids.add(r.path("rate_id").longValue()));
        long initial=jdbc.queryForObject("SELECT min(id) FROM loan_rate_histories WHERE loan_id=?",Long.class,id);
        assertThat(ids).containsExactly(initial,first,second,third,last);
        assertThat(rates.get(1).path("previous_rate").decimalValue()).isEqualByComparingTo("6");
        assertThat(rates.get(1).path("new_rate").isNumber()).isTrue();
        assertThat(rates.get(1).path("new_rate").decimalValue()).isZero();
        assertThat(rates.get(2).path("previous_rate").decimalValue()).isZero();
        assertThat(rates.get(3).path("previous_rate").decimalValue()).isEqualByComparingTo("7.125");
        assertThat(rates.get(4).path("previous_rate").decimalValue()).isEqualByComparingTo("7.125");
        assertThat(rates.get(4).path("new_rate").decimalValue()).isEqualByComparingTo("8.25");
    }
    @Test void foreignMissingInvalidAndDeletedIdsHaveSame404WhilePaidHistoryIsVisible()throws Exception {
        var a=browser();var b=browser();long ownerA=owner(a);owner(b);long id=loan(a),ownB=loan(b);
        missing(b,id);missing(a,ownB);missing(a,Long.MAX_VALUE);missing(a,0);missing(a,-1);
        jdbc.update("UPDATE loans SET status='PAID',remaining_principal=0 WHERE user_id=? AND id=?",ownerA,id);
        assertThat(get(a,path(id)).statusCode()).isEqualTo(200);
        jdbc.update("UPDATE loans SET status='DELETED' WHERE user_id=? AND id=?",ownerA,id);
        missing(a,id);missing(b,id);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM loan_rate_histories WHERE loan_id=?",Long.class,id)).isEqualTo(1);
    }
    @Test void emptyStoredHistoryReturnsEmptyArrayWithoutInventingLatestRate()throws Exception {
        var browser=browser();long owner=owner(browser),id=loan(browser);
        jdbc.update("DELETE FROM loan_rate_histories WHERE user_id=? AND loan_id=?",owner,id);
        var response=get(browser,path(id));assertThat(response.statusCode()).isEqualTo(200);
        assertThat(json(response).path("data").path("rates").isArray()).isTrue();
        assertThat(json(response).path("data").path("rates").size()).isZero();
    }
    @Test void historyDoesNotDependOnUnrelatedPlanParsingOrOtherLoans()throws Exception {
        var browser=browser();long owner=owner(browser),id=loan(browser),other=loan(browser);
        rate(owner,other,"2026-03-02","11");
        jdbc.update("UPDATE loans SET initial_plan_snapshot='{}'::jsonb WHERE user_id=?",owner);
        var response=get(browser,path(id));assertThat(response.statusCode()).isEqualTo(200);
        assertThat(json(response).path("data").path("rates").size()).isEqualTo(1);
        assertThat(json(response).path("data").path("rates").get(0).path("loan_id").longValue()).isEqualTo(id);
    }
    @Test void invalidPathUsesCommonEnvelopeAndTrailingSlashIsRequired()throws Exception {
        var browser=browser();owner(browser);long id=loan(browser);
        for(String value:List.of("abc","9223372036854775808")) {
            var response=get(browser,"/api/loans/"+value+"/rate-history/");
            assertThat(response.statusCode()).isEqualTo(400);assertThat(json(response).path("code").asText()).isEqualTo("VALIDATION_ERROR");
            assertThat(json(response).has("errors")).isTrue();assertThat(json(response).has("data")).isFalse();
        }
        assertThat(get(browser,path(id).substring(0,path(id).length()-1)).statusCode()).isEqualTo(404);
    }
    @Test void repeatedReadsPreserveAllFinancialSourcesRevisionAndUserTimestamp()throws Exception {
        var browser=browser();long owner=owner(browser),id=loan(browser);
        var userBefore=jdbc.queryForMap("SELECT revision,updated_at FROM demo_users WHERE id=?",owner);
        var before=sources.snapshot(owner,LocalDate.of(2026,3,31));
        var first=get(browser,path(id));var second=get(browser,path(id));
        assertThat(first.statusCode()).isEqualTo(200);assertThat(json(second)).isEqualTo(json(first));
        assertThat(sources.snapshot(owner,LocalDate.of(2026,3,31))).isEqualTo(before);
        assertThat(jdbc.queryForMap("SELECT revision,updated_at FROM demo_users WHERE id=?",owner)).isEqualTo(userBefore);
        assertThatThrownBy(()->sources.rateHistory(owner,id).clear()).isInstanceOf(UnsupportedOperationException.class);
    }
}
