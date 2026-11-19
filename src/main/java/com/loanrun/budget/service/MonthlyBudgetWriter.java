package com.loanrun.budget.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.loanrun.budget.domain.MonthlyBudget;
import com.loanrun.budget.dto.BudgetDtos.*;
import com.loanrun.budget.repository.*;
import com.loanrun.common.contract.*;
import com.loanrun.common.error.ApiException;
import com.loanrun.common.validation.JsonPatch;
import java.time.*;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.ObjectProvider;
import com.loanrun.risk.domain.RiskHistoryModels.Origin;

@Service
public class MonthlyBudgetWriter {
    private final MonthlyBudgetRepository budgets;
    private final IncomeEntryRepository entries;
    private final FinanceProfileService profiles;
    private final MonthlyBudgetReader reader;
    private final UserMutationLock locks;
    private final BudgetRecalculationPort recalculation;
    private final RevisionPort revisions;
    private final Clock clock;
    private final ObjectProvider<FinanceChangeRecorder> history;
    public MonthlyBudgetWriter(MonthlyBudgetRepository budgets, IncomeEntryRepository entries, FinanceProfileService profiles,
            MonthlyBudgetReader reader, UserMutationLock locks, BudgetRecalculationPort recalculation, RevisionPort revisions, Clock clock,ObjectProvider<FinanceChangeRecorder> history) {
        this.budgets=budgets; this.entries=entries; this.profiles=profiles; this.reader=reader;
        this.locks=locks; this.recalculation=recalculation; this.revisions=revisions; this.clock=clock;
        this.history=history;
    }
    @Transactional
    public Monthly save(long userId, String monthText, JsonNode body) {
        YearMonth month = BudgetInput.month(monthText);
        if (!month.equals(YearMonth.now(clock))) throw BudgetInput.invalid("year_month", "현재 월만 수정할 수 있습니다.");
        locks.lock(userId);
        profiles.requireProfile(userId);
        profiles.ensureCurrentSnapshots(userId);
        MonthlyBudget budget = budgets.find(userId, month).orElseThrow(ApiException::unavailable);
        var recorder=history.getIfAvailable();var before=recorder==null?null:recorder.capture(userId,month);
        try {
            JsonPatch.fields(body, "fixed_essential_expenses", "variable_essential_expenses", "discretionary_expenses", "savings", "income_entries");
            long fixed=BudgetInput.amount(body,"fixed_essential_expenses"), variable=BudgetInput.amount(body,"variable_essential_expenses"),
                discretionary=BudgetInput.amount(body,"discretionary_expenses"), savings=BudgetInput.amount(body,"savings");
            Math.addExact(Math.addExact(fixed, variable), Math.addExact(discretionary, savings));
            if (body.has("income_entries")) replaceEntries(userId, budget, body.get("income_entries"));
            long income = budgets.entries(userId, budget.id()).stream().map(IncomeEntry::amount).reduce(0L, Math::addExact);
            if (budget.incomeType() == IncomeType.REGULAR) Math.addExact(budget.expectedIncome(), income);
            budgets.saveExpenses(userId, budget.id(), fixed, variable, discretionary, savings, clock.instant());
            recalculation.recalculateFrom(userId, month);
            long revision=revisions.bump(userId, "MONTHLY_BUDGET_CHANGED");
            if(recorder!=null)recorder.record(userId,revision,Origin.MONTHLY_BUDGET,month,before,recorder.capture(userId,month));
            return reader.view(userId, month);
        } catch (ApiException ex) {
            if (!ex.code().equals("VALIDATION_ERROR")) throw ex;
            throw new ApiException(HttpStatus.BAD_REQUEST,"BUDGET_VALIDATION_ERROR","월별 재정정보를 확인해 주세요.",ex.errors());
        } catch (ArithmeticException ex) { throw BudgetInput.invalid("non_field_errors", "금액 합계가 지원 범위를 초과합니다."); }
    }
    private void replaceEntries(long userId, MonthlyBudget budget, JsonNode array) {
        if (!array.isArray()) throw BudgetInput.invalid("income_entries", "배열이어야 합니다.");
        Set<Long> owned = budgets.entries(userId, budget.id()).stream().map(IncomeEntry::incomeEntryId).collect(Collectors.toSet());
        List<IncomeEntryInput> inputs = new ArrayList<>();
        Set<Long> kept = new HashSet<>();
        for (JsonNode value : array) {
            var entry = IncomeEntryInput.parse(value, budget, clock, true);
            if (entry.id()!=null && !owned.contains(entry.id()))
                throw new ApiException(HttpStatus.NOT_FOUND,"NOT_FOUND","조회한 경로 또는 리소스를 찾을 수 없습니다.");
            if (entry.id()!=null && !kept.add(entry.id())) throw BudgetInput.invalid("income_entries", "입금 ID를 중복 없이 입력해 주세요.");
            inputs.add(entry);
        }
        for (Long id : owned) if (!kept.contains(id)) entries.delete(userId, budget.id(), id);
        for (var entry : inputs) {
            if (entry.id()==null) entries.insert(userId,budget.id(),entry.type(),entry.date(),entry.amount(),clock.instant());
            else entries.update(userId,budget.id(),entry.id(),entry.type(),entry.date(),entry.amount());
        }
        budgets.confirmIncome(userId, budget.id(), clock.instant());
    }
}
