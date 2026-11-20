package com.loanrun.loan;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.*;
import com.loanrun.common.contract.*;
import com.loanrun.common.contract.FinanceModels.*;
import com.loanrun.loan.read.LoanSourceRepository;
import com.loanrun.loan.service.LoanRegistrationService;
import com.loanrun.repaymentplan.service.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@Testcontainers
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties="server.servlet.session.cookie.secure=false")
@Import(PaymentScheduleIntegrationTest.Config.class)
class PaymentScheduleIntegrationTest {
    @Container static final PostgreSQLContainer<?> DB=new PostgreSQLContainer<>("postgres:16.16-bookworm");
    @DynamicPropertySource static void db(DynamicPropertyRegistry r){r.add("spring.datasource.url",DB::getJdbcUrl);r.add("spring.datasource.username",DB::getUsername);r.add("spring.datasource.password",DB::getPassword);}
    @TestConfiguration static class Config {@Bean @Primary Clock testClock(){return Clock.fixed(Instant.parse("2026-03-01T00:00:00Z"),ZoneId.of("Asia/Seoul"));}}
    @MockitoBean BudgetRecalculationPort recalc;
    @MockitoBean com.loanrun.budget.service.BudgetReadService budget;
    @MockitoBean PlanQueryPort overall;
    @Autowired JdbcTemplate jdbc;@Autowired ObjectMapper mapper;@Autowired LoanRegistrationService registration;
    @Autowired LoanSourceRepository sources;@Autowired PlanViewQueryService views;@Autowired RevisionPort revisions;@Autowired Clock clock;
    @LocalServerPort int port;
    @BeforeEach void setup()throws Exception {
        if(jdbc.queryForObject("SELECT to_regclass('repayment_plan_versions')",String.class)==null)jdbc.execute(Files.readString(Path.of("docs/sql/repayment_plan_versions_proposal.sql")));
        when(budget.capacity(anyLong(),any())).thenAnswer(i->new BudgetCapacity(i.getArgument(1),2000000L,2000000L,0,2000000L,0,0,2000000L,0,0L,"RECORDED","OPEN",List.of()));
        when(budget.monthlyOverrides(anyLong(),any())).thenReturn(List.of());
    }
    HttpClient browser(){return HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();}
    HttpResponse<String> get(HttpClient b,String path)throws Exception{return b.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+path)).GET().build(),HttpResponse.BodyHandlers.ofString());}
    JsonNode json(HttpResponse<String> r)throws Exception{return mapper.readTree(r.body());}
    long owner(HttpClient b)throws Exception{return json(get(b,"/api/users/me/")).path("data").path("user_id").longValue();}
    long loan(long owner,boolean bullet,boolean exempt)throws Exception {
        var body=new LoanPreviewTest().body();body.put("fee_exempt",exempt);
        if(bullet){body.put("repayment_method","BULLET");body.putNull("prepayment_policy");body.put("initial_savings_balance",2000000);}
        return registration.create(owner,body,null).body().path("data").path("loan_id").longValue();
    }
    JsonNode list(HttpClient b,String query)throws Exception {var r=get(b,"/api/repayment-plans/"+query);assertThat(r.statusCode()).as(r.body()).isEqualTo(200);assertThat(json(r).path("code").asText()).isEqualTo("REPAYMENT_PLAN_LIST_SUCCESS");return json(r).path("data");}
    JsonNode schedule(HttpClient b,String query)throws Exception{var r=get(b,"/api/payments/schedule/"+query);assertThat(r.statusCode()).as(r.body()).isEqualTo(200);return json(r).path("data");}
    @Test void savedDueIdsCalendarAndUnknownGoalsAreReadOnly()throws Exception {
        var b=browser();long user=owner(b),id=loan(user,false,true),revision=revisions.current(user);var before=sources.loanSnapshot(user,id,LocalDate.of(2026,3,1));var d=schedule(b,"?month=2026-03");
        assertThat(d.path("due_items").size()).isEqualTo(1);var due=d.path("due_items").get(0);assertThat(due.path("due_item_id").longValue()).isPositive();assertThat(due.path("required_amount").longValue()).isEqualTo(1260000);assertThat(due.path("planned_extra_amount").isNull()).isTrue();assertThat(d.path("calendar_events").get(0).path("due_item_id")).isEqualTo(due.path("due_item_id"));assertThat(sources.loanSnapshot(user,id,LocalDate.of(2026,3,1))).isEqualTo(before);assertThat(revisions.current(user)).isEqualTo(revision);
    }
    @Test void extraActualDoesNotFulfillRegularAndActualsUsePaidAtMonth()throws Exception {
        var b=browser();long user=owner(b),id=loan(user,false,true);jdbc.update("INSERT INTO payments(user_id,loan_id,paid_at,payment_type,amount,principal_amount,extra_amount,interest_amount,fee_amount,savings_used_amount,created_at) VALUES (?,?,'2026-03-01','EXTRA',100,0,100,0,0,0,CURRENT_TIMESTAMP)",user,id);
        var d=schedule(b,"?month=2026-03&loan_id="+id);assertThat(d.path("due_items").get(0).path("status").asText()).isEqualTo("UPCOMING");assertThat(d.path("monthly_totals").path("actual_repayment").longValue()).isEqualTo(100);assertThat(d.path("monthly_totals").path("remaining_amount").longValue()).isEqualTo(1260000);
    }
    @Test void partialRegularAllocationChangesOnlyItsActualDue()throws Exception {
        var b=browser();long user=owner(b),id=loan(user,false,true);long p=jdbc.queryForObject("INSERT INTO payments(user_id,loan_id,paid_at,payment_type,amount,principal_amount,extra_amount,interest_amount,fee_amount,savings_used_amount,created_at) VALUES (?,?,'2026-03-01','REGULAR',100,0,0,100,0,0,CURRENT_TIMESTAMP) RETURNING id",Long.class,user,id);jdbc.update("INSERT INTO payment_allocations(user_id,loan_id,payment_id,due_item_id,principal_amount,interest_amount,fee_amount) SELECT ?,?,?,id,0,100,0 FROM due_items WHERE loan_id=?",user,id,p,id);
        var d=schedule(b,"?month=2026-03");assertThat(d.path("due_items").get(0).path("status").asText()).isEqualTo("PARTIAL");assertThat(d.path("monthly_totals").path("remaining_amount").longValue()).isEqualTo(1259900);
    }
    @Test void invalidQueriesOwnershipAndUnmaterializedFutureAreExplicit()throws Exception {
        var b=browser();var other=browser();long user=owner(b),id=loan(user,false,true);owner(other);
        for(var q:List.of("","?month=2026-13","?month=2026-3","?month=0000-01","?month=2026-03&month=2026-04","?month=2026-03&user_id=1")){var r=get(b,"/api/payments/schedule/"+q);assertThat(r.statusCode()).isEqualTo(400);assertThat(json(r).path("code").asText()).isEqualTo("INVALID_MONTH");}
        assertThat(get(other,"/api/payments/schedule/?month=2026-03&loan_id="+id).statusCode()).isEqualTo(404);assertThat(get(b,"/api/payments/schedule/?month=2026-04").statusCode()).isEqualTo(503);assertThat(schedule(b,"?month=2025-12").path("due_items").size()).isZero();
    }
}
