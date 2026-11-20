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
@Import(PlanReadApiIntegrationTest.Config.class)
class PlanReadApiIntegrationTest {
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
    @Test void emptyFreeUserGetsExactEnvelopeAndNoInventedProjection()throws Exception {
        var b=browser();var d=list(b,"");assertThat(d.path("plans").size()).isZero();assertThat(d.path("overall_access").asText()).isEqualTo("LOCKED");
        assertThat(d.path("aggregate").path("estimated_payoff_date").isNull()).isTrue();assertThat(d.path("progress").path("progress_percent").isNull()).isTrue();
        assertThat(d.path("data_revision").isTextual()).isTrue();assertThat(get(b,"/api/repayment-plans").statusCode()).isEqualTo(404);
    }
    @Test void realLoanAndEngineAreExposedWithUnknownPerformancePreserved()throws Exception {
        var b=browser();long user=owner(b),id=loan(user,false,true);var d=list(b,"");var p=d.path("plans").get(0);
        assertThat(p.path("loan_id").longValue()).isEqualTo(id);assertThat(p.path("monthly_amount").longValue()).isEqualTo(1260000);
        assertThat(p.path("estimated_total_interest").longValue()).isEqualTo(330000);assertThat(p.path("projected_payoff_date").asText()).isEqualTo("2026-12-25");
        assertThat(p.path("projection_source").asText()).isEqualTo("FALLBACK_PLAN");assertThat(p.path("observed_month_count").intValue()).isZero();
        assertThat(p.path("planned_progress_pct").isNull()).isTrue();assertThat(p.path("actual_progress_pct").isNull()).isTrue();
        assertThat(p.path("track").path("data_status").asText()).isEqualTo("INSUFFICIENT");assertThat(p.path("regular_due_status").asText()).isEqualTo("UPCOMING");
        assertThat(d.path("strategy_code").isNull()).isTrue();assertThat(p.has("user_id")).isFalse();
    }
    @Test void bulletAssetsAndSavingAreSeparateFromInterestAndMonthlyBasicCash()throws Exception {
        var b=browser();long user=owner(b);loan(user,true,true);var p=list(b,"").path("plans").get(0);
        assertThat(p.path("monthly_amount").longValue()).isEqualTo(60000);assertThat(p.path("savings_balance").longValue()).isEqualTo(2000000);
        assertThat(p.path("planned_saving_amount").longValue()).isEqualTo(1000000);assertThat(p.path("required_monthly_saving").longValue()).isEqualTo(1000000);
        assertThat(p.path("estimated_total_interest").longValue()).isEqualTo(600000);
    }
    @Test void foreignMissingAndDeletedSelectionsAreHidden()throws Exception {
        var a=browser();var b=browser();long user=owner(a),id=loan(user,false,true);owner(b);
        assertThat(json(get(b,"/api/repayment-plans/?loan_id="+id))).isEqualTo(json(get(b,"/api/repayment-plans/?loan_id=9223372036854775807")));
        jdbc.update("UPDATE loans SET status='DELETED' WHERE id=?",id);assertThat(get(a,"/api/repayment-plans/?loan_id="+id).statusCode()).isEqualTo(404);
    }
    @Test void selectedGoodLoanDoesNotParseAnotherMalformedLoan()throws Exception {
        var b=browser();long user=owner(b),id=loan(user,false,true),other=loan(user,false,true);
        jdbc.update("UPDATE loans SET initial_plan_snapshot='{}'::jsonb WHERE id=?",other);
        assertThat(list(b,"?loan_id="+id).path("plans").size()).isEqualTo(1);
    }
    @ParameterizedTest @ValueSource(strings={"?loan_id=","?loan_id=0","?loan_id=x","?loan_id=9223372036854775808","?loan_id=1&loan_id=2","?as_of=","?as_of=2026-02-30","?as_of=0000-01-01","?user_id=1"})
    void invalidQueriesUseTheExactValidationEnvelope(String query)throws Exception {var r=get(browser(),"/api/repayment-plans/"+query);assertThat(r.statusCode()).as(r.body()).isEqualTo(400);assertThat(json(r).path("code").asText()).isEqualTo("VALIDATION_ERROR");}
    @Test void unsupportedAsOfDoesNotPretendCurrentTermsAreHistoricalOrFutureFacts()throws Exception {
        for(var date:List.of("2026-02-28","2026-03-02"))assertThat(get(browser(),"/api/repayment-plans/?as_of="+date).statusCode()).isEqualTo(503);
    }
    @Test void explicitZeroOverrideDoesNotRewriteTheLongTermPlanOrClaimCumulativeTrack()throws Exception {
        var b=browser();long user=owner(b),id=loan(user,true,true);
        when(budget.monthlyOverrides(user,YearMonth.of(2026,3))).thenReturn(List.of(new PlannedAmount(id,AllocationType.REPAYMENT_SAVING,0,null)));
        var p=list(b,"").path("plans").get(0);assertThat(p.path("planned_saving_amount").longValue()).isZero();
        assertThat(p.path("projected_payoff_date").asText()).isEqualTo("2026-12-25");assertThat(p.path("saving_gap_amount").isNull()).isTrue();
        assertThat(sources.loan(user,id).initialPlan().monthlySaving()).isEqualTo(1000000);
    }
    @Test void proMultiAccessUsesActualOverallStateAndFreeMasksIt()throws Exception {
        var b=browser();long user=owner(b),id=loan(user,false,true),other=loan(user,false,true);
        when(overall.overallState(user)).thenReturn(new OverallState(true,"PARALLEL","PARALLEL",clock.instant()));
        when(overall.plannedAllocations(user,YearMonth.of(2026,3))).thenReturn(List.of(new PlannedAmount(id,AllocationType.EXTRA_PAYMENT,0,1),new PlannedAmount(other,AllocationType.EXTRA_PAYMENT,0,2)));
        assertThat(list(b,"").path("overall_access").asText()).isEqualTo("LOCKED");
        jdbc.update("UPDATE demo_users SET plan_type='PRO' WHERE id=?",user);var d=list(b,"");assertThat(d.path("overall_access").asText()).isEqualTo("AVAILABLE");assertThat(d.path("plans").get(0).path("priority").intValue()).isEqualTo(1);
    }
    @Test void absentOrBrokenBudgetDoesNotBecomeAnEmptyOverrideSource()throws Exception {
        var b=browser();long user=owner(b);loan(user,false,true);var factory=new StaticListableBeanFactory();
        var service=new PlanReadService(sources,views,factory.getBeanProvider(BudgetQueryPort.class),factory.getBeanProvider(PlanQueryPort.class),revisions,mapper,clock);
        assertThatThrownBy(()->service.list(user,PlanType.FREE,null,null)).isInstanceOf(com.loanrun.common.error.ApiException.class);
        when(budget.monthlyOverrides(anyLong(),any())).thenReturn(null);assertThat(get(b,"/api/repayment-plans/").statusCode()).isEqualTo(503);
        long id=sources.activeLoanIds(user).getFirst();
        when(budget.monthlyOverrides(anyLong(),any())).thenReturn(List.of(new PlannedAmount(id,null,0,null)));assertThat(get(b,"/api/repayment-plans/").statusCode()).isEqualTo(503);
    }
    @Test void unknownFeeAndRepeatedGetPreserveUncertaintyAndAllOriginalSources()throws Exception {
        var b=browser();long user=owner(b),id=loan(user,false,false);var before=sources.loanSnapshot(user,id,LocalDate.of(2026,3,1));var revision=revisions.current(user);
        var d=list(b,"");assertThat(d.path("plans").get(0).path("estimated_total_fee").isNull()).isTrue();assertThat(list(b,"")).isEqualTo(d);
        assertThat(sources.loanSnapshot(user,id,LocalDate.of(2026,3,1))).isEqualTo(before);assertThat(revisions.current(user)).isEqualTo(revision);
    }
    @Test void actualCurrentMonthSavingBeforeTheFirstPlannedDueIsStillDisplayed()throws Exception {
        var b=browser();long user=owner(b),id=loan(user,true,true);
        jdbc.update("INSERT INTO repayment_savings(user_id,loan_id,saved_at,amount,created_at) VALUES (?,?,'2026-03-01',100,CURRENT_TIMESTAMP)",user,id);
        var p=list(b,"").path("plans").get(0);assertThat(p.path("actual_saving_amount").longValue()).isEqualTo(100);
        assertThat(p.path("cumulative_actual_saving_amount").longValue()).isZero();assertThat(p.path("savings_balance").longValue()).isEqualTo(2000100);
    }
    @Test void detailReusesPublicBaseAndPlanWithoutLeakingAnEntity()throws Exception {
        var b=browser();long user=owner(b),id=loan(user,false,true);var r=get(b,"/api/loans/"+id+"/");assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        var d=json(r);assertThat(d.path("code").asText()).isEqualTo("LOAN_DETAIL_SUCCESS");var data=d.path("data");
        assertThat(data.path("loan_id").longValue()).isEqualTo(id);assertThat(data.path("remaining_principal").longValue()).isEqualTo(12000000);
        assertThat(data.path("estimated_remaining_interest").longValue()).isEqualTo(330000);
        assertThat(data.path("plan")).isEqualTo(list(b,"?loan_id="+id).path("plans").get(0));assertThat(data.has("user_id")).isFalse();
        var next=data.path("next_payment");assertThat(next.path("due_item_id").longValue()).isPositive();assertThat(next.path("status").asText()).isEqualTo("UPCOMING");
        assertThat(next.path("required_amount").longValue()).isEqualTo(1260000);assertThat(next.path("total_planned_amount").longValue()).isEqualTo(1260000);
        assertThat(get(b,"/api/loans/preview/").statusCode()).isEqualTo(405);
    }
    @Test void detailVisibilityFiltersBeforeParsingADeletedOrUnrelatedMalformedPlan()throws Exception {
        var b=browser();var other=browser();long user=owner(b),id=loan(user,false,true),broken=loan(user,false,true);owner(other);
        jdbc.update("UPDATE loans SET initial_plan_snapshot='{}'::jsonb WHERE id=?",broken);
        assertThat(get(b,"/api/loans/"+id+"/").statusCode()).isEqualTo(200);
        assertThat(json(get(other,"/api/loans/"+id+"/"))).isEqualTo(json(get(other,"/api/loans/9223372036854775807/")));
        jdbc.update("UPDATE loans SET status='DELETED' WHERE id=?",broken);
        assertThat(get(b,"/api/loans/"+broken+"/").statusCode()).isEqualTo(404);
        assertThat(get(b,"/api/repayment-plans/?loan_id="+broken).statusCode()).isEqualTo(404);
    }
    @Test void detailPaidLoanHasNoNextObligationOrInventedPayoff()throws Exception {
        var b=browser();long user=owner(b),id=loan(user,false,true);jdbc.update("UPDATE loans SET remaining_principal=0,status='PAID' WHERE id=?",id);
        var r=get(b,"/api/loans/"+id+"/");assertThat(r.statusCode()).as(r.body()).isEqualTo(200);var d=json(r).path("data");
        assertThat(d.path("next_payment").isNull()).isTrue();assertThat(d.path("plan").path("monthly_amount").longValue()).isZero();
        assertThat(d.path("estimated_remaining_interest").isNull()).isTrue();
    }
    @Test void detailPartialRegularAllocationsAreNotCountedAsExtraOrRecharged()throws Exception {
        var b=browser();long user=owner(b),id=loan(user,false,true);
        long p=jdbc.queryForObject("INSERT INTO payments(user_id,loan_id,paid_at,payment_type,amount,principal_amount,extra_amount,interest_amount,fee_amount,savings_used_amount,created_at) VALUES (?,?,'2026-03-01','REGULAR',50000,0,0,50000,0,0,CURRENT_TIMESTAMP) RETURNING id",Long.class,user,id);
        jdbc.update("INSERT INTO payment_allocations(user_id,loan_id,payment_id,due_item_id,principal_amount,interest_amount,fee_amount) SELECT ?,?,?,id,0,50000,0 FROM due_items WHERE loan_id=?",user,id,p,id);
        var r=get(b,"/api/loans/"+id+"/");assertThat(r.statusCode()).as(r.body()).isEqualTo(200);var d=json(r).path("data");var next=d.path("next_payment");
        assertThat(next.path("status").asText()).isEqualTo("PARTIAL");assertThat(next.path("paid_required_amount").longValue()).isEqualTo(50000);
        assertThat(next.path("outstanding_amount").longValue()).isEqualTo(1210000);assertThat(next.path("paid_extra_amount").longValue()).isZero();
        assertThat(d.path("estimated_remaining_interest").isNull()).isTrue();
    }
    @Test void detailCurrentSavingAndUnknownFeeStayConsistentWithPlanAndList()throws Exception {
        var b=browser();long user=owner(b),id=loan(user,true,false);
        jdbc.update("INSERT INTO repayment_savings(user_id,loan_id,saved_at,amount,created_at) VALUES (?,?,'2026-03-01',100,CURRENT_TIMESTAMP)",user,id);
        var r=get(b,"/api/loans/"+id+"/");assertThat(r.statusCode()).as(r.body()).isEqualTo(200);var d=json(r).path("data");
        assertThat(d.path("savings_balance").longValue()).isEqualTo(2000100);assertThat(d.path("next_payment").path("actual_saving_amount").longValue()).isEqualTo(100);
        assertThat(d.path("estimated_total_fee").isNull()).isTrue();
    }
    @Test void detailQueriesAndMissingSourcesCannotProduceFakeSuccess()throws Exception {
        var b=browser();long user=owner(b),id=loan(user,false,true);
        assertThat(get(b,"/api/loans/"+id+"/?user_id=1").statusCode()).isEqualTo(400);
        when(budget.monthlyOverrides(anyLong(),any())).thenReturn(null);assertThat(get(b,"/api/loans/"+id+"/").statusCode()).isEqualTo(503);
    }
    @Test void detailRepeatedReadsPreserveSourcesAndRevision()throws Exception {
        var b=browser();long user=owner(b),id=loan(user,false,true);var before=sources.loanSnapshot(user,id,LocalDate.of(2026,3,1));long revision=revisions.current(user);
        assertThat(json(get(b,"/api/loans/"+id+"/"))).isEqualTo(json(get(b,"/api/loans/"+id+"/")));
        assertThat(sources.loanSnapshot(user,id,LocalDate.of(2026,3,1))).isEqualTo(before);assertThat(revisions.current(user)).isEqualTo(revision);
    }
    HttpResponse<String> mutate(HttpClient b,String method,String path,String body)throws Exception {
        return b.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+path)).header("Origin","http://localhost:5173").header("Content-Type","application/json")
            .method(method,HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }
    long variableLoan(long user,String method,Long contractual)throws Exception {
        var body=new LoanPreviewTest().body();body.put("rate_type","VARIABLE");body.put("rate_reset_months",6);body.put("fee_exempt",true);body.put("repayment_method",method);
        if(contractual!=null)body.put("contractual_monthly_payment",contractual);
        long id=registration.create(user,body,null).body().path("data").path("loan_id").longValue();
        jdbc.update("UPDATE loans SET snapshot_date='2026-02-28' WHERE id=?",id);
        jdbc.update("UPDATE loan_balance_snapshots SET effective_date='2026-02-28' WHERE loan_id=?",id);
        jdbc.update("UPDATE loan_rate_histories SET effective_date='2026-02-28' WHERE loan_id=?",id);return id;
    }
    HttpResponse<String> rate(HttpClient b,long id,String body)throws Exception{return mutate(b,"POST","/api/loans/"+id+"/rate-history/",body);}
    @Test void rateWritesOriginalHistoryRepricesOnlyFutureDueAndBumpsOnce()throws Exception {
        var b=browser();long user=owner(b),id=variableLoan(user,"EQUAL_PRINCIPAL",null);var before=sources.loan(user,id);long rev=revisions.current(user);
        clearInvocations(recalc);var r=rate(b,id,"{\"effective_date\":\"2026-03-01\",\"annual_rate\":7}");assertThat(r.statusCode()).as(r.body()).isEqualTo(201);var d=json(r).path("data");
        assertThat(json(r).path("code").asText()).isEqualTo("LOAN_RATE_UPDATE_SUCCESS");assertThat(d.path("rate_record").path("previous_rate").decimalValue()).isEqualByComparingTo("6");
        assertThat(d.path("loan").path("annual_rate").decimalValue()).isEqualByComparingTo("7");assertThat(d.path("loan").path("next_rate_reset_date").asText()).isEqualTo("2026-09-01");
        assertThat(d.path("estimated_monthly_payment").longValue()).isEqualTo(1270000);assertThat(d.path("recalculated_plan").path("estimated_total_interest").longValue()).isEqualTo(385000);
        assertThat(sources.loan(user,id).initialPlan()).isEqualTo(before.initialPlan());assertThat(sources.loan(user,id).latestSnapshotDate()).isEqualTo(before.latestSnapshotDate());
        assertThat(revisions.current(user)).isEqualTo(rev+1);verify(recalc).recalculateFrom(user,YearMonth.of(2026,3));
        assertThat(jdbc.queryForObject("SELECT interest_amount FROM due_items WHERE loan_id=?",Long.class,id)).isEqualTo(70000);
        assertThat(json(get(b,"/api/loans/"+id+"/rate-history/")).path("data").path("rates").size()).isEqualTo(2);
        assertThat(rate(b,id,"{\"effective_date\":\"2026-03-01\",\"annual_rate\":7}").statusCode()).isEqualTo(409);assertThat(revisions.current(user)).isEqualTo(rev+1);
    }
    @Test void ratePreservesContractualNextAmountButDoesNotCopyItIntoLaterCycles()throws Exception {
        var b=browser();long user=owner(b),id=variableLoan(user,"EQUAL_PAYMENT",1300000L);
        var r=rate(b,id,"{\"effective_date\":\"2026-03-01\",\"annual_rate\":0}");assertThat(r.statusCode()).as(r.body()).isEqualTo(201);
        assertThat(json(r).path("data").path("estimated_monthly_payment").longValue()).isEqualTo(1300000);
        assertThat(sources.loan(user,id).baseAnchor()).isEqualTo(1200000);assertThat(sources.loan(user,id).contractualNextAmount()).isEqualTo(1300000);
    }
    @Test void ratePreservesPastPaidRowsAndAllOriginalPayments()throws Exception {
        var b=browser();long user=owner(b),id=variableLoan(user,"EQUAL_PRINCIPAL",null);
        jdbc.update("INSERT INTO due_items(user_id,loan_id,due_date,principal_amount,interest_amount,fee_amount,required_amount,terms_revision,created_at) VALUES (?,?,'2026-02-25',100,10,0,110,1,CURRENT_TIMESTAMP)",user,id);
        var before=jdbc.queryForMap("SELECT * FROM due_items WHERE loan_id=? AND due_date='2026-02-25'",id);
        assertThat(rate(b,id,"{\"effective_date\":\"2026-03-01\",\"annual_rate\":6.25}").statusCode()).isEqualTo(201);
        assertThat(jdbc.queryForMap("SELECT * FROM due_items WHERE loan_id=? AND due_date='2026-02-25'",id)).isEqualTo(before);
    }
    @ParameterizedTest @ValueSource(strings={"{}","{\"effective_date\":null,\"annual_rate\":7}","{\"effective_date\":\"2026-02-30\",\"annual_rate\":7}","{\"effective_date\":\"2026-03-02\",\"annual_rate\":7}","{\"effective_date\":\"2026-03-01\",\"annual_rate\":30}","{\"effective_date\":\"2026-03-01\",\"annual_rate\":6.001}","{\"effective_date\":\"2026-03-01\",\"annual_rate\":\"7\"}","{\"effective_date\":\"2026-03-01\",\"annual_rate\":7,\"user_id\":1}"})
    void invalidRateInputsNeverWrite(String body)throws Exception {
        var b=browser();long user=owner(b),id=variableLoan(user,"EQUAL_PRINCIPAL",null);long rev=revisions.current(user);
        var r=rate(b,id,body);assertThat(r.statusCode()).as(r.body()).isEqualTo(400);assertThat(revisions.current(user)).isEqualTo(rev);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM loan_rate_histories WHERE loan_id=?",Integer.class,id)).isEqualTo(1);
    }
    @Test void rateFixedForeignDeletedAndReversedDatesUseContractErrors()throws Exception {
        var b=browser();var other=browser();long user=owner(b),fixed=loan(user,false,true),id=variableLoan(user,"EQUAL_PRINCIPAL",null);owner(other);
        String input="{\"effective_date\":\"2026-03-01\",\"annual_rate\":7}";
        assertThat(json(rate(b,fixed,input)).path("code").asText()).isEqualTo("RATE_CHANGE_NOT_ALLOWED");
        assertThat(rate(other,id,input).statusCode()).isEqualTo(404);assertThat(rate(b,id,"{\"effective_date\":\"2026-02-28\",\"annual_rate\":7}").statusCode()).isEqualTo(409);
        jdbc.update("UPDATE loans SET status='DELETED' WHERE id=?",id);assertThat(rate(b,id,input).statusCode()).isEqualTo(404);
    }
    @Test void rateBudgetAndResponseFailuresRollBackEverySourceIncludingRevision()throws Exception {
        var b=browser();long user=owner(b),id=variableLoan(user,"EQUAL_PRINCIPAL",null);var before=sources.loanSnapshot(user,id,LocalDate.of(2026,3,1));long rev=revisions.current(user);
        doThrow(com.loanrun.common.error.ApiException.unavailable()).when(recalc).recalculateFrom(user,YearMonth.of(2026,3));
        String input="{\"effective_date\":\"2026-03-01\",\"annual_rate\":7}";assertThat(rate(b,id,input).statusCode()).isEqualTo(503);
        assertThat(sources.loanSnapshot(user,id,LocalDate.of(2026,3,1))).isEqualTo(before);assertThat(revisions.current(user)).isEqualTo(rev);
        doNothing().when(recalc).recalculateFrom(user,YearMonth.of(2026,3));when(budget.monthlyOverrides(user,YearMonth.of(2026,3))).thenReturn(null);
        assertThat(rate(b,id,input).statusCode()).isEqualTo(503);assertThat(sources.loanSnapshot(user,id,LocalDate.of(2026,3,1))).isEqualTo(before);assertThat(revisions.current(user)).isEqualTo(rev);
    }
    @Test void rateConfirmedFutureDueIsNeverOverwritten()throws Exception {
        var b=browser();long user=owner(b),id=variableLoan(user,"EQUAL_PRINCIPAL",null);jdbc.update("UPDATE due_items SET confirmed_unpaid=true WHERE loan_id=?",id);
        var before=sources.loanSnapshot(user,id,LocalDate.of(2026,3,1));assertThat(rate(b,id,"{\"effective_date\":\"2026-03-01\",\"annual_rate\":7}").statusCode()).isEqualTo(409);
        assertThat(sources.loanSnapshot(user,id,LocalDate.of(2026,3,1))).isEqualTo(before);
    }
    HttpResponse<String> patchLoan(HttpClient b,long id,String body)throws Exception{return mutate(b,"PATCH","/api/loans/"+id+"/",body);}
    @Test void correctionAppendsSnapshotAndRepricesFutureWithoutChangingRegistrationOrRates()throws Exception {
        var b=browser();long user=owner(b),id=variableLoan(user,"EQUAL_PRINCIPAL",null);var before=sources.loanSnapshot(user,id,LocalDate.of(2026,3,1));long rev=revisions.current(user);clearInvocations(recalc);
        var r=patchLoan(b,id,"{\"remaining_principal\":10000000}");assertThat(r.statusCode()).as(r.body()).isEqualTo(200);var d=json(r).path("data");
        assertThat(json(r).path("code").asText()).isEqualTo("LOAN_UPDATE_SUCCESS");assertThat(d.path("remaining_principal").longValue()).isEqualTo(10000000);
        assertThat(d.path("snapshot_date").asText()).isEqualTo("2026-03-01");assertThat(d.path("snapshot_principal").longValue()).isEqualTo(10000000);
        assertThat(d.path("estimated_monthly_payment").longValue()).isEqualTo(1050000);assertThat(d.has("plan")).isFalse();
        var after=sources.loanSnapshot(user,id,LocalDate.of(2026,3,1));assertThat(after.balances()).hasSize(2);assertThat(after.balances().getFirst()).isEqualTo(before.balances().getFirst());
        assertThat(after.balances().getLast().reason()).isEqualTo("CORRECTION");assertThat(after.rates()).isEqualTo(before.rates());assertThat(after.loans().getFirst().initialPlan()).isEqualTo(before.loans().getFirst().initialPlan());
        assertThat(revisions.current(user)).isEqualTo(rev+1);verify(recalc).recalculateFrom(user,YearMonth.of(2026,3));
    }
    @Test void correctionToZeroCompletesLoanAndRemovesOnlyUnconfirmedFutureSchedule()throws Exception {
        var b=browser();long user=owner(b),id=loan(user,false,true);var r=patchLoan(b,id,"{\"remaining_principal\":0}");assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        assertThat(json(r).path("data").path("status").asText()).isEqualTo("PAID");assertThat(jdbc.queryForObject("SELECT count(*) FROM due_items WHERE loan_id=?",Integer.class,id)).isZero();
        assertThat(sources.loan(user,id).latestSnapshotPrincipal()).isZero();
    }
    @Test void contractPatchMovesUnconfirmedDueAndPreservesOmittedFieldsAndExplicitNull()throws Exception {
        var b=browser();long user=owner(b),id=variableLoan(user,"EQUAL_PRINCIPAL",1300000L);long due=jdbc.queryForObject("SELECT id FROM due_items WHERE loan_id=?",Long.class,id);
        var r=patchLoan(b,id,"{\"payment_day\":31,\"contractual_monthly_payment\":null,\"next_rate_reset_date\":null}");assertThat(r.statusCode()).as(r.body()).isEqualTo(200);var d=json(r).path("data");
        assertThat(d.path("payment_day").intValue()).isEqualTo(31);assertThat(d.path("annual_rate").decimalValue()).isEqualByComparingTo("6");assertThat(d.path("contractual_monthly_payment").isNull()).isTrue();
        assertThat(jdbc.queryForObject("SELECT due_date FROM due_items WHERE id=?",LocalDate.class,due)).isEqualTo(LocalDate.of(2026,3,31));
        assertThat(sources.loan(user,id).latestSnapshotDate()).isEqualTo(LocalDate.of(2026,2,28));assertThat(jdbc.queryForObject("SELECT count(*) FROM loan_balance_snapshots WHERE loan_id=?",Integer.class,id)).isEqualTo(1);
    }
    @Test void metadataAndIdenticalPatchesDoNotRecalculateBudgetOrAppendSnapshots()throws Exception {
        var b=browser();long user=owner(b),id=loan(user,false,true);long rev=revisions.current(user);clearInvocations(recalc);
        assertThat(patchLoan(b,id,"{}").statusCode()).isEqualTo(200);assertThat(patchLoan(b,id,"{\"annual_rate\":6}").statusCode()).isEqualTo(400);
        assertThat(patchLoan(b,id,"{\"remaining_principal\":12000000,\"fee_exempt\":true}").statusCode()).isEqualTo(200);assertThat(revisions.current(user)).isEqualTo(rev);
        var r=patchLoan(b,id,"{\"product_name\":\"가상 정정 상품\"}");assertThat(r.statusCode()).as(r.body()).isEqualTo(200);assertThat(json(r).path("data").path("product_name").asText()).isEqualTo("가상 정정 상품");
        assertThat(revisions.current(user)).isEqualTo(rev+1);verifyNoInteractions(recalc);assertThat(jdbc.queryForObject("SELECT count(*) FROM loan_balance_snapshots WHERE loan_id=?",Integer.class,id)).isEqualTo(1);
    }
    @ParameterizedTest @ValueSource(strings={"{\"remaining_principal\":null}","{\"remaining_principal\":12000001}","{\"remaining_principal\":-1}","{\"payment_day\":32}","{\"maturity_date\":\"2026-02-30\"}","{\"product_name\":null}","{\"loan_id\":1}","{\"repayment_method\":\"BULLET\"}","{\"user_id\":1}","{\"prepayment_policy\":null}"})
    void invalidLoanPatchesPreserveAllSources(String input)throws Exception {
        var b=browser();long user=owner(b),id=loan(user,false,true);var before=sources.loanSnapshot(user,id,LocalDate.of(2026,3,1));long rev=revisions.current(user);
        var r=patchLoan(b,id,input);assertThat(r.statusCode()).as(r.body()).isEqualTo(400);assertThat(sources.loanSnapshot(user,id,LocalDate.of(2026,3,1))).isEqualTo(before);assertThat(revisions.current(user)).isEqualTo(rev);
    }
    @Test void contractPatchConfirmedDueAndHistoricalPrincipalConflictsUse409()throws Exception {
        var b=browser();long user=owner(b),id=loan(user,false,true);
        assertThat(json(patchLoan(b,id,"{\"original_principal\":11000000,\"remaining_principal\":10000000}")).path("code").asText()).isEqualTo("LOAN_UPDATE_CONFLICT");
        jdbc.update("UPDATE due_items SET confirmed_unpaid=true WHERE loan_id=?",id);var before=sources.loanSnapshot(user,id,LocalDate.of(2026,3,1));
        assertThat(patchLoan(b,id,"{\"remaining_principal\":0}").statusCode()).isEqualTo(409);assertThat(sources.loanSnapshot(user,id,LocalDate.of(2026,3,1))).isEqualTo(before);
    }
    @Test void patchForeignDeletedAndCatalogMetadataAreProtected()throws Exception {
        var b=browser();var other=browser();long user=owner(b),id=loan(user,false,true);owner(other);
        assertThat(patchLoan(other,id,"{}").statusCode()).isEqualTo(404);jdbc.update("UPDATE loans SET product_id=123 WHERE id=?",id);
        assertThat(patchLoan(b,id,"{\"product_name\":\"임의 상품\"}").statusCode()).isEqualTo(400);jdbc.update("UPDATE loans SET status='DELETED' WHERE id=?",id);assertThat(patchLoan(b,id,"{}").statusCode()).isEqualTo(404);
    }
    @Test void correctionBudgetFailureRollsBackSnapshotContractDueAndRevision()throws Exception {
        var b=browser();long user=owner(b),id=loan(user,false,true);var before=sources.loanSnapshot(user,id,LocalDate.of(2026,3,1));long rev=revisions.current(user);
        doThrow(com.loanrun.common.error.ApiException.unavailable()).when(recalc).recalculateFrom(user,YearMonth.of(2026,3));
        assertThat(patchLoan(b,id,"{\"remaining_principal\":10000000}").statusCode()).isEqualTo(503);assertThat(sources.loanSnapshot(user,id,LocalDate.of(2026,3,1))).isEqualTo(before);assertThat(revisions.current(user)).isEqualTo(rev);
    }
}
