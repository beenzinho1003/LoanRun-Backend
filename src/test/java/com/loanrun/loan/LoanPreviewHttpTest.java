package com.loanrun.loan;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import java.net.*;
import java.net.http.*;
import java.time.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties="server.servlet.session.cookie.secure=false")
@Import({LoanPreviewHttpTest.Config.class, WithoutBudgetModule.class})
class LoanPreviewHttpTest {
    @Container static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("postgres:16.16-bookworm");
    @DynamicPropertySource static void db(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",DB::getJdbcUrl); r.add("spring.datasource.username",DB::getUsername); r.add("spring.datasource.password",DB::getPassword);
    }
    @TestConfiguration static class Config {
        @Bean @Primary Clock fixed() { return Clock.fixed(Instant.parse("2025-12-31T15:00:00Z"), ZoneId.of("Asia/Seoul")); }
    }
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    HttpResponse<String> request(HttpClient client,String method,String path,String body,String origin) throws Exception {
        var b=HttpRequest.newBuilder(URI.create("http://localhost:"+port+path)).header("Content-Type","application/json");
        if(origin!=null)b.header("Origin",origin);
        return client.send(b.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }
    @Test void anonymousFreePreviewIsReadOnlyAndUsesExactProductionRoutesAndOriginGuard() throws Exception {
        var browser=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();
        var profile=request(browser,"GET","/api/users/me/",null,null);
        long id=mapper.readTree(profile.body()).path("data").path("user_id").longValue();
        long revision=jdbc.queryForObject("select revision from demo_users where id=?",Long.class,id);
        var body=new LoanPreviewTest().body().toString();
        for(int i=0;i<2;i++) {
            var response=request(browser,"POST","/api/loans/preview/",body,"http://localhost:5173");
            assertThat(response.statusCode()).isEqualTo(200);
            var json=mapper.readTree(response.body());assertThat(json.path("code").asText()).isEqualTo("LOAN_PREVIEW_SUCCESS");
            assertThat(json.path("data").path("estimated_next_interest").longValue()).isEqualTo(60000);
            assertThat(json.has("errors")).isFalse();
        }
        assertThat(request(browser,"POST","/api/loans/preview",body,"http://localhost:5173").statusCode()).isEqualTo(404);
        assertThat(request(browser,"POST","/api/loans/preview/",body,null).statusCode()).isEqualTo(403);
        assertThat(request(browser,"GET","/api/loans/preview/",null,null).statusCode()).isEqualTo(405);
        var invalid=request(browser,"POST","/api/loans/preview/","{\"user_id\":99}","http://localhost:5173");
        assertThat(invalid.statusCode()).isEqualTo(400);assertThat(mapper.readTree(invalid.body()).path("code").asText()).isEqualTo("VALIDATION_ERROR");
        assertThat(jdbc.queryForObject("select revision from demo_users where id=?",Long.class,id)).isEqualTo(revision);
        assertThat(jdbc.queryForObject("select count(*) from idempotency_records",Long.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from demo_users where id=?",Long.class,id)).isEqualTo(1);
    }
    @Test void registrationWithoutRequiredBudgetProviderFailsClosed() throws Exception {
        var browser=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();
        var profile=request(browser,"GET","/api/users/me/",null,null);
        long id=mapper.readTree(profile.body()).path("data").path("user_id").longValue();
        var result=request(browser,"POST","/api/loans/",new LoanPreviewTest().body().toString(),"http://localhost:5173");
        assertThat(result.statusCode()).isEqualTo(503);
        assertThat(mapper.readTree(result.body()).path("code").asText()).isEqualTo("SERVICE_UNAVAILABLE");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM loans WHERE user_id=?",Long.class,id)).isZero();
        assertThat(jdbc.queryForObject("SELECT revision FROM demo_users WHERE id=?",Long.class,id)).isZero();
    }

}
