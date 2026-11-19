package com.loanrun;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.*;
import com.loanrun.common.contract.*;
import com.loanrun.common.contract.FinanceModels.*;
import com.loanrun.common.error.ApiException;
import com.loanrun.risk.domain.RiskHistoryModels.*;
import com.loanrun.risk.repository.RiskHistoryRepository;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
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
import org.springframework.test.context.bean.override.mockito.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@Testcontainers
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"loanrun.demo-mode=true","server.servlet.session.cookie.secure=false","loanrun.risk-history.enabled=true"})
@Import(BootstrapIntegrationTest.TestConfig.class)
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class FinanceHistoryIntegrationTest {
    @Container static final PostgreSQLContainer<?> DB=new PostgreSQLContainer<>("postgres:16.16-bookworm");
    @DynamicPropertySource static void database(DynamicPropertyRegistry r){r.add("spring.datasource.url",DB::getJdbcUrl);r.add("spring.datasource.username",DB::getUsername);r.add("spring.datasource.password",DB::getPassword);}
    @LocalServerPort int port;@Autowired ObjectMapper mapper;@Autowired JdbcTemplate jdbc;@Autowired RevisionPort revisions;
    @MockitoBean LoanQueryPort loans;@MockitoBean PlanQueryPort plans;@MockitoSpyBean RiskHistoryRepository history;
    static final YearMonth MONTH=YearMonth.of(2026,11);
    @BeforeEach void setup()throws Exception{
        if(jdbc.queryForObject("SELECT to_regclass('finance_change_events')",String.class)==null)jdbc.execute(Files.readString(Path.of("docs/sql/risk_history_proposal.sql")));
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
    String profile(long income,long expenses){return "{\"monthly_income\":"+income+",\"income_type\":\"REGULAR\",\"payday\":1,\"fixed_essential_expenses\":"+expenses+",\"variable_essential_expenses\":0,\"discretionary_expenses\":0,\"savings\":0}";}
    long initialize(HttpClient c)throws Exception{long id=json(call(c,"GET","/api/users/me/",null,null)).path("data").path("user_id").longValue();
        var response=call(c,"PUT","/api/budgets/profile/",null,profile(3000000,1000000));assertThat(response.statusCode()).as(response.body()).isEqualTo(200);return id;}
    List<Change> changes(long id){return history.changes(id,MONTH.atDay(1),MONTH.atEndOfMonth());}
    @Test void initialProfileIsBaselineAndChangesAreImmutableSameRevisionOwnerScopedAndNoopAddsNoEvent()throws Exception{
        var c=browser();long id=initialize(c);assertThat(changes(id)).isEmpty();
        var response=call(c,"PUT","/api/budgets/profile/",null,profile(3200000,0));assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        var rows=changes(id);assertThat(rows).hasSize(2);assertThat(rows).allMatch(row->row.revision()==revisions.current(id)&&row.origin()==Origin.PROFILE);
        assertThat(rows.stream().filter(row->row.kind()==Kind.EXPENSE).findFirst().orElseThrow().afterAmount()).isZero();
        call(c,"PUT","/api/budgets/profile/",null,profile(3200000,0));assertThat(changes(id)).isEqualTo(rows);
        var other=browser();long otherId=initialize(other);assertThat(changes(otherId)).isEmpty();
    }
    @Test void monthlyIncomeReplacementAndExpenseChangeShareTheFinancialTransaction()throws Exception{
        var c=browser();long id=initialize(c);
        String body="{\"fixed_essential_expenses\":500000,\"variable_essential_expenses\":0,\"discretionary_expenses\":0,\"savings\":0,\"income_entries\":[{\"entry_type\":\"EXTRA\",\"received_date\":\"2026-11-15\",\"amount\":200000}]}";
        var response=call(c,"PUT","/api/budgets/2026-11/",null,body);assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        var rows=changes(id);assertThat(rows).hasSize(2);assertThat(rows).allMatch(row->row.origin()==Origin.MONTHLY_BUDGET&&row.revision()==revisions.current(id));
        assertThat(rows.stream().filter(row->row.kind()==Kind.INCOME).findFirst().orElseThrow().afterAmount()).isEqualTo(3200000);
        call(c,"PUT","/api/budgets/profile/",null,profile(9000000,9000000));assertThat(changes(id)).isEqualTo(rows); // INPUT month is preserved.
    }
    @Test void incomeReplayAddsOneEventAndJournalFailureRollsBackEntryRevisionAndIdempotency()throws Exception{
        var c=browser();long id=initialize(c);String body="{\"entry_type\":\"EXTRA\",\"received_date\":\"2026-11-15\",\"amount\":200000}";
        var first=call(c,"POST","/api/income-entries/","first",body);assertThat(first.statusCode()).as(first.body()).isEqualTo(201);
        long revision=revisions.current(id);var retry=call(c,"POST","/api/income-entries/","first",body);
        assertThat(json(retry)).isEqualTo(json(first));assertThat(changes(id)).hasSize(1);assertThat(revisions.current(id)).isEqualTo(revision);
        RiskHistoryRepository target=org.springframework.test.util.AopTestUtils.getUltimateTargetObject(history);
        doThrow(ApiException.unavailable()).when(target).append(any(Change.class));
        var failed=call(c,"POST","/api/income-entries/","failed",body);assertThat(failed.statusCode()).isEqualTo(503);
        assertThat(revisions.current(id)).isEqualTo(revision);assertThat(changes(id)).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM income_entries WHERE user_id=?",Long.class,id)).isEqualTo(1);
        doCallRealMethod().when(target).append(any(Change.class));
        assertThat(call(c,"POST","/api/income-entries/","failed",body).statusCode()).isEqualTo(201);assertThat(changes(id)).hasSize(2);
    }
}
