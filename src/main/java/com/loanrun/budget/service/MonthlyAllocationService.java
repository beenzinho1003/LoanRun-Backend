package com.loanrun.budget.service;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.loanrun.budget.dto.AllocationSaved;
import com.loanrun.budget.repository.*;
import com.loanrun.common.api.ApiResponse;
import com.loanrun.common.contract.*;
import com.loanrun.common.contract.FinanceModels.*;
import com.loanrun.common.error.ApiException;
import com.loanrun.common.idempotency.IdempotencyService;
import com.loanrun.common.idempotency.IdempotencyService.StoredResponse;
import com.loanrun.common.validation.JsonPatch;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MonthlyAllocationService {
    private final MonthlyBudgetRepository budgets;
    private final MonthlyAllocationRepository allocations;
    private final FinanceProfileService profiles;
    private final BudgetReadService reads;
    private final MonthlyBudgetReader reader;
    private final BudgetRecalculationPort recalculation;
    private final RevisionPort revisions;
    private final IdempotencyService idempotency;
    private final UserAccessPort access;
    private final ObjectProvider<PlanQueryPort> plans;
    private final ObjectProvider<PlanPresentationPort> presentation;
    private final ObjectMapper mapper;
    private final Clock clock;
    public MonthlyAllocationService(MonthlyBudgetRepository budgets,MonthlyAllocationRepository allocations,FinanceProfileService profiles,
            BudgetReadService reads,MonthlyBudgetReader reader,BudgetRecalculationPort recalculation,RevisionPort revisions,
            IdempotencyService idempotency,UserAccessPort access,ObjectProvider<PlanQueryPort> plans,
            ObjectProvider<PlanPresentationPort> presentation,ObjectMapper mapper,Clock clock) {
        this.budgets=budgets;this.allocations=allocations;this.profiles=profiles;this.reads=reads;this.reader=reader;
        this.recalculation=recalculation;this.revisions=revisions;this.idempotency=idempotency;this.access=access;
        this.plans=plans;this.presentation=presentation;this.mapper=mapper;this.clock=clock;
    }
    @Transactional
    public StoredResponse save(long userId,String monthText,String key,JsonNode body) {
        YearMonth month=BudgetInput.month(monthText);
        try {
            if(!month.equals(YearMonth.now(clock))) throw BudgetInput.invalid("year_month","현재 월만 수정할 수 있습니다.");
            var stored=idempotency.execute(userId,"PUT","/api/budgets/"+month+"/allocation/",key,body,"IDEMPOTENCY_CONFLICT",()->{
                profiles.requireProfile(userId);profiles.ensureCurrentSnapshots(userId);
                var budget=budgets.find(userId,month).orElseThrow(ApiException::unavailable);
                JsonPatch.fields(body,"allocations","reserve_amount");long reserve=BudgetInput.amount(body,"reserve_amount");
                if(!body.has("allocations")||!body.get("allocations").isArray()) throw BudgetInput.invalid("allocations","배열이어야 합니다.");
                var evaluation=reads.evaluate(userId,month);List<PlannedAmount> requested=new ArrayList<>();Set<Long> ids=new HashSet<>();
                for(JsonNode value:body.get("allocations")) {
                    JsonPatch.fields(value,"loan_id","allocation_type","amount");long loanId=BudgetInput.amount(value,"loan_id");
                    if(loanId==0||!ids.add(loanId)) throw BudgetInput.invalid("allocations","대출 ID를 중복 없이 입력해 주세요.");
                    var loan=reads.loans().terms(userId,loanId,LocalDate.now(clock));
                    if(loan.userId()!=userId||loan.status()!=LoanStatus.ACTIVE) throw new ApiException(HttpStatus.NOT_FOUND,"LOAN_NOT_FOUND","대출을 찾을 수 없습니다.");
                    var type=JsonPatch.enumeration(value,"allocation_type",AllocationType.class);
                    if((loan.method()==RepaymentMethod.BULLET)!=(type==AllocationType.REPAYMENT_SAVING)
                            ||(loan.method()==RepaymentMethod.GRACE_INSTALLMENT&&(loan.graceEnd()==null||LocalDate.now(clock).isBefore(loan.graceEnd()))))
                        throw BudgetInput.invalid("allocations","대출 상환 방식과 거치 기간을 확인해 주세요.");
                    long amount=BudgetInput.amount(value,"amount");
                    long actual=type==AllocationType.REPAYMENT_SAVING?evaluation.savings().additionsByLoan().getOrDefault(loanId,0L):evaluation.payments().extraCashByLoan().getOrDefault(loanId,0L);
                    if(amount<actual) throw BudgetInput.invalid("allocations","이미 실제 사용한 금액 미만으로 줄일 수 없습니다.");
                    requested.add(new PlannedAmount(loanId,type,amount,null));
                }
                long total=reserve;
                for(var p:requested) total=Math.addExact(total,p.cashAmount());
                for(var p:evaluation.payments().extraCashByLoan().entrySet()) if(!ids.contains(p.getKey())) total=Math.addExact(total,p.getValue());
                for(var p:evaluation.savings().additionsByLoan().entrySet()) if(!ids.contains(p.getKey())) total=Math.addExact(total,p.getValue());
                Long capacity=evaluation.metrics().allocationCapacity();
                if(capacity==null||total>capacity) throw BudgetInput.invalid("allocations","배분 합계가 현재 배분 가능액을 초과합니다.");
                var planPort=plans.getIfAvailable();var viewPort=presentation.getIfAvailable();
                if(planPort==null||viewPort==null) throw ApiException.unavailable();
                allocations.replace(budget,requested,reserve,clock.instant());recalculation.recalculateFrom(userId,month);
                revisions.bump(userId,"MONTHLY_ALLOCATION_CHANGED");
                var view=reader.view(userId,month);var overall=planPort.overallState(userId);
                var result=new AllocationSaved(month.toString(),view.allocation(),viewPort.monthViews(userId,LocalDate.now(clock),month,List.copyOf(requested)),
                    overall.strategyCode(),overall.exists(),true,AllocationSaved.AllocationMetrics.from(view.metrics()));
                return new StoredResponse(200,mapper.valueToTree(ApiResponse.success("BUDGET_ALLOCATION_SUCCESS","여유자금 배분을 저장했습니다.",result)));
            });
            if(access.planType(userId)!=PlanType.FREE) return stored;
            var copy=stored.body().deepCopy();var data=(ObjectNode)copy.get("data");
            data.putNull("strategy_code");data.put("overall_plan_exists",false);
            for(var plan:data.path("updated_plans")) if(plan.isObject()) ((ObjectNode)plan).putNull("priority");
            return new StoredResponse(stored.status(),copy);
        } catch(ApiException ex) {
            if(!ex.code().equals("VALIDATION_ERROR")&&!ex.code().equals("BUDGET_VALIDATION_ERROR")) throw ex;
            throw new ApiException(HttpStatus.BAD_REQUEST,"ALLOCATION_VALIDATION_ERROR","배분 입력값을 확인해 주세요.",ex.errors());
        } catch(ArithmeticException ex) { throw new ApiException(HttpStatus.BAD_REQUEST,"ALLOCATION_VALIDATION_ERROR","배분 금액 합계를 확인해 주세요."); }
    }
}
