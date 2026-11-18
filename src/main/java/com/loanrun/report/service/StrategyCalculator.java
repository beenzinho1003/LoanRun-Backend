package com.loanrun.report.service;

import com.loanrun.common.contract.*;
import com.loanrun.common.contract.FinanceModels.*;
import com.loanrun.common.error.ApiException;
import com.loanrun.report.domain.ReportInput;
import com.loanrun.report.dto.StrategyDtos.*;
import com.loanrun.report.service.StrategyRecommendationPolicy.*;
import com.loanrun.risk.domain.RiskScenarioInput;
import com.loanrun.risk.service.RiskMath;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/** Captured facts and pure Team 1 results only. No DB, HTTP self-call, live budget read or AI decision. */
@Service
public class StrategyCalculator {
    public record RateContext(Long afterOnePpAvailable,BigDecimal basicAtRiskFrom){}
    public record Computed(View view,List<StrategyCandidate> stored){public Computed{stored=List.copyOf(stored);}}
    private record Evaluated(Code code,StrategyEngineInputs.Prepared input,SimulationResult result,boolean feasible){}
    private final ObjectProvider<RepaymentEngine> engines;private final Clock clock;
    public StrategyCalculator(ObjectProvider<RepaymentEngine> engines,Clock clock){this.engines=engines;this.clock=clock;}
    public Computed calculate(long reportId,ReportInput input,RateContext rate){
        var original=input.scenarios().household();
        var targeted=input.loanId()==null?original.loans():original.loans().stream().filter(l->l.loanId()==input.loanId()).toList();
        String type=StrategyInputPolicy.type(targeted,original.asOf());
        if(type.equals("NONE"))return unavailable(reportId,input,type,targeted.isEmpty()?"NO_LOANS":"NO_ELIGIBLE_LOANS");
        var scope=StrategyReportScope.select(input.scenarios(),input.loanId(),input.monthlyOverrides());
        if(scope.isEmpty())return unavailable(reportId,input,type,"NO_ELIGIBLE_LOANS");
        var source=scope.get().candidateInput();var facts=source.household();
        List<Code> codes=switch(type){case "SINGLE_PREFERENCE"->List.of(Code.FAST,Code.BALANCED,Code.STABLE);case "SAVINGS_ONLY"->List.of(Code.SAVINGS);
            default->List.of(Code.RATE_RISK,Code.INTEREST_SAVING,Code.PARALLEL);};
        Map<Code,StrategyEngineInputs.Prepared> prepared=new LinkedHashMap<>();
        for(var code:codes)StrategyEngineInputs.candidate(source,code,scope.get().overrides()).ifPresent(value->prepared.put(code,value));
        if(prepared.isEmpty())return unavailable(reportId,input,type,"NO_ELIGIBLE_LOANS");
        var engine=engines.getIfAvailable();if(engine==null)throw ApiException.unavailable();
        var baseline=engine.simulate(StrategyEngineInputs.baseline(source,scope.get().overrides()));
        if(baseline==null||baseline.rows().isEmpty())throw ApiException.unavailable();
        List<Evaluated> evaluated=new ArrayList<>();
        for(var entry:prepared.entrySet()){
            var result=engine.simulate(entry.getValue().simulation());if(result==null||result.rows().isEmpty())throw ApiException.unavailable();
            boolean feasible=entry.getValue().currentFundingAvailable()&&!result.insufficient()&&result.projectedPayoffDate()!=null&&result.totalInterest()!=null;
            evaluated.add(new Evaluated(entry.getKey(),entry.getValue(),result,feasible));
        }
        var comparison=StrategyRecommendationPolicy.compare(baseline.totalInterest(),baseline.totalFee(),evaluated.stream()
            .map(e->new Cost(e.code(),e.result().totalInterest(),e.result().totalFee(),e.feasible())).toList());
        if(evaluated.stream().noneMatch(Evaluated::feasible))return unavailable(reportId,input,type,"NO_ELIGIBLE_LOANS");
        String version="strategy-"+reportId+"-"+input.revision()+"-"+input.basisMonth();
        List<Candidate> candidates=new ArrayList<>();List<StrategyCandidate> stored=new ArrayList<>();
        for(int i=0;i<evaluated.size();i++){
            var e=evaluated.get(i);var cost=comparison.savings().get(i);var goals=e.input().goals();
            var allocations=goals.stream().map(a->new Allocation(a.loanId(),a.cashAmount(),a.priority(),a.allocationType(),
                (a.allocationType()==AllocationType.REPAYMENT_SAVING?source.savings().additionsByLoan():source.payments().extraCashByLoan()).getOrDefault(a.loanId(),0L))).toList();
            String caveat=e.feasible()?"예상 결과는 현재 입력한 조건을 기준으로 합니다.":"당월 자금과 상환 조건을 확인한 뒤 적용할 수 있습니다.";
            candidates.add(new Candidate(e.code().name(),label(e.code()),List.of(reason(e.code())),List.of(caveat),e.result().projectedPayoffDate(),e.result().totalInterest(),cost.interestSaving(),
                monthlyCash(e.result(),facts.loans(),facts.asOf()),allocations,reason(e.code()),e.result().totalFee(),cost.netSaving(),comparison.basis().name()));
            List<String> warnings=new ArrayList<>(e.result().warnings());if(!e.input().currentFundingAvailable())warnings.add("CURRENT_ALLOCATION_UNFUNDED");
            var projection=e.result();var storedProjection=new SimulationResult(projection.rows(),projection.projectedPayoffDate(),projection.totalPrincipal(),projection.totalInterest(),projection.totalFee(),
                projection.totalCash(),projection.insufficient(),comparison.basis().name(),warnings);
            stored.add(new StrategyCandidate(reportId,version,e.code().name(),type,input.revision(),clock.instant(),goals,storedProjection,e.feasible(),warnings));
        }
        String currentCode=currentCode(input,source);Code recommendation;
        var finance=facts.finance();if(finance==null)throw ApiException.unavailable();
        if(type.equals("SINGLE_PREFERENCE"))recommendation=StrategyRecommendationPolicy.single(facts.capacity().availableCash(),finance.planningIncome(),finance.actualDiscretionaryExpenses(),finance.discretionaryLimit());
        else if(type.equals("SAVINGS_ONLY"))recommendation=Code.SAVINGS;
        else recommendation=StrategyRecommendationPolicy.multi(comparison,currentCode,facts.variableExposurePct(),rate.afterOnePpAvailable()!=null&&original.monthlyTarget()!=null&&rate.afterOnePpAvailable()<original.monthlyTarget(),
            StrategyInputPolicy.directTargets(facts.loans(),facts.asOf()).stream().anyMatch(l->l.rateType()==RateType.VARIABLE),true);
        if(recommendation!=null){String selected=recommendation.name();if(stored.stream().noneMatch(c->c.code().equals(selected)&&c.feasible()))recommendation=null;}
        boolean household=input.loanId()==null;var performance=input.performance();
        BigDecimal adherence=household&&performance!=null?performance.adherenceRate3m():null;
        var appliedAt=household&&facts.loans().size()>1&&input.overall()!=null&&input.overall().appliedAt()!=null?input.overall().appliedAt().atZone(clock.getZone()).toOffsetDateTime():null;
        var current=new Current(currentCode,appliedAt,adherence,StrategyRecommendationPolicy.judgement(currentCode,recommendation,adherence));
        Long configured=null;Set<Long> eligible=new HashSet<>();StrategyInputPolicy.directTargets(facts.loans(),facts.asOf()).forEach(l->eligible.add(l.loanId()));
        facts.loans().stream().filter(l->l.method()==RepaymentMethod.BULLET).forEach(l->eligible.add(l.loanId()));
        for(var p:facts.plans())if(p.configured()&&eligible.contains(p.loanId()))configured=Math.addExact(configured==null?0:configured,Math.addExact(p.monthlyExtraCash(),p.monthlySaving()));
        long required=prepared.values().iterator().next().budget().requiredSaving();var budget=StrategyInputPolicy.budget(facts.capacity(),configured,required,50);
        var situation=new Situation(budget.recurringCash(),baseline.projectedPayoffDate(),baseline.totalInterest(),RiskMath.display(RiskMath.ratio(finance.actualDiscretionaryExpenses(),finance.discretionaryLimit())),
            RiskMath.display(RiskMath.ratio(facts.capacity().availableCash(),finance.planningIncome())),household&&performance!=null?performance.consecutiveExtraOnTrackMonths():null,
            household&&performance!=null?performance.missedPaymentCount6m():null,RiskMath.display(facts.variableExposurePct()),rate.basicAtRiskFrom(),baseline.totalFee(),required,facts.capacity().recurringCap());
        var recommended=recommendation==null?null:new Recommendation(recommendation.name(),recommendation.name().equals(currentCode)?"KEEP":"NEW",reason(recommendation));
        return new Computed(new View(true,null,reportId,version,current,situation,recommended,candidates,false,type,revision(input),comparison.basis().name()),stored);
    }
    private String currentCode(ReportInput input,RiskScenarioInput source){
        var facts=source.household();
        if(facts.loans().size()==1){var plan=facts.plans().stream().filter(PlanSnapshot::configured).findFirst();if(plan.isEmpty())return "NONE";
            if(facts.loans().getFirst().method()==RepaymentMethod.BULLET)return "SAVINGS";
            return List.of("FAST","BALANCED","STABLE").contains(Objects.toString(plan.get().preference(),""))?plan.get().preference():"MANUAL";}
        return input.overall()==null||input.overall().strategyCode()==null?"NONE":input.overall().strategyCode();
    }
    /** Aggregate the first projected month's cash; reused saving is not new cash and extraCash already includes fees. */
    private long monthlyCash(SimulationResult result,List<LoanTerms> terms,LocalDate asOf){
        if(result.rows().isEmpty())throw ApiException.unavailable();var first=result.rows().stream().map(SimulationRow::date).min(LocalDate::compareTo).orElseThrow();
        if(first.isBefore(asOf))throw ApiException.unavailable();var month=YearMonth.from(first);Map<Long,Long> saving=new HashMap<>();terms.forEach(l->saving.put(l.loanId(),l.currentSavingBalance()));long cash=0;
        for(var row:result.rows().stream().filter(r->YearMonth.from(r.date()).equals(month)).sorted(Comparator.comparing(SimulationRow::date).thenComparingLong(SimulationRow::loanId)).toList()){
            var previous=saving.get(row.loanId());if(previous==null)throw ApiException.unavailable();
            long used=Math.subtractExact(Math.addExact(previous,row.saving()),row.remainingSaving()),scheduled=Math.addExact(row.scheduledPrincipal(),row.interest());
            if(used<0||used>scheduled)throw ApiException.unavailable();
            cash=Math.addExact(cash,Math.addExact(Math.subtractExact(scheduled,used),Math.addExact(row.extraCash(),row.saving())));saving.put(row.loanId(),row.remainingSaving());
        }
        return cash;
    }
    private String revision(ReportInput input){return "rev-"+input.revision()+"-"+input.basisMonth();}
    private Computed unavailable(long id,ReportInput input,String type,String reason){return new Computed(new View(false,reason,id,null,null,null,null,List.of(),false,type,revision(input),null),List.of());}
    private String label(Code code){return switch(code){case FAST->"빠른 상환";case BALANCED->"균형 상환";case STABLE->"생활 안정";case SAVINGS->"상환자금 적립";case RATE_RISK->"금리 위험 대비";case INTEREST_SAVING->"이자 절감";case PARALLEL->"병행";};}
    private String reason(Code code){return switch(code){case RATE_RISK->"추가 상환 가능한 변동금리 대출의 노출을 먼저 줄입니다.";case INTEREST_SAVING->"높은 금리의 대출부터 추가 상환합니다.";
        case PARALLEL->"남은 원금 비율에 따라 기존 추가 자금을 배분합니다.";case SAVINGS->"실제 상환과 구분하여 만기 원금을 위한 자금을 적립합니다.";
        case STABLE->"기본 상환과 생활비 확보를 우선합니다.";default->"반복 가능한 여유자금 범위에서 상환 성향을 비교합니다.";};}
}
