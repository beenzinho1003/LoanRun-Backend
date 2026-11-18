package com.loanrun;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import com.loanrun.common.contract.RevisionPort;
import java.net.*;
import java.net.http.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@Testcontainers
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"loanrun.demo-mode=true","server.servlet.session.cookie.secure=false"})
@Import(BootstrapIntegrationTest.TestConfig.class)
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class ComparisonSettingsIntegrationTest {
    @Container static final PostgreSQLContainer<?> DB=new PostgreSQLContainer<>("postgres:16.16-bookworm");
    @DynamicPropertySource static void database(DynamicPropertyRegistry r){r.add("spring.datasource.url",DB::getJdbcUrl);r.add("spring.datasource.username",DB::getUsername);r.add("spring.datasource.password",DB::getPassword);}
    @LocalServerPort int port;@Autowired ObjectMapper mapper;@Autowired RevisionPort revisions;@Autowired JdbcTemplate jdbc;
    static final String PATH="/api/reports/comparison-settings/";
    static final String CONSENT="{\"consented\":true,\"age_group\":\"AGE_20S\",\"income_decile\":3}";
    HttpClient browser(){return HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();}
    HttpResponse<String> call(HttpClient c,String method,String path,String body)throws Exception{return c.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+path))
        .header("Origin","http://localhost:5173").header("Content-Type","application/json").method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());}
    JsonNode json(HttpResponse<String> r)throws Exception{return mapper.readTree(r.body());}
    long initialize(HttpClient c,boolean pro)throws Exception{long id=json(call(c,"GET","/api/users/me/",null)).path("data").path("user_id").longValue();if(pro)plan(c,"PRO");return id;}
    void plan(HttpClient c,String type)throws Exception{assertThat(call(c,"PATCH","/api/settings/subscription/","{\"plan_type\":\""+type+"\"}").statusCode()).isEqualTo(200);}
    @Test void defaultReadDoesNotCreateSettingsAndOnlyActualPatchChangesAdvanceRevision()throws Exception{
        var c=browser();long id=initialize(c,true),revision=revisions.current(id);var response=call(c,"GET",PATH,null);assertThat(response.statusCode()).isEqualTo(200);
        var data=json(response).path("data");assertThat(data.path("consented").asBoolean()).isFalse();assertThat(data.path("age_group").isNull()).isTrue();assertThat(data.path("income_decile").isNull()).isTrue();assertThat(data.path("source_type").asText()).isEqualTo("DEMO");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM comparison_settings WHERE user_id=?",Long.class,id)).isZero();assertThat(revisions.current(id)).isEqualTo(revision);
        data=json(call(c,"PATCH",PATH,CONSENT)).path("data");assertThat(data.path("regeneration_required").asBoolean()).isTrue();assertThat(revisions.current(id)).isEqualTo(revision+1);
        assertThat(json(call(c,"PATCH",PATH,"{}")).path("data").path("regeneration_required").asBoolean()).isFalse();assertThat(revisions.current(id)).isEqualTo(revision+1);
        assertThat(json(call(c,"PATCH",PATH,CONSENT)).path("data").path("regeneration_required").asBoolean()).isFalse();
        data=json(call(c,"PATCH",PATH,"{\"income_decile\":5}")).path("data");assertThat(data.path("age_group").asText()).isEqualTo("AGE_20S");assertThat(data.path("consented").asBoolean()).isTrue();assertThat(revisions.current(id)).isEqualTo(revision+2);
    }
    @Test void withdrawalPreservesOmittedCategoriesAndExplicitNullClearsThemOnlyWithoutConsent()throws Exception{
        var c=browser();initialize(c,true);call(c,"PATCH",PATH,CONSENT);
        assertThat(call(c,"PATCH",PATH,"{\"age_group\":null}").statusCode()).isEqualTo(400);
        var data=json(call(c,"PATCH",PATH,"{\"consented\":false}")).path("data");assertThat(data.path("consented").asBoolean()).isFalse();assertThat(data.path("age_group").asText()).isEqualTo("AGE_20S");
        assertThat(call(c,"PATCH",PATH,"{\"consented\":true}").statusCode()).isEqualTo(200);
        data=json(call(c,"PATCH",PATH,"{\"consented\":false,\"age_group\":null,\"income_decile\":null}")).path("data");assertThat(data.path("age_group").isNull()).isTrue();assertThat(data.path("income_decile").isNull()).isTrue();
    }
    @Test void invalidValuesAndUnknownFieldsPreserveDataAndRevision()throws Exception{
        var c=browser();long id=initialize(c,true),revision=revisions.current(id);
        for(String body:List.of("{\"consented\":true}","{\"consented\":null}","{\"consented\":\"true\"}","{\"age_group\":\"AGE_10S\"}",
                "{\"income_decile\":0}","{\"income_decile\":6}","{\"income_decile\":1.5}","{\"income_decile\":\"3\"}","{\"user_id\":1}","{\"source_type\":\"LIVE\"}")){
            var r=call(c,"PATCH",PATH,body);assertThat(r.statusCode()).isEqualTo(400);assertThat(json(r).path("code").asText()).isEqualTo("COMPARISON_SETTINGS_INVALID");}
        assertThat(revisions.current(id)).isEqualTo(revision);assertThat(jdbc.queryForObject("SELECT count(*) FROM comparison_settings WHERE user_id=?",Long.class,id)).isZero();
    }
    @Test void sessionsAndProTransitionsKeepSettingsOwnedAndEnforceCurrentPermissions()throws Exception{
        var a=browser();initialize(a,true);call(a,"PATCH",PATH,CONSENT);var b=browser();initialize(b,false);
        assertThat(call(b,"GET",PATH,null).statusCode()).isEqualTo(403);assertThat(call(b,"PATCH",PATH,CONSENT).statusCode()).isEqualTo(403);
        plan(b,"PRO");assertThat(json(call(b,"GET",PATH,null)).path("data").path("consented").asBoolean()).isFalse();
        plan(a,"FREE");assertThat(call(a,"GET",PATH,null).statusCode()).isEqualTo(403);assertThat(call(a,"PATCH",PATH,"{\"consented\":false}").statusCode()).isEqualTo(403);
        plan(a,"PRO");assertThat(json(call(a,"GET",PATH,null)).path("data").path("consented").asBoolean()).isTrue();
    }
    @Test void concurrentOmittedFieldPatchesDoNotLoseEachOthersChanges()throws Exception{
        var c=browser();long id=initialize(c,true);call(c,"PATCH",PATH,CONSENT);long revision=revisions.current(id);
        try(var executor=Executors.newFixedThreadPool(2)){
            var start=new CountDownLatch(1);var age=executor.submit(()->{start.await();return call(c,"PATCH",PATH,"{\"age_group\":\"AGE_30S\"}");});
            var income=executor.submit(()->{start.await();return call(c,"PATCH",PATH,"{\"income_decile\":5}");});start.countDown();
            assertThat(age.get(20,TimeUnit.SECONDS).statusCode()).isEqualTo(200);assertThat(income.get(20,TimeUnit.SECONDS).statusCode()).isEqualTo(200);
        }
        var data=json(call(c,"GET",PATH,null)).path("data");assertThat(data.path("age_group").asText()).isEqualTo("AGE_30S");assertThat(data.path("income_decile").asInt()).isEqualTo(5);assertThat(revisions.current(id)).isEqualTo(revision+2);
    }
}
