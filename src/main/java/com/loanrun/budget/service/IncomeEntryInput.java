package com.loanrun.budget.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.loanrun.budget.domain.MonthlyBudget;
import com.loanrun.common.contract.IncomeType;
import com.loanrun.common.validation.JsonPatch;
import java.time.*;
import java.time.format.DateTimeParseException;

record IncomeEntryInput(Long id, String type, LocalDate date, long amount) {
    static IncomeEntryInput parse(JsonNode body, MonthlyBudget budget, Clock clock, boolean allowId) {
        if (allowId) JsonPatch.fields(body, "income_entry_id", "entry_type", "received_date", "amount");
        else JsonPatch.fields(body, "entry_type", "received_date", "amount");
        String type = JsonPatch.text(body, "entry_type", false, 16);
        if (!type.equals(budget.incomeType() == IncomeType.REGULAR ? "EXTRA" : "IRREGULAR"))
            throw BudgetInput.invalid("entry_type", "현재 소득 유형과 일치해야 합니다.");
        LocalDate date;
        try { date = LocalDate.parse(JsonPatch.text(body, "received_date", false, 10)); }
        catch (DateTimeParseException ex) { throw BudgetInput.invalid("received_date", "날짜를 확인해 주세요."); }
        if (!YearMonth.from(date).equals(budget.month()) || date.isAfter(LocalDate.now(clock)))
            throw BudgetInput.invalid("received_date", "해당 월의 오늘 이하 날짜여야 합니다.");
        long amount = BudgetInput.amount(body, "amount");
        if (amount == 0) throw BudgetInput.invalid("amount", "1원 이상이어야 합니다.");
        Long id = body.hasNonNull("income_entry_id") ? BudgetInput.amount(body, "income_entry_id") : null;
        if (id != null && id == 0) throw BudgetInput.invalid("income_entry_id", "양의 ID가 필요합니다.");
        return new IncomeEntryInput(id, type, date, amount);
    }
}
