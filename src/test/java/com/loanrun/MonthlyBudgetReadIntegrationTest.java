package com.loanrun;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.*;
import com.loanrun.common.contract.*;
import com.loanrun.common.contract.FinanceModels.*;
import java.net.*;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@Testcontainers
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"loanrun.demo-mode=true","server.servlet.session.cookie.secure=false"})
@Import(BootstrapIntegrationTest.TestConfig.class)
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class MonthlyBudgetReadIntegrationTest {
    @Container static final PostgreSQLContainer<?> DB=new PostgreSQLContainer<>("postgres:16.16-bookworm");
    @DynamicPropertySource static void database(DynamicPropertyRegistry r){r.add("spring.datasource.url",DB::getJdbcUrl);r.add("spring.datasource.username",DB::getUsername);r.add("spring.datasource.password",DB::getPassword);}
    @LocalServerPort int port;
    @Autowired ObjectMapper mapper;@Autowired JdbcTemplate jdbc;@Autowired RevisionPort revisions;
    @Autowired BudgetQueryPort budgetQueries;@Autowired BudgetRecalculationPort recalculation;@Autowired TransactionTemplate transactions;
    @MockitoBean LoanQueryPort loans;@MockitoBean PlanQueryPort plans;
    final AtomicLong owner=new AtomicLong(),paid=new AtomicLong(),pastPaid=new AtomicLong();
    static final YearMonth MONTH=YearMonth.of(2026,11);
    @BeforeEach void sourceFacts(){
        owner.set(0);paid.set(0);pastPaid.set(0);
        when(loans.dueSummary(anyLong(),any())).thenAnswer(i->{YearMonth m=i.getArgument(1);long due=(long)i.getArgument(0)==owner.get()&&m.equals(MONTH)?800000:0;return new DueSummary(m,due,0,0,due,0,m.atDay(25),due>0?1:0,0,0,0,0);});
        when(loans.paymentSummary(anyLong(),any())).thenAnswer(i->{YearMonth m=i.getArgument(1);boolean owned=(long)i.getArgument(0)==owner.get();long cash=owned?(m.equals(MONTH)?paid.get():pastPaid.get()):0;return new PaymentSummary(m,0,cash,0,0,cash,0,0,owned,0,0,cash>0?Map.of(101L,cash):Map.of());});
        when(loans.savingSummary(anyLong(),any())).thenAnswer(i->new SavingSummary(i.getArgument(1),0,0,0,0,true,0,Map.of()));
        when(plans.plannedAllocations(anyLong(),any())).thenAnswer(i->(long)i.getArgument(0)==owner.get()?List.of(new PlannedAmount(101,AllocationType.EXTRA_PAYMENT,100000,null)):List.of());
    }
    HttpClient browser(){return HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();}
    HttpResponse<String> call(HttpClient c,String method,String path,String body)throws Exception{
        return c.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+path)).header("Origin","http://localhost:5173").header("Content-Type","application/json")
            .method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }
    JsonNode json(HttpResponse<String> r)throws Exception{return mapper.readTree(r.body());}
    long initialize(HttpClient c)throws Exception{
        long id=json(call(c,"GET","/api/users/me/",null)).path("data").path("user_id").longValue();owner.set(id);
        assertThat(call(c,"PUT","/api/budgets/profile/","{\"monthly_income\":3000000,\"income_type\":\"REGULAR\",\"payday\":25,\"fixed_essential_expenses\":800000,\"variable_essential_expenses\":200000,\"discretionary_expenses\":500000,\"savings\":200000}").statusCode()).isEqualTo(200);return id;
    }
    @Test void forecastReadsAreFlatAndPureCapacityDoesNotWriteOrAdvanceRevision()throws Exception{
        var c=browser();long id=initialize(c);long revision=revisions.current(id);
        var response=call(c,"GET","/api/budgets/2026-11/",null);assertThat(response.statusCode()).isEqualTo(200);
        var data=json(response).path("data");assertThat(data.path("available_cash").longValue()).isEqualTo(500000);
        assertThat(data.path("income_status").asText()).isEqualTo("FORECAST");assertThat(data.path("actual_income").isNull()).isTrue();assertThat(data.path("actual_available_cash").isNull()).isTrue();assertThat(data.has("metrics")).isFalse();
        long count=jdbc.queryForObject("SELECT count(*) FROM monthly_budgets WHERE user_id=?",Long.class,id);
        assertThat(budgetQueries.capacity(id,MONTH).allocationCapacity()).isEqualTo(500000);
        var future=call(c,"GET","/api/budgets/2026-12/",null);assertThat(future.statusCode()).isEqualTo(200);
        assertThat(json(future).path("data").path("actual_income").isNull()).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM monthly_budgets WHERE user_id=?",Long.class,id)).isEqualTo(count);
        assertThat(revisions.current(id)).isEqualTo(revision);
    }
    @Test void unknownProfileAndOtherSessionDoNotBorrowOwnerMoneyOrPlans()throws Exception{
        var c=browser();initialize(c);var other=browser();var data=json(call(other,"GET","/api/budgets/2026-11/",null)).path("data");
        assertThat(data.path("available_cash").isNull()).isTrue();assertThat(data.path("monthly_income").isNull()).isTrue();
        assertThat(data.path("allocation").path("allocations").isEmpty()).isTrue();
        for(String month:List.of("2026-1","0000-01","2026-13"))assertThat(json(call(c,"GET","/api/budgets/"+month+"/",null)).path("code").asText()).isEqualTo("INVALID_MONTH");
    }
    @Test void observedZeroIsRealAndSpentCashCannotDisappearBehindALowerPlan()throws Exception{
        var c=browser();long id=initialize(c);paid.set(400000);
        jdbc.update("UPDATE monthly_budgets SET payday=1,expenses_confirmed=TRUE,source='INPUT',discretionary_expenses=0 WHERE user_id=?",id);
        var data=json(call(c,"GET","/api/budgets/2026-11/",null)).path("data");
        assertThat(data.path("actual_income").longValue()).isEqualTo(3000000);assertThat(data.path("actual_discretionary_expenses").longValue()).isZero();
        assertThat(data.path("actual_available_cash").longValue()).isEqualTo(1400000);
        assertThat(data.path("allocation").path("total_allocated").longValue()).isEqualTo(400000);
        when(loans.paymentSummary(id,MONTH)).thenReturn(new PaymentSummary(MONTH,0,0,0,0,0,0,0,false,0,0,Map.of()));
        assertThat(json(call(c,"GET","/api/budgets/2026-11/",null)).path("data").path("actual_available_cash").isNull()).isTrue();
    }
    @Test void pastCorrectionsRecomputeReserveAndOnlyNextMonthCarryInCallerTransaction()throws Exception{
        var c=browser();long id=initialize(c);
        jdbc.update("""
            INSERT INTO monthly_budgets(user_id,year_month,income_type,expected_income,payday,fixed_essential_expenses,variable_essential_expenses,
                discretionary_expenses,discretionary_limit,savings,expenses_confirmed,reserve_amount,created_at,updated_at)
            VALUES (?,'2026-10-01','REGULAR',3000000,1,800000,200000,500000,500000,200000,TRUE,100000,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
            """,id);
        long revision=revisions.current(id);call(c,"GET","/api/budgets/2026-11/",null);
        assertThat(budgetQueries.capacity(id,MONTH).carryIn()).isEqualTo(1200000);
        pastPaid.set(1000000);transactions.executeWithoutResult(s->recalculation.recalculateFrom(id,MONTH.minusMonths(1)));
        assertThat(budgetQueries.capacity(id,MONTH).carryIn()).isEqualTo(200000);
        jdbc.update("UPDATE monthly_budgets SET expenses_confirmed=FALSE WHERE user_id=? AND year_month='2026-10-01'",id);
        transactions.executeWithoutResult(s->recalculation.recalculateFrom(id,MONTH.minusMonths(1)));
        assertThat(budgetQueries.capacity(id,MONTH).carryIn()).isZero();
        assertThat(jdbc.queryForObject("SELECT carry_out FROM monthly_budgets WHERE user_id=? AND year_month='2026-10-01'",Long.class,id)).isNull();
        assertThat(revisions.current(id)).isEqualTo(revision);
    }
    @Test void irregularPlanningUsesThreeCompletedObservedMonthsIncludingConfirmedZero()throws Exception{
        var c=browser();long id=initialize(c);
        var body=mapper.createObjectNode().put("monthly_income",900000).put("income_type","IRREGULAR").putNull("payday")
            .put("fixed_essential_expenses",800000).put("variable_essential_expenses",200000).put("discretionary_expenses",500000).put("savings",200000);
        assertThat(call(c,"PUT","/api/budgets/profile/",body.toString()).statusCode()).isEqualTo(200);
        long[] amounts={0,1200000,1800000};
        for(int i=0;i<3;i++){
            LocalDate month=LocalDate.of(2026,8+i,1);
            long budgetId=jdbc.queryForObject("""
                INSERT INTO monthly_budgets(user_id,year_month,income_type,expected_income,fixed_essential_expenses,variable_essential_expenses,
                    discretionary_expenses,discretionary_limit,savings,income_confirmed,created_at,updated_at)
                VALUES (?,?,'IRREGULAR',900000,800000,200000,500000,500000,200000,TRUE,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP) RETURNING id
                """,Long.class,id,java.sql.Date.valueOf(month));
            if(amounts[i]>0)jdbc.update("INSERT INTO income_entries(user_id,budget_id,entry_type,received_date,amount,created_at) VALUES (?,?,'IRREGULAR',?,?,CURRENT_TIMESTAMP)",
                id,budgetId,java.sql.Date.valueOf(month.withDayOfMonth(15)),amounts[i]);
        }
        var data=json(call(c,"GET","/api/budgets/2026-11/",null)).path("data");
        assertThat(data.path("planning_monthly_income").longValue()).isEqualTo(1000000);
        assertThat(data.path("actual_income").isNull()).isTrue();assertThat(data.path("monthly_income").isNull()).isTrue();
    }
}
