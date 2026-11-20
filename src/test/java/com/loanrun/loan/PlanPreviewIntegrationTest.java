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
@Import(PlanPreviewIntegrationTest.Config.class)
class PlanPreviewIntegrationTest {
    @Container static final PostgreSQLContainer<?> DB=new PostgreSQLContainer<>("postgres:16.16-bookworm");
    @DynamicPropertySource static void db(DynamicPropertyRegistry r){r.add("spring.datasource.url",DB::getJdbcUrl);r.add("spring.datasource.username",DB::getUsername);r.add("spring.datasource.password",DB::getPassword);}
    @TestConfiguration static class Config {@Bean @Primary Clock testClock(){return Clock.fixed(Instant.parse("2026-03-01T00:00:00Z"),ZoneId.of("Asia/Seoul"));}}
    @MockitoBean BudgetRecalculationPort recalc;
    @MockitoBean com.loanrun.budget.service.BudgetReadService budget;
    @MockitoBean PlanQueryPort overall;
    @MockitoBean StrategyQueryPort candidates;
    @Autowired JdbcTemplate jdbc;@Autowired ObjectMapper mapper;@Autowired LoanRegistrationService registration;
    @Autowired LoanSourceRepository sources;@Autowired PlanViewQueryService views;@Autowired RevisionPort revisions;@Autowired Clock clock;
    @LocalServerPort int port;
    @BeforeEach void setup()throws Exception {
        if(jdbc.queryForObject("SELECT to_regclass('repayment_plan_versions')",String.class)==null)jdbc.execute(Files.readString(Path.of("docs/sql/repayment_plan_versions_proposal.sql")));
        when(overall.overallState(anyLong())).thenReturn(new OverallState(false,"NONE","NONE",null));
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
    @Autowired RepaymentEngine engine;@Autowired com.loanrun.loan.service.CurrentLoanTermsQueryService current;
    HttpResponse<String> preview(HttpClient b,String body)throws Exception {return b.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/repayment-plans/preview/")).header("Origin","http://localhost:5173").header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());}
    JsonNode ok(HttpClient b,String body)throws Exception {var r=preview(b,body);assertThat(r.statusCode()).as(r.body()).isEqualTo(200);assertThat(json(r).path("code").asText()).isEqualTo("REPAYMENT_PLAN_PREVIEW_SUCCESS");return json(r).path("data");}
    String direct(long id,String fields){return "{\"plans\":[{\"loan_id\":"+id+","+fields+"}]}";}
    @Test void extraPreviewUsesOneEngineSameCurrentBasisAndNeverWrites()throws Exception {
        var b=browser();long user=owner(b),id=loan(user,false,true);var before=sources.loanSnapshot(user,id,LocalDate.of(2026,3,1));long revision=revisions.current(user);clearInvocations(recalc);
        var d=ok(b,direct(id,"\"mode\":\"EXTRA_AMOUNT\",\"extra_monthly_payment\":400000"));var p=d.path("proposed_plans").get(0);
        assertThat(p.path("estimated_payoff_date").asText()).isEqualTo("2026-10-25");assertThat(p.path("estimated_total_interest").longValue()).isEqualTo(256000);
        assertThat(d.path("current_plans").get(0).path("estimated_total_interest").longValue()).isEqualTo(330000);assertThat(d.path("delta").path("interest_saving").longValue()).isEqualTo(74000);
        assertThat(d.path("delta").path("payoff_months_saved").longValue()).isEqualTo(2);assertThat(d.path("delta").path("monthly_burden_delta").longValue()).isEqualTo(400000);
        assertThat(d.path("monthly_burden").longValue()).isEqualTo(1660000);assertThat(p.path("plan_start_principal").longValue()).isEqualTo(12000000);
        assertThat(p.path("actual_progress_pct").isNull()).isTrue();assertThat(d.path("proposed_allocations").get(0).path("actual_amount").longValue()).isZero();assertThat(d.path("strategy_code").isNull()).isTrue();
        assertThat(sources.loanSnapshot(user,id,LocalDate.of(2026,3,1))).isEqualTo(before);assertThat(revisions.current(user)).isEqualTo(revision);verifyNoInteractions(recalc);
    }
    @Test void maturityAndMonthlyAmountKeepBasicCashSeparateFromExtra()throws Exception {
        var b=browser();long user=owner(b),id=loan(user,false,true);var m=ok(b,direct(id,"\"mode\":\"MATURITY\""));assertThat(m.path("proposed_plans").get(0).path("target_payoff_date").asText()).isEqualTo("2026-12-25");
        var d=ok(b,direct(id,"\"mode\":\"MONTHLY_AMOUNT\",\"monthly_amount\":1660000"));assertThat(d.path("proposed_allocations").get(0).path("amount").longValue()).isEqualTo(400000);
        var r=preview(b,direct(id,"\"mode\":\"MONTHLY_AMOUNT\",\"monthly_amount\":1259999"));assertThat(r.statusCode()).isEqualTo(400);assertThat(json(r).path("code").asText()).isEqualTo("PAYOFF_NOT_FEASIBLE");
    }
    @Test void targetInversionWarnsOnHouseholdAffordabilityInsteadOfRejectingFinancialShortfall()throws Exception {
        var b=browser();long user=owner(b),id=loan(user,false,true);var d=ok(b,direct(id,"\"mode\":\"TARGET_DATE\",\"target_date\":\"2026-04-25\""));
        assertThat(d.path("proposed_allocations").get(0).path("amount").longValue()).isEqualTo(4800000);assertThat(d.path("proposed_plans").get(0).path("affordability_gap_amount").longValue()).isEqualTo(2800000);
        assertThat(d.path("warnings").toString()).contains("RECURRING_CAP_EXCEEDED");
        assertThat(json(preview(b,direct(id,"\"mode\":\"TARGET_DATE\",\"target_date\":\"2026-03-10\""))).path("code").asText()).isEqualTo("PAYOFF_NOT_FEASIBLE");
    }
    @Test void preferencesUseTheHouseholdBucketOnceIncludingUnchangedLoans()throws Exception {
        var b=browser();long user=owner(b),a=loan(user,false,true),second=loan(user,false,true);
        var single=ok(b,direct(a,"\"mode\":\"PREFERENCE\",\"preference\":\"FAST\""));assertThat(single.path("proposed_allocations").get(0).path("amount").longValue()).isEqualTo(1600000);
        String request="{\"plans\":[{\"loan_id\":"+a+",\"mode\":\"PREFERENCE\",\"preference\":\"FAST\",\"extra_monthly_payment\":800000},{\"loan_id\":"+second+",\"mode\":\"PREFERENCE\",\"preference\":\"FAST\",\"extra_monthly_payment\":800000}]}";
        var d=ok(b,request);long sum=0;for(var p:d.path("proposed_allocations"))sum+=p.path("amount").longValue();assertThat(sum).isEqualTo(1600000);
        assertThat(preview(b,request.replace("800000","1600000")).statusCode()).isEqualTo(400);
    }
    @Test void preferenceRoundingDoesNotRoundDownToFiftyThousand()throws Exception {
        var b=browser();long user=owner(b),id=loan(user,false,true);when(budget.capacity(user,YearMonth.of(2026,3))).thenReturn(new BudgetCapacity(YearMonth.of(2026,3),101L,101L,0,101L,0,0,101L,0,0L,"RECORDED","OPEN",List.of()));
        assertThat(ok(b,direct(id,"\"mode\":\"PREFERENCE\",\"preference\":\"BALANCED\"")).path("proposed_allocations").get(0).path("amount").longValue()).isEqualTo(51);
    }
    @Test void bulletTargetIsSavingReadinessAndDoesNotFabricateEarlyPrincipalRepayment()throws Exception {
        var b=browser();long user=owner(b),id=loan(user,true,true);var d=ok(b,direct(id,"\"mode\":\"TARGET_DATE\",\"target_date\":\"2026-06-25\""));var p=d.path("proposed_plans").get(0);
        assertThat(p.path("required_monthly_saving").longValue()).isEqualTo(2500000);assertThat(p.path("planned_saving_amount").longValue()).isEqualTo(2500000);assertThat(p.path("planned_extra_amount").longValue()).isZero();
        assertThat(p.path("target_payoff_date").asText()).isEqualTo("2026-06-25");assertThat(p.path("projected_payoff_date").asText()).isEqualTo("2026-12-25");assertThat(p.path("estimated_total_interest").longValue()).isEqualTo(600000);
        assertThat(d.path("proposed_allocations").get(0).path("allocation_type").asText()).isEqualTo("REPAYMENT_SAVING");
        var insufficient=ok(b,direct(id,"\"mode\":\"MATURITY\",\"planned_saving_amount\":10"));assertThat(insufficient.path("estimated_payoff_date").isNull()).isTrue();assertThat(insufficient.path("warnings").toString()).contains("BULLET_SAVINGS_SHORTFALL");
    }
    @Test void feeUnknownRemainsGrossAndDoesNotInventPrincipalOrNetSaving()throws Exception {
        var b=browser();long user=owner(b),id=loan(user,false,false);var d=ok(b,direct(id,"\"mode\":\"EXTRA_AMOUNT\",\"extra_monthly_payment\":400000"));
        assertThat(d.path("estimated_total_fee").isNull()).isTrue();assertThat(d.path("proposed_plans").get(0).path("planned_extra_amount").isNull()).isTrue();assertThat(d.path("warnings").toString()).contains("FEE_UNKNOWN");
        assertThat(d.path("delta").path("interest_saving").longValue()).isEqualTo(74000);
    }
    @Test void previewVersionIsDeterministicCanonicalOwnerRevisionAndInputBound()throws Exception {
        var b=browser();long user=owner(b),id=loan(user,false,true);String request=direct(id,"\"mode\":\"EXTRA_AMOUNT\",\"extra_monthly_payment\":400000");var first=ok(b,request);
        String reordered="{\"plans\":[{\"extra_monthly_payment\":400000,\"mode\":\"EXTRA_AMOUNT\",\"loan_id\":"+id+"}]}";assertThat(ok(b,reordered).path("preview_version")).isEqualTo(first.path("preview_version"));
        assertThat(ok(b,request.replace("400000","500000")).path("preview_version")).isNotEqualTo(first.path("preview_version"));
        jdbc.update("UPDATE demo_users SET revision=revision+1 WHERE id=?",user);assertThat(ok(b,request).path("preview_version")).isNotEqualTo(first.path("preview_version"));
    }
    @ParameterizedTest @ValueSource(strings={"{}","{\"plans\":[]}","{\"plans\":null}","{\"plans\":[],\"report_id\":1}","{\"plans\":[],\"user_id\":1}"})
    void invalidRootRequestsUsePlanValidation(String input)throws Exception {var r=preview(browser(),input);assertThat(r.statusCode()).as(r.body()).isEqualTo(400);assertThat(json(r).path("code").asText()).isEqualTo("PLAN_VALIDATION_ERROR");}
    @ParameterizedTest @ValueSource(strings={"\"mode\":\"EXTRA_AMOUNT\",\"extra_monthly_payment\":-1","\"mode\":\"EXTRA_AMOUNT\",\"extra_monthly_payment\":1.5","\"mode\":\"EXTRA_AMOUNT\",\"extra_monthly_payment\":null","\"mode\":\"PREFERENCE\",\"preference\":\"fast\"","\"mode\":\"TARGET_DATE\",\"target_date\":\"2026-02-30\"","\"mode\":\"MATURITY\",\"monthly_amount\":10","\"mode\":\"MATURITY\",\"planned_saving_amount\":10"})
    void invalidDirectPlansUsePlanValidation(String fields)throws Exception {var b=browser();long user=owner(b),id=loan(user,false,true);var r=preview(b,direct(id,fields));assertThat(r.statusCode()).as(r.body()).isEqualTo(400);assertThat(json(r).path("code").asText()).isEqualTo("PLAN_VALIDATION_ERROR");}
    @Test void foreignDeletedPaidAndRepeatedLoansAreRejected()throws Exception {
        var b=browser();var other=browser();long user=owner(b),id=loan(user,false,true);owner(other);String input=direct(id,"\"mode\":\"MATURITY\"");assertThat(preview(other,input).statusCode()).isEqualTo(404);
        var p=mapper.readTree(input);((com.fasterxml.jackson.databind.node.ArrayNode)p.path("plans")).add(p.path("plans").get(0).deepCopy());assertThat(preview(b,p.toString()).statusCode()).isEqualTo(400);
        jdbc.update("UPDATE loans SET status='PAID',remaining_principal=0 WHERE id=?",id);assertThat(preview(b,input).statusCode()).isEqualTo(404);jdbc.update("UPDATE loans SET status='DELETED' WHERE id=?",id);assertThat(preview(b,input).statusCode()).isEqualTo(404);
    }
    @Test void missingBudgetAndMissingVersionTableNeverBecomeFakePlans()throws Exception {
        var b=browser();long user=owner(b),id=loan(user,false,true);String input=direct(id,"\"mode\":\"MATURITY\"");when(budget.capacity(user,YearMonth.of(2026,3))).thenReturn(null);assertThat(preview(b,input).statusCode()).isEqualTo(503);
        when(budget.capacity(user,YearMonth.of(2026,3))).thenReturn(new BudgetCapacity(YearMonth.of(2026,3),2000000L,2000000L,0,2000000L,0,0,2000000L,0,0L,"RECORDED","OPEN",List.of()));
        jdbc.execute("ALTER TABLE repayment_plan_versions RENAME TO withheld_plan_versions");
        try {assertThat(preview(b,input).statusCode()).isEqualTo(503);}finally{jdbc.execute("ALTER TABLE withheld_plan_versions RENAME TO repayment_plan_versions");}
    }
    @Test void explicitLaterEffectiveDateNeedsFutureBasisAndPastDateIsInvalid()throws Exception {
        var b=browser();long user=owner(b),id=loan(user,false,true);String input=direct(id,"\"mode\":\"MATURITY\"");var p=(com.fasterxml.jackson.databind.node.ObjectNode)mapper.readTree(input);p.put("effective_date","2026-04-25");assertThat(preview(b,p.toString()).statusCode()).isEqualTo(503);
        p.put("effective_date","2026-02-28");assertThat(preview(b,p.toString()).statusCode()).isEqualTo(400);
    }
    StrategyCandidate candidate(long user,long report,String code,String version,List<PlannedAmount> amounts,long revision) {
        var terms=sources.activeLoanIds(user).stream().map(id->current.current(user,id).terms()).toList();var months=new HashMap<YearMonth,List<PlannedAmount>>();for(int i=0;i<10;i++)months.put(YearMonth.of(2026,3).plusMonths(i),amounts);
        return new StrategyCandidate(report,version,code,"DEMO",revision,clock.instant(),amounts,engine.simulate(new SimulationInput(terms,LocalDate.of(2026,3,1),1200,months,Map.of(),null,code)),true,List.of());
    }
    @Test void strategyRequiresProCurrentCandidateAndNeverPersistsIt()throws Exception {
        var b=browser();long user=owner(b),id=loan(user,false,true);String request="{\"report_id\":123,\"strategy_code\":\"FAST\",\"strategy_version\":\"candidate-v1\"}";
        assertThat(preview(b,request).statusCode()).isEqualTo(403);jdbc.update("UPDATE demo_users SET plan_type='PRO' WHERE id=?",user);long rev=revisions.current(user);
        var c=candidate(user,123,"FAST","candidate-v1",List.of(new PlannedAmount(id,AllocationType.EXTRA_PAYMENT,400000,null)),rev);when(candidates.requireCurrentCandidate(user,123,"candidate-v1","FAST",rev)).thenReturn(c);
        var before=sources.loanSnapshot(user,id,LocalDate.of(2026,3,1));var d=ok(b,request);assertThat(d.path("strategy_code").asText()).isEqualTo("FAST");assertThat(d.path("strategy_version").asText()).isEqualTo("candidate-v1");assertThat(d.path("proposed_plans").get(0).path("mode").asText()).isEqualTo("STRATEGY");
        assertThat(sources.loanSnapshot(user,id,LocalDate.of(2026,3,1))).isEqualTo(before);assertThat(revisions.current(user)).isEqualTo(rev);
        when(candidates.requireCurrentCandidate(user,123,"candidate-v1","FAST",rev)).thenReturn(candidate(user,123,"FAST","candidate-v1",c.allocations(),rev-1));assertThat(preview(b,request).statusCode()).isEqualTo(409);
    }
    @Test void strategyAllocationsKeepPrioritiesAndRejectOverCapacity()throws Exception {
        var b=browser();long user=owner(b),id=loan(user,false,true),second=loan(user,false,true);jdbc.update("UPDATE demo_users SET plan_type='PRO' WHERE id=?",user);long rev=revisions.current(user);
        String request="{\"report_id\":123,\"strategy_code\":\"INTEREST_SAVING\",\"strategy_version\":\"candidate-v1\"}";var amounts=List.of(new PlannedAmount(id,AllocationType.EXTRA_PAYMENT,200000,1),new PlannedAmount(second,AllocationType.EXTRA_PAYMENT,0,2));
        when(candidates.requireCurrentCandidate(user,123,"candidate-v1","INTEREST_SAVING",rev)).thenReturn(candidate(user,123,"INTEREST_SAVING","candidate-v1",amounts,rev));var d=ok(b,request);assertThat(d.path("proposed_allocations").get(0).path("priority").intValue()).isEqualTo(1);
        when(candidates.requireCurrentCandidate(user,123,"candidate-v1","INTEREST_SAVING",rev)).thenReturn(candidate(user,123,"INTEREST_SAVING","candidate-v1",List.of(new PlannedAmount(id,AllocationType.EXTRA_PAYMENT,3000000,1)),rev));assertThat(preview(b,request).statusCode()).isEqualTo(400);
    }
    @Test void newLongTermGoalPreservesCurrentMonthOverrideAndRequestedTargetNull()throws Exception {
        var b=browser();long user=owner(b),id=loan(user,false,true);when(budget.monthlyOverrides(user,YearMonth.of(2026,3))).thenReturn(List.of(new PlannedAmount(id,AllocationType.EXTRA_PAYMENT,0,null)));
        var d=ok(b,direct(id,"\"mode\":\"EXTRA_AMOUNT\",\"extra_monthly_payment\":400000"));var p=d.path("proposed_plans").get(0);
        assertThat(p.path("extra_monthly_payment").longValue()).isEqualTo(400000);assertThat(p.path("planned_extra_amount").longValue()).isZero();assertThat(p.path("target_date").isNull()).isTrue();
        assertThat(p.path("target_payoff_date").asText()).isEqualTo("2026-10-25");assertThat(d.path("proposed_allocations").get(0).path("amount").longValue()).isEqualTo(400000);
    }
    @Test void positiveSavedGoalWithMissingOriginalBasisRemainsNullInsteadOfUnboxing()throws Exception {
        var b=browser();long user=owner(b),id=loan(user,false,true);jdbc.update("UPDATE loans SET initial_plan_snapshot=jsonb_set(initial_plan_snapshot,'{monthly_extra_cash}','100000'::jsonb) WHERE id=?",id);
        var d=ok(b,direct(id,"\"mode\":\"MATURITY\""));assertThat(d.path("current_plans").get(0).path("planned_extra_amount").isNull()).isTrue();
    }
    @Test void overduePreviousMonthDetailKeepsUnknownMonthTargetsNull()throws Exception {
        var b=browser();long user=owner(b),id=loan(user,false,true);
        jdbc.update("UPDATE loans SET created_at='2026-02-01T00:00:00Z',snapshot_date='2026-02-01',next_due_date='2026-02-25',initial_plan_snapshot=jsonb_set(initial_plan_snapshot,'{effective_date}','\"2026-02-25\"'::jsonb) WHERE id=?",id);
        jdbc.update("UPDATE due_items SET due_date='2026-02-25' WHERE loan_id=?",id);var r=get(b,"/api/loans/"+id+"/");assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        var n=json(r).path("data").path("next_payment");assertThat(n.path("planned_extra_amount").isNull()).isTrue();assertThat(n.path("planned_saving_amount").longValue()).isZero();assertThat(n.path("total_planned_amount").isNull()).isTrue();
    }
    @Test void graceInstallmentDoesNotAcceptExtraGoalsBeforeGraceEnds()throws Exception {
        var b=browser();long user=owner(b);var body=new LoanPreviewTest().body();body.put("repayment_method","GRACE_INSTALLMENT");body.put("grace_months",18);body.put("post_grace_method","EQUAL_PRINCIPAL");body.put("fee_exempt",true);
        long id=registration.create(user,body,null).body().path("data").path("loan_id").longValue();
        var r=preview(b,direct(id,"\"mode\":\"EXTRA_AMOUNT\",\"extra_monthly_payment\":100000"));assertThat(r.statusCode()).isEqualTo(400);assertThat(json(r).path("code").asText()).isEqualTo("PLAN_VALIDATION_ERROR");
        assertThat(ok(b,direct(id,"\"mode\":\"PREFERENCE\",\"preference\":\"STABLE\"")).path("proposed_allocations").get(0).path("amount").longValue()).isZero();
    }
    @Test void fullyFundedBulletMaturityBurdenUsesAssetsOnceAndDoesNotDoubleCountPrincipal()throws Exception {
        var b=browser();long user=owner(b);var body=new LoanPreviewTest().body();body.put("repayment_method","BULLET");body.putNull("prepayment_policy");body.put("initial_savings_balance",12000000);body.put("maturity_date","2026-03-25");body.put("fee_exempt",true);
        long id=registration.create(user,body,null).body().path("data").path("loan_id").longValue();var d=ok(b,direct(id,"\"mode\":\"MATURITY\""));assertThat(d.path("monthly_burden").longValue()).isEqualTo(60000);
        assertThat(d.path("proposed_plans").get(0).path("required_monthly_saving").longValue()).isZero();assertThat(sources.loan(user,id).storedRemainingPrincipal()).isEqualTo(12000000);
    }
    @Test void fixedFeeIsIncludedInExtraCashAndNeverAddedOnTopOfItsBudget()throws Exception {
        var b=browser();long user=owner(b);var body=new LoanPreviewTest().body();body.put("prepayment_fee_method","FIXED");body.put("prepayment_fee_rate",1);body.put("fee_exempt",false);
        long id=registration.create(user,body,null).body().path("data").path("loan_id").longValue();var d=ok(b,direct(id,"\"mode\":\"EXTRA_AMOUNT\",\"extra_monthly_payment\":400000"));
        assertThat(d.path("proposed_plans").get(0).path("planned_extra_amount").longValue()).isEqualTo(396040);assertThat(d.path("estimated_total_fee").longValue()).isEqualTo(27720);
        assertThat(d.path("monthly_burden").longValue()).isEqualTo(1660000);assertThat(d.path("proposed_allocations").get(0).path("amount").longValue()).isEqualTo(400000);
    }
    @Test void oneUnderfundedBulletDoesNotEraseAnotherLoansKnownPayoff()throws Exception {
        var b=browser();long user=owner(b),installment=loan(user,false,true),bullet=loan(user,true,true);
        var d=ok(b,direct(bullet,"\"mode\":\"MATURITY\",\"planned_saving_amount\":10"));assertThat(d.path("estimated_payoff_date").isNull()).isTrue();
        for(var p:d.path("proposed_plans"))if(p.path("loan_id").longValue()==installment)assertThat(p.path("projected_payoff_date").asText()).isEqualTo("2026-12-25");else assertThat(p.path("projected_payoff_date").isNull()).isTrue();
    }
}
