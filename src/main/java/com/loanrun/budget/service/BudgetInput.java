package com.loanrun.budget.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.loanrun.common.error.ApiException;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;

final class BudgetInput {
    private BudgetInput() {}
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
