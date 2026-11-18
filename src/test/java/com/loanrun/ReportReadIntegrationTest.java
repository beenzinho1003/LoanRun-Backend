package com.loanrun;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.loanrun.common.contract.*;
import com.loanrun.common.contract.FinanceModels.*;
import com.loanrun.common.error.ApiException;
import com.loanrun.report.domain.ReportJob;
import com.loanrun.report.repository.ReportJobRepository;
import com.loanrun.report.service.ReportJobStore;
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
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@Testcontainers
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"loanrun.demo-mode=true","server.servlet.session.cookie.secure=false"})
@Import(BootstrapIntegrationTest.TestConfig.class)
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class ReportReadIntegrationTest {
    @Container static final PostgreSQLContainer<?> DB=new PostgreSQLContainer<>("postgres:16.16-bookworm");
    @DynamicPropertySource static void database(DynamicPropertyRegistry r){r.add("spring.datasource.url",DB::getJdbcUrl);r.add("spring.datasource.username",DB::getUsername);r.add("spring.datasource.password",DB::getPassword);}
    @LocalServerPort int port;@Autowired ObjectMapper mapper;@Autowired ReportJobRepository jobs;@Autowired ReportJobStore store;
    @Autowired UserMutationLock locks;@Autowired RevisionPort revisions;@Autowired TransactionTemplate transactions;@Autowired Clock clock;@Autowired JdbcTemplate jdbc;
    @MockitoBean LoanQueryPort loans;final AtomicLong loanOwner=new AtomicLong();final YearMonth month=YearMonth.of(2026,11);
    @BeforeEach void source(){loanOwner.set(0);when(loans.terms(anyLong(),anyLong(),any())).thenAnswer(i->{
        long user=i.getArgument(0),id=i.getArgument(1);if(user!=loanOwner.get()||id!=7)throw new ApiException(org.springframework.http.HttpStatus.NOT_FOUND,"LOAN_NOT_FOUND","대출을 찾을 수 없습니다.");
        return new LoanTerms(7,user,"가상",LoanType.CREDIT,100000,100000,month.atDay(17),BigDecimal.valueOf(6),RateType.FIXED,RepaymentMethod.EQUAL_PAYMENT,null,null,
            month.atDay(25),25,month.plusYears(1).atDay(25),1000L,1000L,null,PrepaymentPolicy.KEEP_PAYMENT,FeeMethod.NONE,BigDecimal.ZERO,null,null,0,0,LoanStatus.ACTIVE,null);});}
    HttpClient browser(){return HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();}
    HttpResponse<String> call(HttpClient c,String method,String path,String body)throws Exception{return c.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+path))
        .header("Origin","http://localhost:5173").header("Content-Type","application/json").method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());}
    JsonNode json(HttpResponse<String> r)throws Exception{return mapper.readTree(r.body());}
    long initialize(HttpClient c,boolean pro)throws Exception{long id=json(call(c,"GET","/api/users/me/",null)).path("data").path("user_id").longValue();if(pro)plan(c,"PRO");return id;}
    void plan(HttpClient c,String type)throws Exception{assertThat(call(c,"PATCH","/api/settings/subscription/","{\"plan_type\":\""+type+"\"}").statusCode()).isEqualTo(200);}
    ReportJob create(long user,Long loan){return transactions.execute(s->{locks.lock(user);return jobs.create(user,loan,revisions.current(user),month,"{}",clock.instant());});}
    void complete(ReportJob job){var payload=mapper.createObjectNode().put("summary","저장된 개인 리포트").put("strategy_available",true).put("strategy_type","MULTI");
        payload.putObject("pace_analysis").put("monthly_principal_repayment_rate",new BigDecimal("2.50"));payload.putObject("comparison").put("source_type","DEMO");
        payload.putArray("payoff_scenarios");payload.putArray("suggestions");payload.putNull("error");
        assertThat(store.complete(store.start(job.id()).orElseThrow(),payload,null,List.of(),"FALLBACK")).isTrue();}
    JsonNode detail(HttpClient c,long id)throws Exception{var r=call(c,"GET","/api/reports/"+id+"/",null);assertThat(r.statusCode()).as(r.body()).isEqualTo(200);return json(r).path("data");}
    @Test void absentReportIsNotGeneratedAndGetNeverCreatesJobsOrAdvancesRevision()throws Exception{
        var c=browser();long user=initialize(c,false);assertThat(call(c,"GET","/api/reports/latest/",null).statusCode()).isEqualTo(403);plan(c,"PRO");long revision=revisions.current(user);
        var r=call(c,"GET","/api/reports/latest/",null);assertThat(r.statusCode()).isEqualTo(200);assertThat(json(r).path("code").asText()).isEqualTo("REPORT_LATEST_SUCCESS");var data=json(r).path("data");
        assertThat(data.path("status").asText()).isEqualTo("NOT_GENERATED");assertThat(data.path("report_id").isNull()).isTrue();assertThat(data.path("report").isNull()).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM report_jobs WHERE user_id=?",Long.class,user)).isZero();assertThat(revisions.current(user)).isEqualTo(revision);
        assertThat(call(c,"GET","/api/reports/999999/",null).statusCode()).isEqualTo(404);
    }
    @Test void pendingRunningAndFailedExposeStateWithoutInventedResultsOrSuccessfulUpdateTime()throws Exception{
        var c=browser();long user=initialize(c,true);var job=create(user,null);long revision=revisions.current(user);
        var data=detail(c,job.id());assertThat(data.path("status").asText()).isEqualTo("PENDING");assertThat(data.path("summary").isNull()).isTrue();assertThat(data.path("updated_at").isNull()).isTrue();
        assertThat(json(call(c,"GET","/api/reports/latest/",null)).path("data").path("report").isNull()).isTrue();
        var lease=store.start(job.id()).orElseThrow();assertThat(detail(c,job.id()).path("status").asText()).isEqualTo("RUNNING");store.fail(lease,"SERVICE_UNAVAILABLE");
        data=detail(c,job.id());assertThat(data.path("status").asText()).isEqualTo("FAILED");assertThat(data.path("error").path("code").asText()).isEqualTo("SERVICE_UNAVAILABLE");
        assertThat(data.path("pace_analysis").isNull()).isTrue();assertThat(data.path("generated_at").isNull()).isTrue();assertThat(revisions.current(user)).isEqualTo(revision);
    }
    @Test void completedReadsRetainEndpointSpecificRateTypesAndDoNotRewriteStoredPayload()throws Exception{
        var c=browser();long user=initialize(c,true);call(c,"PATCH","/api/reports/comparison-settings/","{\"consented\":true,\"age_group\":\"AGE_20S\",\"income_decile\":3}");
        var job=create(user,null);complete(job);String stored=jobs.owned(user,job.id()).resultPayload();long revision=revisions.current(user);
        var data=detail(c,job.id());assertThat(data.path("pace_analysis").path("monthly_principal_repayment_rate").isNumber()).isTrue();assertThat(data.path("comparison").isObject()).isTrue();
        var latest=json(call(c,"GET","/api/reports/latest/",null)).path("data");var rate=latest.path("report").path("pace_analysis").path("monthly_principal_repayment_rate");
        assertThat(rate.isTextual()).isTrue();assertThat(new BigDecimal(rate.asText())).isEqualByComparingTo("2.50");assertThat(latest.path("updated_at").asText()).endsWith("+09:00");
        assertThat(jobs.owned(user,job.id()).resultPayload()).isEqualTo(stored);assertThat(revisions.current(user)).isEqualTo(revision);
    }
    @Test void staleOrWithdrawnDataIsNotCurrentAndProDowngradeBlocksEveryRead()throws Exception{
        var c=browser();long user=initialize(c,true);call(c,"PATCH","/api/reports/comparison-settings/","{\"consented\":true,\"age_group\":\"AGE_20S\",\"income_decile\":3}");
        var job=create(user,null);complete(job);transactions.executeWithoutResult(s->{locks.lock(user);revisions.bump(user,"TEST_FINANCIAL_CHANGE");});
        var latest=json(call(c,"GET","/api/reports/latest/",null)).path("data");assertThat(latest.path("stale").asBoolean()).isTrue();assertThat(latest.path("report").isNull()).isTrue();
        var data=detail(c,job.id());assertThat(data.path("stale").asBoolean()).isTrue();assertThat(data.path("strategy_available").asBoolean()).isFalse();
        call(c,"PATCH","/api/reports/comparison-settings/","{\"consented\":false}");assertThat(detail(c,job.id()).path("comparison").isNull()).isTrue();
        plan(c,"FREE");assertThat(call(c,"GET","/api/reports/latest/",null).statusCode()).isEqualTo(403);assertThat(call(c,"GET","/api/reports/"+job.id()+"/",null).statusCode()).isEqualTo(403);
        plan(c,"PRO");assertThat(detail(c,job.id()).path("report_id").longValue()).isEqualTo(job.id());
    }
    @Test void scopesOwnershipStaticRoutesAndInvalidIdsStaySeparate()throws Exception{
        var a=browser();long owner=initialize(a,true);loanOwner.set(owner);var job=create(owner,7L);complete(job);var b=browser();initialize(b,true);
        assertThat(call(b,"GET","/api/reports/"+job.id()+"/",null).statusCode()).isEqualTo(404);assertThat(call(b,"GET","/api/reports/latest/?loan_id=7",null).statusCode()).isEqualTo(404);
        assertThat(json(call(a,"GET","/api/reports/latest/?loan_id=7",null)).path("data").path("report_id").longValue()).isEqualTo(job.id());
        assertThat(json(call(a,"GET","/api/reports/latest/",null)).path("data").path("status").asText()).isEqualTo("NOT_GENERATED");
        assertThat(call(a,"GET","/api/reports/comparison-settings/",null).statusCode()).isEqualTo(200);
        assertThat(call(a,"GET","/api/reports/latest/?loan_id=0",null).statusCode()).isEqualTo(400);assertThat(call(a,"GET","/api/reports/nope/",null).statusCode()).isEqualTo(400);
        assertThat(call(a,"GET","/api/reports/latest",null).statusCode()).isEqualTo(404);
    }
}
