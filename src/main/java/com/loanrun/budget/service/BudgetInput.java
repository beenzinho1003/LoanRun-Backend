package com.loanrun.budget.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.loanrun.common.error.ApiException;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;

final class BudgetInput {
    private BudgetInput() {}
    static YearMonth month(String value) {
        try {
            if (value == null || !value.matches("[0-9]{4}-(0[1-9]|1[0-2])") || value.startsWith("0000"))
                throw new DateTimeParseException("Invalid", "", 0);
            return YearMonth.parse(value);
        } catch (DateTimeParseException ex) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_MONTH", "월 형식을 확인해 주세요.");
        }
    }
    static long amount(JsonNode body, String field) {
        JsonNode value = body.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 0)
            throw invalid(field, "0 이상의 정수로 입력해 주세요.");
        return value.longValue();
    }
    static Long optionalAmount(JsonNode body, String field) {
        return !body.hasNonNull(field) ? null : amount(body, field);
    }
    static ApiException invalid(String field, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "BUDGET_VALIDATION_ERROR", "월별 재정정보를 확인해 주세요.", Map.of(field, List.of(message)));
    }
}
