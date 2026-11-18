package com.loanrun;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.loanrun.common.contract.*;
import com.loanrun.common.contract.FinanceModels.*;
import com.loanrun.common.contract.PlanPresentationPort.PlanView;
import com.loanrun.common.error.ApiException;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@Testcontainers
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"loanrun.demo-mode=true","server.servlet.session.cookie.secure=false"})
@Import(BootstrapIntegrationTest.TestConfig.class)
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class MonthlyAllocationIntegrationTest {
    @Container static final PostgreSQLContainer<?> DB=new PostgreSQLContainer<>("postgres:16.16-bookworm");
    @DynamicPropertySource static void database(DynamicPropertyRegistry r){r.add("spring.datasource.url",DB::getJdbcUrl);r.add("spring.datasource.username",DB::getUsername);r.add("spring.datasource.password",DB::getPassword);}
    @LocalServerPort int port;
    @Autowired ObjectMapper mapper; @Autowired JdbcTemplate jdbc; @Autowired RevisionPort revisions; @Autowired BudgetQueryPort budgets;
    @MockitoBean LoanQueryPort loans; @MockitoBean PlanQueryPort plans; @MockitoBean PlanPresentationPort presentation;
    final AtomicLong owner=new AtomicLong(),cash=new AtomicLong(),saving=new AtomicLong();
    static final YearMonth MONTH=YearMonth.of(2026,11);
    @BeforeEach void facts(){
        owner.set(0);cash.set(0);saving.set(0);
        when(loans.dueSummary(anyLong(),any())).thenAnswer(i->new DueSummary(i.getArgument(1),0,0,0,0,0,null,0,0,0,0,0));
        when(loans.paymentSummary(anyLong(),any())).thenAnswer(i->{long value=(long)i.getArgument(0)==owner.get()?cash.get():0;return new PaymentSummary(i.getArgument(1),0,value,0,0,value,0,0,true,0,0,value>0?Map.of(1L,value):Map.of());});
        when(loans.savingSummary(anyLong(),any())).thenAnswer(i->{long value=(long)i.getArgument(0)==owner.get()?saving.get():0;return new SavingSummary(i.getArgument(1),value,0,0,value,true,0,value>0?Map.of(2L,value):Map.of());});
        when(loans.terms(anyLong(),anyLong(),any())).thenAnswer(i->{
            long user=i.getArgument(0),id=i.getArgument(1);
            if(user!=owner.get()||id<1||id>4)throw new ApiException(org.springframework.http.HttpStatus.NOT_FOUND,"LOAN_NOT_FOUND","대출을 찾을 수 없습니다.");
            var method=id==2?RepaymentMethod.BULLET:id>=3?RepaymentMethod.GRACE_INSTALLMENT:RepaymentMethod.EQUAL_PAYMENT;
            return new LoanTerms(id,user,"가상 대출",LoanType.CREDIT,1000000,1000000,LocalDate.of(2026,11,17),new java.math.BigDecimal("6"),
                RateType.FIXED,method,id>=3?LocalDate.parse(id==3?"2026-12-01":"2026-11-17"):null,RepaymentMethod.EQUAL_PAYMENT,
                LocalDate.of(2026,11,25),25,LocalDate.of(2028,11,25),100000L,100000L,null,PrepaymentPolicy.KEEP_PAYMENT,
                FeeMethod.NONE,java.math.BigDecimal.ZERO,null,null,0,0,LoanStatus.ACTIVE,null);
        });
        when(plans.plannedAllocations(anyLong(),any())).thenReturn(List.of());
        when(plans.overallState(anyLong())).thenReturn(new OverallState(true,"AVALANCHE","AVALANCHE",Instant.parse("2026-11-01T00:00:00Z")));
        when(presentation.monthViews(anyLong(),any(),any(),anyList())).thenAnswer(i->{List<PlannedAmount> amounts=i.getArgument(3);
            return amounts.stream().map(p->{var data=mapper.createObjectNode().put("loan_id",p.loanId()).put("extra_monthly_payment",p.cashAmount()).put("priority",1).put("observed_month_count",0);
                data.putArray("warnings");return mapper.convertValue(data,PlanView.class);}).toList();});
    }
    HttpClient browser(){return HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();}
    HttpResponse<String> call(HttpClient c,String method,String path,String key,String body)throws Exception{
        var request=HttpRequest.newBuilder(URI.create("http://localhost:"+port+path)).header("Origin","http://localhost:5173").header("Content-Type","application/json");
        if(key!=null)request.header("Idempotency-Key",key);
        return c.send(request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }
    JsonNode json(HttpResponse<String> r)throws Exception{return mapper.readTree(r.body());}
    ObjectNode body(long reserve,long extra,long save){var b=mapper.createObjectNode().put("reserve_amount",reserve);var a=b.putArray("allocations");
        if(extra>=0)a.addObject().put("loan_id",1).put("allocation_type","EXTRA_PAYMENT").put("amount",extra);
        if(save>=0)a.addObject().put("loan_id",2).put("allocation_type","REPAYMENT_SAVING").put("amount",save);return b;}
    long initialize(HttpClient c,boolean ownsLoans)throws Exception{
        long id=json(call(c,"GET","/api/users/me/",null,null)).path("data").path("user_id").longValue();if(ownsLoans)owner.set(id);
        assertThat(call(c,"PUT","/api/budgets/profile/",null,"{\"monthly_income\":3000000,\"income_type\":\"REGULAR\",\"payday\":1,\"fixed_essential_expenses\":800000,\"variable_essential_expenses\":200000,\"discretionary_expenses\":500000,\"savings\":200000}").statusCode()).isEqualTo(200);return id;
    }
    HttpResponse<String> save(HttpClient c,String key,ObjectNode body)throws Exception{return call(c,"PUT","/api/budgets/2026-11/allocation/",key,body.toString());}
    @Test void storesOnlyMonthlyOverridesAllowsUnallocatedAndReplaysWithoutChangingRevision()throws Exception{
        var c=browser();long id=initialize(c,true),revision=revisions.current(id);var b=body(100000,200000,300000);
        var first=save(c,"same",b);assertThat(first.statusCode()).as(first.body()).isEqualTo(200);var data=json(first).path("data");
        assertThat(json(first).path("code").asText()).isEqualTo("BUDGET_ALLOCATION_SUCCESS");assertThat(data.path("monthly_override_only").asBoolean()).isTrue();
        assertThat(data.path("allocation").path("total_allocated").longValue()).isEqualTo(600000);
        assertThat(data.path("allocation").path("unallocated_amount").longValue()).isEqualTo(700000);
        assertThat(data.path("strategy_code").isNull()).isTrue();assertThat(data.path("updated_plans").get(0).path("priority").isNull()).isTrue();
        assertThat(budgets.monthlyOverrides(id,MONTH)).hasSize(2);assertThat(budgets.monthlyOverrides(id,MONTH.plusMonths(1))).isEmpty();
        assertThat(revisions.current(id)).isEqualTo(revision+1);assertThat(save(c,"same",b).body()).isEqualTo(first.body());assertThat(revisions.current(id)).isEqualTo(revision+1);
        assertThat(save(c,"same",body(1,200000,300000)).statusCode()).isEqualTo(409);
        verify(presentation,times(1)).monthViews(eq(id),any(),eq(MONTH),argThat(a->a.size()==2));
    }
    @Test void alreadySpentMoneyRemainsCountedEvenWhenLoanOmittedAndCannotReduceItsGoal()throws Exception{
        var c=browser();long id=initialize(c,true);cash.set(300000);saving.set(200000);long revision=revisions.current(id);
        assertThat(save(c,"low-extra",body(0,299999,200000)).statusCode()).isEqualTo(400);
        assertThat(save(c,"low-saving",body(0,300000,199999)).statusCode()).isEqualTo(400);
        assertThat(save(c,"over",body(800001,-1,-1)).statusCode()).isEqualTo(400);assertThat(revisions.current(id)).isEqualTo(revision);
        var data=json(save(c,"spent",body(100000,-1,-1))).path("data");
        assertThat(data.path("allocation").path("total_allocated").longValue()).isEqualTo(600000);
        assertThat(data.path("remaining_allocation_capacity").longValue()).isEqualTo(800000);
    }
    @Test void rejectsWrongMethodGraceDuplicatesInvalidMonthsAmountsAndForeignLoans()throws Exception{
        var c=browser();long id=initialize(c,true),revision=revisions.current(id);var invalid=new ArrayList<ObjectNode>();
        invalid.add(body(-1,0,0));invalid.add(body(0,Long.MAX_VALUE,1));invalid.add(body(0,1300001,-1));
        invalid.add(mapper.createObjectNode().put("reserve_amount",0).putNull("allocations"));
        var wrong=body(0,1,-1);((ObjectNode)wrong.path("allocations").get(0)).put("allocation_type","REPAYMENT_SAVING");invalid.add(wrong);
        var grace=body(0,1,-1);((ObjectNode)grace.path("allocations").get(0)).put("loan_id",3);invalid.add(grace);
        var duplicate=body(0,1,-1);((com.fasterxml.jackson.databind.node.ArrayNode)duplicate.path("allocations")).add(duplicate.path("allocations").get(0).deepCopy());invalid.add(duplicate);
        for(var b:invalid){var r=save(c,"invalid",b);assertThat(r.statusCode()).as(r.body()).isEqualTo(400);assertThat(json(r).path("code").asText()).isEqualTo("ALLOCATION_VALIDATION_ERROR");}
        for(String month:List.of("2026-10","2026-12"))assertThat(call(c,"PUT","/api/budgets/"+month+"/allocation/","month",body(0,0,0).toString()).statusCode()).isEqualTo(400);
        assertThat(save(c,null,body(0,0,0)).statusCode()).isEqualTo(400);assertThat(revisions.current(id)).isEqualTo(revision);
        var other=browser();long otherId=initialize(other,false);assertThat(save(other,"foreign",body(0,1,-1)).statusCode()).isEqualTo(404);
        assertThat(budgets.monthlyOverrides(otherId,MONTH)).isEmpty();
        var boundary=body(0,1,-1);((ObjectNode)boundary.path("allocations").get(0)).put("loan_id",4);assertThat(save(c,"boundary",boundary).statusCode()).isEqualTo(200);
    }
    @Test void replayAfterProDowngradeRedactsStoredStrategyAndPriorityWithoutResaving()throws Exception{
        var c=browser();long id=initialize(c,true);assertThat(call(c,"PATCH","/api/settings/subscription/",null,"{\"plan_type\":\"PRO\"}").statusCode()).isEqualTo(200);
        var b=body(0,100000,100000);var pro=json(save(c,"pro",b)).path("data");
        assertThat(pro.path("strategy_code").asText()).isEqualTo("AVALANCHE");assertThat(pro.path("updated_plans").get(0).path("priority").asInt()).isEqualTo(1);
        call(c,"PATCH","/api/settings/subscription/",null,"{\"plan_type\":\"FREE\"}");long revision=revisions.current(id);
        var free=json(save(c,"pro",b)).path("data");assertThat(free.path("strategy_code").isNull()).isTrue();assertThat(free.path("overall_plan_exists").asBoolean()).isFalse();
        assertThat(free.path("updated_plans").get(0).path("priority").isNull()).isTrue();assertThat(revisions.current(id)).isEqualTo(revision);
        call(c,"PATCH","/api/settings/subscription/",null,"{\"plan_type\":\"PRO\"}");assertThat(json(save(c,"pro",b)).path("data").path("strategy_code").asText()).isEqualTo("AVALANCHE");
        verify(presentation,times(1)).monthViews(eq(id),any(),eq(MONTH),anyList());
    }
    @Test void planProjectionFailureRollsBackOverrideRevisionAndStoredResponse()throws Exception{
        var c=browser();long id=initialize(c,true),revision=revisions.current(id);var b=body(100000,100000,100000);
        when(presentation.monthViews(eq(id),any(),eq(MONTH),anyList())).thenThrow(ApiException.unavailable());
        assertThat(save(c,"retry",b).statusCode()).isEqualTo(503);assertThat(revisions.current(id)).isEqualTo(revision);assertThat(budgets.monthlyOverrides(id,MONTH)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT allocation_overridden FROM monthly_budgets WHERE user_id=?",Boolean.class,id)).isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM idempotency_records WHERE user_id=?",Long.class,id)).isZero();
        doReturn(List.of()).when(presentation).monthViews(eq(id),any(),eq(MONTH),anyList());assertThat(save(c,"retry",b).statusCode()).isEqualTo(200);
    }
}
