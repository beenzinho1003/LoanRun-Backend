package com.loanrun;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.*;
import com.loanrun.common.contract.*;
import com.loanrun.common.contract.FinanceModels.*;
import com.loanrun.common.error.ApiException;
import com.loanrun.risk.domain.RiskInputs;
import com.loanrun.risk.dto.RiskDtos.*;
import com.loanrun.risk.service.*;
import com.loanrun.risk.service.RiskDetailHistorySource.*;
import java.math.BigDecimal;
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
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@Testcontainers
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"loanrun.demo-mode=true","server.servlet.session.cookie.secure=false"})
@Import(BootstrapIntegrationTest.TestConfig.class)
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class RiskDetailIntegrationTest {
    @Container static final PostgreSQLContainer<?> DB=new PostgreSQLContainer<>("postgres:16.16-bookworm");
    @DynamicPropertySource static void database(DynamicPropertyRegistry r){r.add("spring.datasource.url",DB::getJdbcUrl);r.add("spring.datasource.username",DB::getUsername);r.add("spring.datasource.password",DB::getPassword);}
    @LocalServerPort int port;@Autowired ObjectMapper mapper;@Autowired RevisionPort revisions;
    @MockitoBean RiskDetailHistorySource history;@MockitoBean RiskInputService inputs;@MockitoBean LoanQueryPort loans;
    @MockitoSpyBean RiskSimulationService simulations;
    final AtomicLong owner=new AtomicLong();static final YearMonth MONTH=YearMonth.of(2026,11);
    LoanTerms loan(long user,long id,RepaymentMethod method,LocalDate maturity,LocalDate grace,LocalDate reset){
        return new LoanTerms(id,user,"가상",LoanType.CREDIT,500000,500000,MONTH.atDay(17),BigDecimal.ZERO,RateType.FIXED,
            method,grace,method==RepaymentMethod.GRACE_INSTALLMENT?RepaymentMethod.EQUAL_PAYMENT:null,MONTH.atDay(25),25,maturity,100000L,100000L,null,
            PrepaymentPolicy.KEEP_PAYMENT,FeeMethod.NONE,BigDecimal.ZERO,null,null,0,0,LoanStatus.ACTIVE,reset);
    }
    RiskInputs facts(long user){
        var terms=List.of(loan(user,1,RepaymentMethod.EQUAL_PAYMENT,MONTH.plusYears(1).atDay(25),null,MONTH.atDay(17)),
            loan(user,2,RepaymentMethod.BULLET,MONTH.plusMonths(1).atDay(17),null,null),
            loan(user,3,RepaymentMethod.GRACE_INSTALLMENT,MONTH.plusYears(1).atDay(25),MONTH.plusMonths(2).atDay(1),null));
        return new RiskInputs(user,MONTH.atDay(17),MONTH,terms,List.of(),
            new FinanceSnapshot(MONTH,IncomeType.REGULAR,1000000L,1000000L,100000L,100000L,100000L,100000L,100000L,0L,600000L,true,"INPUT"),
            new BudgetCapacity(MONTH,200000L,200000L,0,200000L,0,0,200000L,0,0L,"ASSUMED","OPEN",List.of()),
            new DueSummary(MONTH,100000,0,0,100000,0,MONTH.atDay(25),3,0,0,0,0),null,null,BigDecimal.ZERO);
    }
    ChangeEvent change(Long loan,LocalDate date,Reason reason,Long delta){return new ChangeEvent(loan,date,reason,Direction.INCREASE,delta,new BigDecimal("1"),reason==Reason.RATE?"PP":"KRW",Level.CAUTION,"확정된 원본 변경");}
    @BeforeEach void setup(){
        when(inputs.snapshot(anyLong(),any())).thenAnswer(i->facts(i.getArgument(0)));
        when(loans.paymentSummary(anyLong(),any())).thenReturn(new PaymentSummary(MONTH,0,0,0,0,0,0,0,false,0,0,Map.of()));
        when(loans.savingSummary(anyLong(),any())).thenReturn(new SavingSummary(MONTH,0,0,0,0,false,0,Map.of()));
        when(loans.terms(anyLong(),anyLong(),any())).thenAnswer(i->{
            if((long)i.getArgument(0)!=owner.get()||(long)i.getArgument(1)>3)throw new ApiException(org.springframework.http.HttpStatus.NOT_FOUND,"LOAN_NOT_FOUND","대출을 찾을 수 없습니다.");
            return facts(i.getArgument(0)).loans().get(((Long)i.getArgument(1)).intValue()-1);
        });
        when(history.changes(anyLong(),any())).thenAnswer(i->{assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            return new Recorded(Set.of(MONTH.minusMonths(1)),List.of(change(1L,MONTH.minusMonths(1).atDay(1),Reason.RATE,null),
                change(null,MONTH.minusMonths(1).atDay(31),Reason.EXPENSE,20000L),change(2L,MONTH.minusMonths(2).atDay(1),Reason.RATE,10000L),
                change(null,MONTH.atDay(1),Reason.INCOME,90000L),change(null,MONTH.minusMonths(7).atDay(1),Reason.EXPENSE,90000L)));});
        doAnswer(i->{assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();return i.callRealMethod();})
            .when(simulations).evaluate(any(),eq(ScenarioType.CURRENT));
    }
    HttpClient browser(){return HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();}
    HttpResponse<String> call(HttpClient c,String method,String path,String body)throws Exception{return c.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+path))
        .header("Origin","http://localhost:5173").header("Content-Type","application/json").method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());}
    JsonNode json(HttpResponse<String> r)throws Exception{return mapper.readTree(r.body());}
    long initialize(HttpClient c,boolean owned,boolean pro)throws Exception{long id=json(call(c,"GET","/api/users/me/",null)).path("data").path("user_id").longValue();if(owned)owner.set(id);
        if(pro)call(c,"PATCH","/api/settings/subscription/","{\"plan_type\":\"PRO\"}");return id;}
    @Test void currentUsesHouseholdRiskAndOnlyConfirmedSixMonthChangesAndFutureEvents()throws Exception{
        var c=browser();long id=initialize(c,true,true),rev=revisions.current(id);
        var response=call(c,"GET","/api/risks/",null);assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(json(response).path("code").asText()).isEqualTo("RISK_DETAIL_SUCCESS");var data=json(response).path("data");
        assertThat(data.path("score").decimalValue()).isEqualByComparingTo("7.5");assertThat(data.path("checks").size()).isEqualTo(4);
        assertThat(data.path("six_month_changes").size()).isEqualTo(3);assertThat(data.path("six_month_summary").path("net_monthly_burden_delta").isNull()).isTrue();
        assertThat(data.path("six_month_summary").path("rate_change_count").intValue()).isEqualTo(2);
        assertThat(data.path("upcoming_events").size()).isEqualTo(3);
        assertThat(data.path("upcoming_events").get(0).path("d_day").intValue()).isZero();
        assertThat(data.path("upcoming_events").get(1).path("is_imminent").booleanValue()).isTrue();
        assertThat(data.path("upcoming_events").get(2).path("is_imminent").booleanValue()).isFalse();
        assertThat(revisions.current(id)).isEqualTo(rev);
    }
    @Test void selectedLoanFiltersDetailsButDoesNotNarrowHouseholdScore()throws Exception{
        var c=browser();initialize(c,true,true);var all=json(call(c,"GET","/api/risks/",null)).path("data");
        var data=json(call(c,"GET","/api/risks/?loan_id=2",null)).path("data");
        assertThat(data.path("score")).isEqualTo(all.path("score"));assertThat(data.path("indicators")).isEqualTo(all.path("indicators"));
        assertThat(data.path("upcoming_events").size()).isEqualTo(1);assertThat(data.path("six_month_changes").size()).isEqualTo(2);
        assertThat(data.path("six_month_summary").path("net_monthly_burden_delta").longValue()).isEqualTo(30000);
    }
    @Test void freeIs403ForeignIs404AndInvalidMonthOrIdIs400()throws Exception{
        var c=browser();initialize(c,true,false);assertThat(call(c,"GET","/api/risks/",null).statusCode()).isEqualTo(403);
        verifyNoInteractions(history,inputs);call(c,"PATCH","/api/settings/subscription/","{\"plan_type\":\"PRO\"}");
        var other=browser();initialize(other,false,true);assertThat(call(other,"GET","/api/risks/?loan_id=1",null).statusCode()).isEqualTo(404);
        for(String query:List.of("month=2026-1","month=2026-13","month=2026-12","month=0000-01","loan_id=0","loan_id=1.5"))
            assertThat(call(c,"GET","/api/risks/?"+query,null).statusCode()).as(query).isEqualTo(400);
        assertThat(call(c,"GET","/api/risks",null).statusCode()).isEqualTo(404);
    }
    @Test void pastMonthUsesExactFrozenAssessmentWithoutCurrentDataOrEngineReads()throws Exception{
        var c=browser();long id=initialize(c,true,true);var frozen=mapper.treeToValue(mapper.readTree("""
            {"level":"CAUTION","data_status":"INSUFFICIENT","score":null,"upcoming_events":[],"history":[],"checks":[],"six_month_changes":[],"strategy_link_available":true}
            """),Detail.class);
        when(history.frozen(id,2L,MONTH.minusMonths(1))).thenReturn(frozen);
        var response=call(c,"GET","/api/risks/?loan_id=2&month=2026-10",null);assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(json(response).path("data").path("level").asText()).isEqualTo("CAUTION");
        assertThat(json(response).path("data").path("score").isNull()).isTrue();verifyNoInteractions(inputs,loans);verify(simulations,never()).evaluate(any(),any());
    }
    @Test void unavailableHistoryOrMissingFrozenMonthIs503WithoutWritingRevision()throws Exception{
        var c=browser();long id=initialize(c,true,true),rev=revisions.current(id);
        doThrow(ApiException.unavailable()).when(history).changes(id,MONTH);
        assertThat(call(c,"GET","/api/risks/",null).statusCode()).isEqualTo(503);
        assertThat(call(c,"GET","/api/risks/?month=2026-10",null).statusCode()).isEqualTo(503);
        assertThat(revisions.current(id)).isEqualTo(rev);verifyNoInteractions(inputs);
    }
    @Test void unobservedAmountsStayNullAndMissingFinanceKeepsIts404Code()throws Exception{
        var c=browser();long id=initialize(c,true,true);var original=facts(id);
        var missingCash=new RiskInputs(id,original.asOf(),MONTH,original.loans(),original.plans(),original.finance(),
            new BudgetCapacity(MONTH,null,null,0,null,0,0,null,0,null,"UNKNOWN","INSUFFICIENT",List.of()),original.due(),null,null,original.variableExposurePct());
        doReturn(missingCash).when(inputs).snapshot(id,MONTH);
        doReturn(new Recorded(Set.of(),List.of())).when(history).changes(id,MONTH);
        var data=json(call(c,"GET","/api/risks/",null)).path("data");
        assertThat(data.path("score").isNull()).isTrue();assertThat(data.path("level").isNull()).isTrue();
        assertThat(data.path("indicators").path("available_cash").isNull()).isTrue();
        assertThat(data.path("six_month_summary").path("net_monthly_burden_delta").isNull()).isTrue();
        assertThat(data.path("six_month_changes").isEmpty()).isTrue();
        doThrow(new ApiException(org.springframework.http.HttpStatus.NOT_FOUND,"FINANCE_PROFILE_NOT_FOUND","기본 재정정보를 찾을 수 없습니다.")).when(inputs).snapshot(id,MONTH);
        var response=call(c,"GET","/api/risks/",null);assertThat(response.statusCode()).isEqualTo(404);
        assertThat(json(response).path("code").asText()).isEqualTo("FINANCE_PROFILE_NOT_FOUND");
    }
}
