package com.loanrun.budget.service;

import com.fasterxml.jackson.databind.*;
import com.loanrun.budget.dto.BudgetDtos.IncomeEntry;
import com.loanrun.budget.dto.IncomeCreated;
import com.loanrun.budget.repository.*;
import com.loanrun.common.api.ApiResponse;
import com.loanrun.common.contract.*;
import com.loanrun.common.error.ApiException;
import com.loanrun.common.idempotency.IdempotencyService;
import com.loanrun.common.idempotency.IdempotencyService.StoredResponse;
import java.time.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.ObjectProvider;
import com.loanrun.risk.domain.RiskHistoryModels.Origin;

@Service
public class IncomeEntryService {
    private final MonthlyBudgetRepository budgets;
    private final IncomeEntryRepository entries;
    private final FinanceProfileService profiles;
    private final MonthlyBudgetReader reader;
    private final BudgetRecalculationPort recalculation;
    private final RevisionPort revisions;
    private final IdempotencyService idempotency;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final ObjectProvider<FinanceChangeRecorder> history;
    public IncomeEntryService(MonthlyBudgetRepository budgets, IncomeEntryRepository entries, FinanceProfileService profiles,
            MonthlyBudgetReader reader, BudgetRecalculationPort recalculation, RevisionPort revisions,
            IdempotencyService idempotency, ObjectMapper mapper, Clock clock,ObjectProvider<FinanceChangeRecorder> history) {
        this.budgets=budgets; this.entries=entries; this.profiles=profiles; this.reader=reader;
        this.recalculation=recalculation; this.revisions=revisions; this.idempotency=idempotency; this.mapper=mapper; this.clock=clock;
        this.history=history;
    }
    @Transactional
    public StoredResponse create(long userId, String key, JsonNode body) {
        try {
            return idempotency.execute(userId,"POST","/api/income-entries/",key,body,"INCOME_ENTRY_CONFLICT",()->{
                var month=YearMonth.now(clock);
                profiles.requireProfile(userId);
                profiles.ensureCurrentSnapshots(userId);
                var budget=budgets.find(userId,month).orElseThrow(ApiException::unavailable);
                var recorder=history.getIfAvailable();var before=recorder==null?null:recorder.capture(userId,month);
                var input=IncomeEntryInput.parse(body,budget,clock,false);
                long total=budgets.entries(userId,budget.id()).stream().map(IncomeEntry::amount).reduce(input.amount(),Math::addExact);
                if(budget.incomeType()==IncomeType.REGULAR) Math.addExact(budget.expectedIncome(),total);
                var created=entries.insert(userId,budget.id(),input.type(),input.date(),input.amount(),clock.instant());
                budgets.confirmIncome(userId,budget.id(),clock.instant());
                recalculation.recalculateFrom(userId,month);
                long revision=revisions.bump(userId,"INCOME_CHANGED");
                if(recorder!=null)recorder.record(userId,revision,Origin.INCOME_ENTRY,month,before,recorder.capture(userId,month));
                var view=reader.view(userId,month);
                return new StoredResponse(201,mapper.valueToTree(ApiResponse.success("INCOME_ENTRY_CREATE_SUCCESS","소득 입금 기록을 저장했습니다.",
                    new IncomeCreated(created,month.toString(),view.monthlyIncome(),view.allocationNeedsReview(),view.metrics()))));
            });
        } catch(ApiException ex) {
            if(ex.code().equals("INCOME_ENTRY_CONFLICT")) throw new ApiException(HttpStatus.CONFLICT,"INCOME_ENTRY_CONFLICT","이미 반영된 소득 기록입니다.");
            if(!ex.code().equals("VALIDATION_ERROR")) throw ex;
            throw new ApiException(HttpStatus.BAD_REQUEST,"BUDGET_VALIDATION_ERROR","월별 재정정보를 확인해 주세요.",ex.errors());
        } catch(ArithmeticException ex) { throw BudgetInput.invalid("amount","금액 합계가 지원 범위를 초과합니다."); }
    }
}
