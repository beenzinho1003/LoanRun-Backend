package com.loanrun;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.*;
import com.loanrun.common.contract.*;
import com.loanrun.common.contract.DashboardQueryPort.*;
import com.loanrun.common.contract.FinanceModels.*;
import com.loanrun.common.error.ApiException;
import com.loanrun.risk.domain.RiskInputs;
import com.loanrun.risk.service.RiskInputService;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import com.loanrun.risk.service.RiskRateStress;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@Testcontainers
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"loanrun.demo-mode=true","server.servlet.session.cookie.secure=false"})
@Import(BootstrapIntegrationTest.TestConfig.class)
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class DashboardIntegrationTest {
    @Container static final PostgreSQLContainer<?> DB=new PostgreSQLContainer<>("postgres:16.16-bookworm");
    @DynamicPropertySource static void database(DynamicPropertyRegistry r){r.add("spring.datasource.url",DB::getJdbcUrl);r.add("spring.datasource.username",DB::getUsername);r.add("spring.datasource.password",DB::getPassword);}
    @LocalServerPort int port;@Autowired ObjectMapper mapper;@Autowired RevisionPort revisions;@Autowired JdbcTemplate jdbc;
    @MockitoBean DashboardQueryPort source;@MockitoBean RiskInputService risks;
    @MockitoSpyBean RiskRateStress rates;
    final AtomicLong owner=new AtomicLong();static final YearMonth MONTH=YearMonth.of(2026,11);
    DueItem due(long id,long loan,String status){return new DueItem(id,loan,MONTH.atDay((int)id),100000,20000,30000,0,70000,status,"가상",120000,status,20000,false,40000,5000);}
    Snapshot ledger(Long selected){return new Snapshot(List.of(new LoanProgress(1,"가상1",500000,500000L,new BigDecimal("50"),0,"ACTIVE",0),
        new LoanProgress(2,"가상2",200000,0L,BigDecimal.ZERO,10000,"ACTIVE",40000)),selected==null?700000:200000,new BigDecimal("41.67"),null,
        due(25,selected==null?1:2,"PARTIAL"),new MonthlySummary(100000,30000,70000,20000,0,0,1,70000,40000,5000),List.of(due(26,2,"UPCOMING"),due(25,selected==null?1:2,"PARTIAL")));}
    RiskInputs facts(long user){
        var loan=new LoanTerms(1,user,"가상",LoanType.CREDIT,1000000,500000,MONTH.atDay(17),BigDecimal.ZERO,RateType.FIXED,
            RepaymentMethod.EQUAL_PAYMENT,null,null,MONTH.atDay(25),25,MONTH.plusYears(2).atDay(25),100000L,100000L,null,
            PrepaymentPolicy.KEEP_PAYMENT,FeeMethod.NONE,BigDecimal.ZERO,null,null,0,0,LoanStatus.ACTIVE,null);
        return new RiskInputs(user,MONTH.atDay(17),MONTH,List.of(loan),List.of(),
            new FinanceSnapshot(MONTH,IncomeType.REGULAR,1000000L,1000000L,100000L,100000L,100000L,100000L,100000L,0L,600000L,true,"INPUT"),
            new BudgetCapacity(MONTH,200000L,200000L,0,200000L,0,0,200000L,0,0L,"ASSUMED","OPEN",List.of()),
            new DueSummary(MONTH,100000,0,0,100000,0,MONTH.atDay(25),1,0,0,0,0),20000L,0L,BigDecimal.ZERO);
    }
    @BeforeEach void setup(){
        doAnswer(i->{assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();return i.callRealMethod();}).when(rates).probe(any());
        when(source.snapshot(anyLong(),nullable(Long.class),any())).thenAnswer(i->{
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            if((long)i.getArgument(0)!=owner.get())throw new ApiException(org.springframework.http.HttpStatus.NOT_FOUND,"LOAN_NOT_FOUND","대출을 찾을 수 없습니다.");
            return ledger(i.getArgument(1));
        });
        when(risks.snapshot(anyLong(),any())).thenAnswer(i->facts(i.getArgument(0)));
    }
    HttpClient browser(){return HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();}
    HttpResponse<String> call(HttpClient c,String method,String path,String body)throws Exception{return c.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+path))
        .header("Origin","http://localhost:5173").header("Content-Type","application/json").method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());}
    JsonNode json(HttpResponse<String> r)throws Exception{return mapper.readTree(r.body());}
    long initialize(HttpClient c,boolean owned,boolean pro)throws Exception{long id=json(call(c,"GET","/api/users/me/",null)).path("data").path("user_id").longValue();if(owned)owner.set(id);
        if(pro)call(c,"PATCH","/api/settings/subscription/","{\"plan_type\":\"PRO\"}");return id;}
    @Test void freeShowsLedgerAndHidesProValuesWithoutReadingRiskOrReports()throws Exception{
        var c=browser();long id=initialize(c,true,false),rev=revisions.current(id);
        var response=call(c,"GET","/api/dashboard/",null);assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(json(response).path("code").asText()).isEqualTo("DASHBOARD_DETAIL_SUCCESS");var data=json(response).path("data");
        assertThat(data.path("total_remaining_principal").longValue()).isEqualTo(700000);
        assertThat(data.path("estimated_payoff_date").isNull()).isTrue();assertThat(data.path("risk_summary").path("available_cash").isNull()).isTrue();
        assertThat(data.path("risk_summary").path("locked").booleanValue()).isTrue();
        assertThat(data.path("report_status").path("report_id").isNull()).isTrue();assertThat(data.path("report_status").path("is_new").booleanValue()).isFalse();
        assertThat(data.path("monthly_checklist").get(0).path("due_item_id").longValue()).isEqualTo(25);
        assertThat(data.path("next_payment").path("status").asText()).isEqualTo("PARTIAL");
        assertThat(data.path("monthly_summary").path("actual_saving_amount").longValue()).isEqualTo(5000);
        assertThat(revisions.current(id)).isEqualTo(rev);verifyNoInteractions(risks);
    }
    @Test void selectedLedgerKeepsCompleteSelectorAndHouseholdRiskAndDoesNotGenerateReports()throws Exception{
        var c=browser();long id=initialize(c,true,true),rev=revisions.current(id);
        var data=json(call(c,"GET","/api/dashboard/?loan_id=2",null)).path("data");
        assertThat(data.path("loans").size()).isEqualTo(2);assertThat(data.path("total_remaining_principal").longValue()).isEqualTo(200000);
        assertThat(data.path("selected_loan_id").longValue()).isEqualTo(2);
        assertThat(data.path("risk_summary").path("available_cash").longValue()).isEqualTo(200000);
        assertThat(data.path("risk_summary").path("score").decimalValue()).isEqualByComparingTo("7.5");
        assertThat(data.path("report_status").path("status").asText()).isEqualTo("NOT_GENERATED");
        verify(risks).snapshot(id,MONTH);assertThat(revisions.current(id)).isEqualTo(rev);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM report_jobs WHERE user_id=?",Long.class,id)).isZero();
    }
    @Test void otherSessionAndMissingSelectionAre404AndInvalidIdsAre400()throws Exception{
        var c=browser();initialize(c,true,false);var other=browser();initialize(other,false,false);
        assertThat(call(other,"GET","/api/dashboard/?loan_id=1",null).statusCode()).isEqualTo(404);
        assertThat(call(c,"GET","/api/dashboard/?loan_id=999",null).statusCode()).isEqualTo(404);
        for(String value:List.of("0","-1","1.5","abc"))assertThat(call(c,"GET","/api/dashboard/?loan_id="+value,null).statusCode()).isEqualTo(400);
        assertThat(call(c,"GET","/api/dashboard",null).statusCode()).isEqualTo(404);
    }
    @Test void unavailableSourceDoesNotReturnAnEmptySuccessfulDashboard()throws Exception{
        var c=browser();long id=initialize(c,true,false),rev=revisions.current(id);
        doThrow(ApiException.unavailable()).when(source).snapshot(eq(id),nullable(Long.class),any());
        var response=call(c,"GET","/api/dashboard/",null);assertThat(response.statusCode()).isEqualTo(503);
        assertThat(json(response).path("code").asText()).isEqualTo("SERVICE_UNAVAILABLE");assertThat(revisions.current(id)).isEqualTo(rev);
    }
    @Test void pendingReportRetainsLastSuccessDateWithoutNewBadgeAndFreeTransitionHidesIt()throws Exception{
        var c=browser();long id=initialize(c,true,true),rev=revisions.current(id);
        jdbc.update("""
            INSERT INTO report_jobs(user_id,scope_key,data_revision,basis_month,status,input_payload,created_at,updated_at,generated_at)
            VALUES (?,'HOUSEHOLD',?,'2026-10-01','COMPLETED','{}','2026-11-16T03:00:00Z','2026-11-16T03:00:00Z','2026-11-16T03:00:00Z'),
            (?,'HOUSEHOLD',?,'2026-11-01','PENDING','{}','2026-11-17T03:00:00Z','2026-11-17T03:00:00Z',NULL)
            """,id,rev,id,rev);
        var report=json(call(c,"GET","/api/dashboard/",null)).path("data").path("report_status");
        assertThat(report.path("status").asText()).isEqualTo("PENDING");assertThat(report.path("updated_at").asText()).isEqualTo("2026-11-16T12:00:00+09:00");
        assertThat(report.path("is_new").booleanValue()).isFalse();
        call(c,"PATCH","/api/settings/subscription/","{\"plan_type\":\"FREE\"}");
        report=json(call(c,"GET","/api/dashboard/",null)).path("data").path("report_status");
        assertThat(report.path("updated_at").isNull()).isTrue();assertThat(report.path("report_id").isNull()).isTrue();
    }
}
