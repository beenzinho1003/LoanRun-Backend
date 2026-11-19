package com.loanrun;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.loanrun.common.contract.*;
import com.loanrun.common.contract.FinanceModels.*;
import com.loanrun.common.error.ApiException;
import java.net.*;
import java.net.http.*;
import java.time.*;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@Testcontainers
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"loanrun.demo-mode=true","server.servlet.session.cookie.secure=false"})
@Import(BootstrapIntegrationTest.TestConfig.class)
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class IncomeEntryIntegrationTest {
    @Container static final PostgreSQLContainer<?> DB=new PostgreSQLContainer<>("postgres:16.16-bookworm");
    @DynamicPropertySource static void database(DynamicPropertyRegistry r){r.add("spring.datasource.url",DB::getJdbcUrl);r.add("spring.datasource.username",DB::getUsername);r.add("spring.datasource.password",DB::getPassword);}
    @LocalServerPort int port;
    @Autowired ObjectMapper mapper; @Autowired JdbcTemplate jdbc; @Autowired RevisionPort revisions;
    @MockitoBean LoanQueryPort loans; @MockitoBean PlanQueryPort plans;
    static final YearMonth MONTH=YearMonth.of(2026,11);
    @BeforeEach void facts(){
        when(loans.dueSummary(anyLong(),any())).thenAnswer(i->new DueSummary(i.getArgument(1),0,0,0,0,0,null,0,0,0,0,0));
        when(loans.paymentSummary(anyLong(),any())).thenAnswer(i->new PaymentSummary(i.getArgument(1),0,0,0,0,0,0,0,true,0,0,Map.of()));
        when(loans.savingSummary(anyLong(),any())).thenAnswer(i->new SavingSummary(i.getArgument(1),0,0,0,0,true,0,Map.of()));
        when(plans.plannedAllocations(anyLong(),any())).thenReturn(List.of());
    }
    HttpClient browser(){return HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();}
    HttpResponse<String> call(HttpClient c,String method,String path,String key,String body)throws Exception{
        var request=HttpRequest.newBuilder(URI.create("http://localhost:"+port+path)).header("Origin","http://localhost:5173").header("Content-Type","application/json");
        if(key!=null)request.header("Idempotency-Key",key);
        return c.send(request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }
    JsonNode json(HttpResponse<String> r)throws Exception{return mapper.readTree(r.body());}
    ObjectNode entry(long amount){return mapper.createObjectNode().put("entry_type","EXTRA").put("received_date","2026-11-15").put("amount",amount);}
    long initialize(HttpClient c,String type)throws Exception{
        long id=json(call(c,"GET","/api/users/me/",null,null)).path("data").path("user_id").longValue();
        var profile=mapper.createObjectNode().put("monthly_income",3000000).put("income_type",type).put("fixed_essential_expenses",800000)
            .put("variable_essential_expenses",200000).put("discretionary_expenses",500000).put("savings",200000);
        if(type.equals("REGULAR"))profile.put("payday",25);else profile.putNull("payday");
        assertThat(call(c,"PUT","/api/budgets/profile/",null,profile.toString()).statusCode()).isEqualTo(200);return id;
    }
    HttpResponse<String> create(HttpClient c,String key,ObjectNode body)throws Exception{return call(c,"POST","/api/income-entries/",key,body.toString());}
    long count(long userId){return jdbc.queryForObject("SELECT count(*) FROM income_entries WHERE user_id=?",Long.class,userId);}
    @Test void creates201AndReplaysExactResponseWithoutDoubleCountingOrRevision()throws Exception{
        var c=browser();long id=initialize(c,"REGULAR"),revision=revisions.current(id);var body=entry(200000);
        var first=create(c,"same",body);assertThat(first.statusCode()).isEqualTo(201);var data=json(first).path("data");
        assertThat(json(first).path("code").asText()).isEqualTo("INCOME_ENTRY_CREATE_SUCCESS");
        assertThat(data.path("monthly_income").longValue()).isEqualTo(3200000);assertThat(data.path("actual_income").longValue()).isEqualTo(200000);
        assertThat(data.path("actual_available_cash").isNull()).isTrue();assertThat(data.has("metrics")).isFalse();
        assertThat(create(c,"same",body).body()).isEqualTo(first.body());assertThat(count(id)).isEqualTo(1);assertThat(revisions.current(id)).isEqualTo(revision+1);
        var conflict=create(c,"same",entry(1));assertThat(conflict.statusCode()).isEqualTo(409);
        assertThat(json(conflict).path("code").asText()).isEqualTo("INCOME_ENTRY_CONFLICT");
        assertThat(json(conflict).path("message").asText()).isEqualTo("이미 반영된 소득 기록입니다.");assertThat(count(id)).isEqualTo(1);
    }
    @Test void concurrentRetryCommitsExactlyOneIncomeAndOneRevision()throws Exception{
        var c=browser();long id=initialize(c,"REGULAR"),revision=revisions.current(id);
        try(var executor=Executors.newFixedThreadPool(2)){
            var start=new CountDownLatch(1);Callable<HttpResponse<String>> operation=()->{start.await();return create(c,"parallel",entry(100000));};
            var a=executor.submit(operation);var b=executor.submit(operation);start.countDown();
            var ar=a.get(20,TimeUnit.SECONDS);var br=b.get(20,TimeUnit.SECONDS);
            assertThat(ar.statusCode()).isEqualTo(201);assertThat(br.statusCode()).isEqualTo(201);assertThat(ar.body()).isEqualTo(br.body());
        }
        assertThat(count(id)).isEqualTo(1);assertThat(revisions.current(id)).isEqualTo(revision+1);
    }
    @Test void keyScopeAndIncomeRemainIndependentBetweenSessions()throws Exception{
        var a=browser();long aid=initialize(a,"REGULAR");var b=browser();long bid=initialize(b,"IRREGULAR");
        var ar=create(a,"shared",entry(100000));var br=create(b,"shared",entry(200000).put("entry_type","IRREGULAR"));
        assertThat(ar.statusCode()).isEqualTo(201);assertThat(br.statusCode()).isEqualTo(201);
        assertThat(json(ar).path("data").path("entry").path("income_entry_id").longValue()).isNotEqualTo(json(br).path("data").path("entry").path("income_entry_id").longValue());
        assertThat(json(br).path("data").path("actual_income").longValue()).isEqualTo(200000);
        assertThat(json(br).path("data").path("planning_monthly_income").longValue()).isEqualTo(3000000);
        assertThat(count(aid)).isEqualTo(1);assertThat(count(bid)).isEqualTo(1);
    }
    @Test void missingProfileAndInvalidInputsNeverCreateIncomeOrConsumeKey()throws Exception{
        var c=browser();assertThat(create(c,"missing",entry(1)).statusCode()).isEqualTo(404);
        long id=initialize(c,"REGULAR"),revision=revisions.current(id);
        for(String key:Arrays.asList(null,"","x".repeat(129)))assertThat(create(c,key,entry(1)).statusCode()).isEqualTo(400);
        for(var body:List.of(entry(0),entry(-1),entry(1).put("amount",0.5),entry(1).put("entry_type","IRREGULAR"),
                entry(1).put("received_date","2026-11-18"),entry(1).put("received_date","2026-10-15"),entry(1).put("income_entry_id",1),entry(Long.MAX_VALUE))){
            var r=create(c,"invalid",body);assertThat(r.statusCode()).as(r.body()).isEqualTo(400);assertThat(json(r).path("code").asText()).isEqualTo("BUDGET_VALIDATION_ERROR");
        }
        assertThat(count(id)).isZero();assertThat(revisions.current(id)).isEqualTo(revision);
        assertThat(create(c,"invalid",entry(1)).statusCode()).isEqualTo(201);
    }
    @Test void dependentFailureRollsBackIncomeConfirmationRevisionAndIdempotencyForRetry()throws Exception{
        var c=browser();long id=initialize(c,"REGULAR"),revision=revisions.current(id);
        when(plans.plannedAllocations(id,MONTH)).thenThrow(ApiException.unavailable());
        assertThat(create(c,"retry",entry(100000)).statusCode()).isEqualTo(503);
        assertThat(count(id)).isZero();assertThat(revisions.current(id)).isEqualTo(revision);
        assertThat(jdbc.queryForObject("SELECT income_confirmed FROM monthly_budgets WHERE user_id=?",Boolean.class,id)).isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM idempotency_records WHERE user_id=?",Long.class,id)).isZero();
        doReturn(List.of()).when(plans).plannedAllocations(id,MONTH);assertThat(create(c,"retry",entry(100000)).statusCode()).isEqualTo(201);
        assertThat(count(id)).isEqualTo(1);assertThat(revisions.current(id)).isEqualTo(revision+1);
    }
}
