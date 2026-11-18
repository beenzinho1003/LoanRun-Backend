package com.loanrun;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.loanrun.common.contract.*;
import com.loanrun.common.contract.FinanceModels.*;
import java.net.*;
import java.net.http.*;
import java.time.*;
import java.util.*;
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
class MonthlyBudgetWriteIntegrationTest {
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
    HttpResponse<String> call(HttpClient c,String method,String path,String body)throws Exception{
        return c.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+path)).header("Origin","http://localhost:5173").header("Content-Type","application/json")
            .method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }
    JsonNode json(HttpResponse<String> r)throws Exception{return mapper.readTree(r.body());}
    ObjectNode expense(){return mapper.createObjectNode().put("fixed_essential_expenses",800000).put("variable_essential_expenses",200000).put("discretionary_expenses",0).put("savings",200000);}
    ObjectNode entry(long amount){return mapper.createObjectNode().put("entry_type","EXTRA").put("received_date","2026-11-15").put("amount",amount);}
    long initialize(HttpClient c)throws Exception{
        long id=json(call(c,"GET","/api/users/me/",null)).path("data").path("user_id").longValue();
        assertThat(call(c,"PUT","/api/budgets/profile/","{\"monthly_income\":3000000,\"income_type\":\"REGULAR\",\"payday\":1,\"fixed_essential_expenses\":800000,\"variable_essential_expenses\":200000,\"discretionary_expenses\":500000,\"savings\":200000}").statusCode()).isEqualTo(200);return id;
    }
    HttpResponse<String> save(HttpClient c,ObjectNode body)throws Exception{return call(c,"PUT","/api/budgets/2026-11/",body.toString());}
    @Test void replacesCorrectsPreservesAndDeletesIncomeWithoutChangingMonthStartLimit()throws Exception{
        var c=browser();long id=initialize(c);long revision=revisions.current(id);
        var body=expense();body.putArray("income_entries").add(entry(200000));
        var response=save(c,body);assertThat(response.statusCode()).isEqualTo(200);
        assertThat(json(response).path("code").asText()).isEqualTo("MONTHLY_BUDGET_SAVE_SUCCESS");
        var data=json(response).path("data");long entryId=data.path("income_entries").get(0).path("income_entry_id").longValue();
        assertThat(data.path("actual_income").longValue()).isEqualTo(3200000);assertThat(data.path("actual_discretionary_expenses").longValue()).isZero();
        assertThat(data.path("discretionary_limit_amount").longValue()).isEqualTo(500000);assertThat(data.path("source").asText()).isEqualTo("INPUT");
        assertThat(revisions.current(id)).isEqualTo(revision+1);
        assertThat(json(save(c,expense())).path("data").path("income_entries").get(0).path("income_entry_id").longValue()).isEqualTo(entryId);
        body=expense();body.putArray("income_entries").add(entry(100000).put("income_entry_id",entryId));
        data=json(save(c,body)).path("data");assertThat(data.path("monthly_income").longValue()).isEqualTo(3100000);
        assertThat(data.path("income_entries").get(0).path("income_entry_id").longValue()).isEqualTo(entryId);
        body=expense();body.putArray("income_entries");data=json(save(c,body)).path("data");
        assertThat(data.path("income_entries").isEmpty()).isTrue();assertThat(data.path("actual_income").longValue()).isEqualTo(3000000);
    }
    @Test void invalidFieldsDatesTypesDuplicatesAndOtherMonthsLeaveAllInputsUnchanged()throws Exception{
        var c=browser();long id=initialize(c);long revision=revisions.current(id);
        List<ObjectNode> invalid=new ArrayList<>();
        invalid.add(expense().putNull("income_entries"));invalid.add(expense().put("savings",-1));invalid.add(expense().put("savings",0.5));
        invalid.add(expense().put("user_id",id));invalid.add(expense().put("savings",Long.MAX_VALUE));
        var missing=expense();missing.remove("savings");invalid.add(missing);
        for(var e:List.of(entry(0),entry(1).put("entry_type","IRREGULAR"),entry(1).put("received_date","2026-11-18"),entry(1).put("received_date","2026-10-15"),entry(1).put("received_date","2026-11-31"))){
            var b=expense();b.putArray("income_entries").add(e);invalid.add(b);
        }
        for(var b:invalid){var r=save(c,b);assertThat(r.statusCode()).as(r.body()).isEqualTo(400);assertThat(json(r).path("code").asText()).isEqualTo("BUDGET_VALIDATION_ERROR");}
        for(String month:List.of("2026-10","2026-12"))assertThat(call(c,"PUT","/api/budgets/"+month+"/",expense().toString()).statusCode()).isEqualTo(400);
        assertThat(call(c,"PUT","/api/budgets/2026-1/",expense().toString()).statusCode()).isEqualTo(400);
        assertThat(revisions.current(id)).isEqualTo(revision);
        assertThat(jdbc.queryForObject("SELECT discretionary_expenses FROM monthly_budgets WHERE user_id=?",Long.class,id)).isEqualTo(500000);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM income_entries WHERE user_id=?",Long.class,id)).isZero();
    }
    @Test void foreignOrDuplicateIncomeIdsCannotChangeEitherSession()throws Exception{
        var a=browser();long aid=initialize(a);var initial=expense();initial.putArray("income_entries").add(entry(200000));
        long entryId=json(save(a,initial)).path("data").path("income_entries").get(0).path("income_entry_id").longValue();
        var b=browser();long bid=initialize(b);long revision=revisions.current(bid);var body=expense();body.putArray("income_entries").add(entry(1).put("income_entry_id",entryId));
        assertThat(save(b,body).statusCode()).isEqualTo(404);assertThat(revisions.current(bid)).isEqualTo(revision);
        assertThat(jdbc.queryForObject("SELECT amount FROM income_entries WHERE id=? AND user_id=?",Long.class,entryId,aid)).isEqualTo(200000);
        body=expense();body.putArray("income_entries").add(entry(1).put("income_entry_id",entryId)).add(entry(2).put("income_entry_id",entryId));
        assertThat(save(a,body).statusCode()).isEqualTo(400);
        assertThat(jdbc.queryForObject("SELECT amount FROM income_entries WHERE id=?",Long.class,entryId)).isEqualTo(200000);
    }
    @Test void projectionFailureRollsBackExpensesIncomeAndRevisionTogether()throws Exception{
        var c=browser();long id=initialize(c);long revision=revisions.current(id);var body=expense();body.putArray("income_entries").add(entry(200000));
        when(plans.plannedAllocations(id,MONTH)).thenThrow(com.loanrun.common.error.ApiException.unavailable());
        assertThat(save(c,body).statusCode()).isEqualTo(503);
        assertThat(revisions.current(id)).isEqualTo(revision);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM income_entries WHERE user_id=?",Long.class,id)).isZero();
        assertThat(jdbc.queryForObject("SELECT source FROM monthly_budgets WHERE user_id=?",String.class,id)).isEqualTo("DEFAULT");
        assertThat(jdbc.queryForObject("SELECT discretionary_expenses FROM monthly_budgets WHERE user_id=?",Long.class,id)).isEqualTo(500000);
    }
    @Test void explicitEmptyIrregularIncomeConfirmsZeroWhileOmissionKeepsUnknown()throws Exception{
        var c=browser();initialize(c);
        assertThat(call(c,"PUT","/api/budgets/profile/","{\"monthly_income\":3000000,\"income_type\":\"IRREGULAR\",\"payday\":null,\"fixed_essential_expenses\":800000,\"variable_essential_expenses\":200000,\"discretionary_expenses\":500000,\"savings\":200000}").statusCode()).isEqualTo(200);
        assertThat(json(save(c,expense())).path("data").path("actual_income").isNull()).isTrue();
        var body=expense();body.putArray("income_entries");var data=json(save(c,body)).path("data");
        assertThat(data.path("actual_income").isNumber()).isTrue();assertThat(data.path("actual_income").longValue()).isZero();
        assertThat(data.path("income_status").asText()).isEqualTo("INPUT");
        assertThat(data.path("planning_monthly_income").longValue()).isEqualTo(3000000);
        body=expense();body.putArray("income_entries").add(entry(200000).put("entry_type","IRREGULAR"));
        data=json(save(c,body)).path("data");assertThat(data.path("actual_income").longValue()).isEqualTo(200000);
        assertThat(data.path("planning_monthly_income").longValue()).isEqualTo(3000000);
    }
}
