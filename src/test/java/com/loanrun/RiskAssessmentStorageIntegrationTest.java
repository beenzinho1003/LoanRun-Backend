package com.loanrun;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.*;
import com.loanrun.common.contract.*;
import com.loanrun.common.contract.FinanceModels.*;
import com.loanrun.common.error.ApiException;
import com.loanrun.risk.domain.*;
import com.loanrun.risk.domain.RiskHistoryModels.*;
import com.loanrun.risk.dto.RiskDtos.Detail;
import com.loanrun.risk.repository.RiskHistoryRepository;
import com.loanrun.risk.service.*;
import java.math.BigDecimal;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@Testcontainers
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"loanrun.demo-mode=true","server.servlet.session.cookie.secure=false","loanrun.risk-history.enabled=true"})
@Import(BootstrapIntegrationTest.TestConfig.class)
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class RiskAssessmentStorageIntegrationTest {
    @Container static final PostgreSQLContainer<?> DB=new PostgreSQLContainer<>("postgres:16.16-bookworm");
    @DynamicPropertySource static void database(DynamicPropertyRegistry r){r.add("spring.datasource.url",DB::getJdbcUrl);r.add("spring.datasource.username",DB::getUsername);r.add("spring.datasource.password",DB::getPassword);}
    @LocalServerPort int port;@Autowired ObjectMapper json;@Autowired JdbcTemplate jdbc;@Autowired RevisionPort revisions;@Autowired TransactionTemplate tx;
    @Autowired RiskAssessmentStore store;@Autowired RiskHistorySourceAdapter source;@Autowired RiskHistoryRepository repository;
    @MockitoBean RiskInputService inputs;@MockitoBean LoanEventQueryPort rates;@MockitoBean LoanQueryPort loans;
    static final YearMonth MONTH=YearMonth.of(2026,10);
    @BeforeEach void setup()throws Exception{
        if(jdbc.queryForObject("SELECT to_regclass('finance_change_events')",String.class)==null)jdbc.execute(Files.readString(Path.of("docs/sql/risk_history_proposal.sql")));
        when(rates.rateChanges(anyLong(),any())).thenReturn(List.of());
        when(loans.terms(anyLong(),anyLong(),any())).thenThrow(new ApiException(org.springframework.http.HttpStatus.NOT_FOUND,"LOAN_NOT_FOUND","대출을 찾을 수 없습니다."));
    }
    CompletedRiskInput captured(long id,long revision,YearMonth month,boolean confirmed){
        var finance=new FinanceSnapshot(month,IncomeType.REGULAR,0L,0L,0L,0L,0L,0L,0L,0L,0L,confirmed,"INPUT");
        var capacity=new BudgetCapacity(month,0L,0L,0,0L,0,0,0L,0,0L,"INPUT",confirmed?"SETTLED":"OPEN",List.of());
        var facts=new RiskInputs(id,month.atEndOfMonth(),month,List.of(),List.of(),finance,capacity,new DueSummary(month,0,0,0,0,0,null,0,0,0,0,0),0L,0L,null);
        var scenario=new RiskScenarioInput(facts,new PaymentSummary(month,0,0,0,0,0,0,0,confirmed,0,0,Map.of()),new SavingSummary(month,0,0,0,0,confirmed,0,Map.of()));
        return new CompletedRiskInput(revision,scenario,List.of(7L),new RiskDetailHistorySource.Recorded(Set.of(),List.of()));
    }
    Detail detail(String score)throws Exception{return json.readValue("{\"level\":null,\"score\":"+score+",\"data_status\":\"INSUFFICIENT\",\"upcoming_events\":[],\"history\":[],\"checks\":[],\"six_month_changes\":[],\"strategy_link_available\":true}",Detail.class);}
    HttpClient browser(){return HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();}
    HttpResponse<String> call(HttpClient client,String method,String path,String body)throws Exception{return client.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+path)).header("Origin","http://localhost:5173").header("Content-Type","application/json")
        .method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());}
    long initialize(HttpClient client,boolean pro)throws Exception{long id=json.readTree(call(client,"GET","/api/users/me/",null).body()).path("data").path("user_id").longValue();
        if(pro)call(client,"PATCH","/api/settings/subscription/","{\"plan_type\":\"PRO\"}");return id;}
    @Test void savedFrozenMonthIsExactAfterLiveSourcesFailAndFreeCannotReadIt()throws Exception{
        var client=browser();long id=initialize(client,true),revision=revisions.current(id);var detail=detail("null");
        assertThat(store.save(captured(id,revision,MONTH,true),detail,Map.of(7L,detail))).isTrue();
        when(inputs.snapshot(anyLong(),any())).thenThrow(ApiException.unavailable());
        var response=call(client,"GET","/api/risks/?month=2026-10&loan_id=7",null);assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(json.readTree(response.body()).path("data")).isEqualTo(json.valueToTree(detail));verifyNoInteractions(inputs,loans,rates);
        call(client,"PATCH","/api/settings/subscription/","{\"plan_type\":\"FREE\"}");assertThat(call(client,"GET","/api/risks/?month=2026-10",null).statusCode()).isEqualTo(403);
    }
    @Test void selectionIsBoundToStoredOwnerAndMissingAssessmentIsNotFabricated()throws Exception{
        var client=browser();long id=initialize(client,true);var value=detail("null");store.save(captured(id,revisions.current(id),MONTH,true),value,Map.of(7L,value));
        assertThat(call(client,"GET","/api/risks/?month=2026-10&loan_id=999",null).statusCode()).isEqualTo(404);
        var other=browser();initialize(other,true);assertThat(call(other,"GET","/api/risks/?month=2026-10&loan_id=7",null).statusCode()).isEqualTo(404);
        assertThat(call(other,"GET","/api/risks/?month=2026-10",null).statusCode()).isEqualTo(503);
    }
    @Test void changedRevisionRejectsLateResultWithoutWritingOrBumpingRevision()throws Exception{
        var client=browser();long id=initialize(client,true),old=revisions.current(id);var captured=captured(id,old,MONTH,true);var value=detail("null");
        long revision=tx.execute(s->revisions.bump(id,"TEST_CHANGE"));assertThat(store.save(captured,value,Map.of(7L,value))).isFalse();
        assertThat(repository.latest(id,MONTH)).isEmpty();assertThat(revisions.current(id)).isEqualTo(revision);
    }
    @Test void confirmedCorrectionAppendsVersionAndIdenticalRetryRetainsOriginal()throws Exception{
        var client=browser();long id=initialize(client,true);var value=detail("null");var old=captured(id,revisions.current(id),MONTH,true);
        assertThat(store.save(old,value,Map.of(7L,value))).isTrue();assertThat(store.save(old,value,Map.of(7L,value))).isTrue();
        long next=tx.execute(s->revisions.bump(id,"TEST_CORRECTION"));var corrected=detail("0");store.save(captured(id,next,MONTH,true),corrected,Map.of(7L,corrected));
        assertThat(source.frozen(id,null,MONTH).score()).isEqualByComparingTo("0");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM risk_assessment_versions WHERE user_id=?",Long.class,id)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT household_payload->>'score' FROM risk_assessment_versions WHERE user_id=? AND data_revision=?",String.class,id,old.revision())).isNull();
    }
    @Test void unconfirmedOrCurrentMonthAndIncompleteSelectorAreRejected()throws Exception{
        var client=browser();long id=initialize(client,true),revision=revisions.current(id);var value=detail("null");
        assertThatThrownBy(()->store.save(captured(id,revision,MONTH,false),value,Map.of(7L,value))).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->store.save(captured(id,revision,MONTH.plusMonths(1),true),value,Map.of(7L,value))).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->store.save(captured(id,revision,MONTH,true),value,Map.of())).isInstanceOf(ApiException.class);
        assertThat(repository.latest(id,MONTH)).isEmpty();
    }
    @Test void actualRateEventsKeepUnknownImpactAndFinanceIncomeUsesOppositeBurdenSign()throws Exception{
        var client=browser();long id=initialize(client,true),revision=revisions.current(id);Instant now=Instant.parse("2026-10-31T03:00:00Z");
        tx.executeWithoutResult(s->{repository.append(new Change(id,revision,Origin.PROFILE,MONTH,MONTH.atEndOfMonth(),Kind.INCOME,1000000L,1200000L,true,now));
            repository.append(new Change(id,revision,Origin.PROFILE,MONTH,MONTH.atEndOfMonth(),Kind.EXPENSE,500000L,550000L,true,now));});
        when(rates.rateChanges(eq(id),any())).thenReturn(List.of(new LoanEventQueryPort.Event(7,MONTH.atDay(1),BigDecimal.ONE,null)));
        var result=source.changes(id,MONTH.plusMonths(1));assertThat(result.events()).hasSize(3);
        assertThat(result.events().stream().filter(e->e.reason()==RiskDetailHistorySource.Reason.INCOME).findFirst().orElseThrow().burdenDelta()).isEqualTo(-200000);
        assertThat(result.events().stream().filter(e->e.reason()==RiskDetailHistorySource.Reason.RATE).findFirst().orElseThrow().burdenDelta()).isNull();
        assertThat(result.events()).allMatch(e->e.recordedLevel()==null); // No past score invented from today's assessment.
    }
}
