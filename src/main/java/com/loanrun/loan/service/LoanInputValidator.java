package com.loanrun.loan.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.loanrun.common.contract.*;
import com.loanrun.common.contract.FinanceModels.*;
import com.loanrun.common.error.ApiException;
import com.loanrun.common.validation.JsonPatch;
import java.math.BigDecimal;
import java.time.*;
import java.time.temporal.ChronoUnit;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** Shared create/preview validation. No persistence or original-data mutation. */
@Service
public class LoanInputValidator {
    private final Clock clock;
    private final ObjectProvider<ProductQueryPort> products;
    public LoanInputValidator(Clock clock, ObjectProvider<ProductQueryPort> products) {
        this.clock = clock; this.products = products;
    }
    public record Validated(LoanTerms terms, Long productId, String institutionName, LocalDate startDate,
        Integer rateResetMonths, LocalDate nextRateResetDate, int graceMonths, Boolean feeExempt, int remainingMonths) {}

    public Validated validate(long userId, JsonNode body) {
        JsonPatch.fields(body, "loan_form", "loan_type", "product_id", "institution_name", "product_name",
            "repayment_method", "original_principal", "remaining_principal", "annual_rate", "rate_type",
            "rate_reset_months", "next_rate_reset_date", "start_date", "maturity_date", "payment_day",
            "contractual_monthly_payment", "grace_months", "post_grace_method", "prepayment_policy",
            "prepayment_fee_method", "prepayment_fee_rate", "fee_start_date", "fee_end_date", "fee_exempt", "initial_savings_balance");
        check("GENERAL".equals(JsonPatch.text(body, "loan_form", false, 60)), "loan_form");
        var type = JsonPatch.enumeration(body, "loan_type", LoanType.class);
        var method = JsonPatch.enumeration(body, "repayment_method", RepaymentMethod.class);
        var rateType = JsonPatch.enumeration(body, "rate_type", RateType.class);
        long original = integer(body, "original_principal", false, null);
        long balance = integer(body, "remaining_principal", false, null);
        check(original > 0 && balance >= 0 && balance <= original, "remaining_principal");
        var rate = decimal(body, "annual_rate", false);
        check(rate.signum() >= 0 && rate.compareTo(BigDecimal.valueOf(30)) < 0 && rate.stripTrailingZeros().scale() <= 2, "annual_rate");
        var today = LocalDate.now(clock);
        var start = date(body, "start_date", false); var maturity = date(body, "maturity_date", false);
        check(!start.isAfter(today), "start_date");
        check(maturity.isAfter(start) && maturity.isAfter(today), "maturity_date");
        long dayValue = integer(body, "payment_day", false, null);
        check(dayValue >= 1 && dayValue <= 31, "payment_day"); int day = (int) dayValue;
        var month = YearMonth.from(today);
        var next = month.atDay(Math.min(day, month.lengthOfMonth()));
        if (next.isBefore(today)) { month = month.plusMonths(1); next = month.atDay(Math.min(day, month.lengthOfMonth())); }
        if (!YearMonth.from(next).isBefore(YearMonth.from(maturity))) next = maturity;
        int periods = Math.toIntExact(ChronoUnit.MONTHS.between(YearMonth.from(next), YearMonth.from(maturity)) + 1);
        check(periods > 0 && ChronoUnit.MONTHS.between(YearMonth.from(today), YearMonth.from(maturity)) < 1200, "maturity_date");
        Long reset = integer(body, "rate_reset_months", true, null);
        check(reset == null || rateType == RateType.VARIABLE && (reset == 3 || reset == 6 || reset == 12), "rate_reset_months");
        var nextReset = date(body, "next_rate_reset_date", true);
        check(nextReset == null || rateType == RateType.VARIABLE, "next_rate_reset_date");
        Long nextAmount = integer(body, "contractual_monthly_payment", true, null);
        check(nextAmount == null || nextAmount >= 0, "contractual_monthly_payment");
        long grace = integer(body, "grace_months", false, 0L);
        check(grace >= 0 && grace <= Integer.MAX_VALUE, "grace_months");
        var post = optionalEnum(body, "post_grace_method", RepaymentMethod.class);
        var policy = optionalEnum(body, "prepayment_policy", PrepaymentPolicy.class);
        LocalDate graceEnd = null;
        if (method == RepaymentMethod.GRACE_INSTALLMENT) {
            check(grace > 0 && start.plusMonths(grace).isBefore(maturity), "grace_months");
            check(post == RepaymentMethod.EQUAL_PAYMENT || post == RepaymentMethod.EQUAL_PRINCIPAL, "post_grace_method");
            graceEnd = start.plusMonths(grace);
        } else { check(grace == 0, "grace_months"); check(post == null, "post_grace_method"); }
        check(method == RepaymentMethod.BULLET ? policy == null : policy != null, "prepayment_policy");
        FeeMethod fee = body.has("prepayment_fee_method") ? JsonPatch.enumeration(body, "prepayment_fee_method", FeeMethod.class) : FeeMethod.UNKNOWN;
        var feeRate = decimal(body, "prepayment_fee_rate", true);
        var feeStart = date(body, "fee_start_date", true); var feeEnd = date(body, "fee_end_date", true);
        check(feeRate == null || feeRate.signum() >= 0, "prepayment_fee_rate");
        check(feeStart == null || feeEnd == null || !feeEnd.isBefore(feeStart), "fee_end_date");
        if (fee == FeeMethod.FIXED || fee == FeeMethod.LINEAR) check(feeRate != null, "prepayment_fee_rate");
        if (fee == FeeMethod.LINEAR) check(feeStart != null && feeEnd != null && feeEnd.isAfter(feeStart), "fee_end_date");
        Boolean exempt = body.hasNonNull("fee_exempt") ? JsonPatch.bool(body, "fee_exempt") : null;
        long savings = integer(body, "initial_savings_balance", false, 0L);
        check(savings >= 0 && (method == RepaymentMethod.BULLET || savings == 0), "initial_savings_balance");
        Long productId = integer(body, "product_id", true, null);
        String institution, name;
        if (productId != null) {
            check(productId > 0, "product_id");
            var port = products.getIfAvailable(); if (port == null) throw ApiException.unavailable();
            var product = port.findEligibleProduct(productId);
            if (product == null) throw new ApiException(HttpStatus.NOT_FOUND, "LOAN_PRODUCT_NOT_FOUND", "대출상품을 찾을 수 없습니다.");
            check(product.loanType() == type && product.repaymentMethods().contains(method), "product_id");
            institution = product.institutionName(); name = product.name();
        } else {
            institution = JsonPatch.text(body, "institution_name", false, Integer.MAX_VALUE);
            name = JsonPatch.text(body, "product_name", false, Integer.MAX_VALUE);
        }
        var terms = new LoanTerms(1, userId, name, type, original, balance, today, rate, rateType, method,
            graceEnd, post, next, day, maturity, nextAmount, null, null, policy, Boolean.TRUE.equals(exempt) ? FeeMethod.NONE : fee,
            feeRate, feeStart, feeEnd, savings, savings, LoanStatus.ACTIVE, nextReset);
        return new Validated(terms, productId, institution, start, reset == null ? null : reset.intValue(), nextReset, (int) grace, exempt, periods);
    }
    private static void check(boolean valid, String field) { if (!valid) throw ApiException.validation(field, "입력값의 범위와 조건을 확인해 주세요."); }
    private static Long integer(JsonNode body, String key, boolean nullable, Long fallback) {
        if (!body.has(key)) { if (nullable || fallback != null) return fallback; throw ApiException.validation(key, "필수 정수입니다."); }
        var value = body.get(key); if (nullable && value.isNull()) return null;
        check(value.isIntegralNumber() && value.canConvertToLong(), key); return value.longValue();
    }
    private static BigDecimal decimal(JsonNode body, String key, boolean nullable) {
        if (nullable && !body.hasNonNull(key)) return null;
        var value = body.get(key); check(value != null && value.isNumber(), key); return value.decimalValue();
    }
    private static LocalDate date(JsonNode body, String key, boolean nullable) {
        if (nullable && !body.hasNonNull(key)) return null;
        var value = body.get(key); check(value != null && value.isTextual() && value.textValue().matches("\\d{4}-\\d{2}-\\d{2}"), key);
        try { return LocalDate.parse(value.textValue()); } catch (java.time.DateTimeException ex) { throw ApiException.validation(key, "올바른 날짜를 입력해 주세요."); }
    }
    private static <E extends Enum<E>> E optionalEnum(JsonNode body, String key, Class<E> type) {
        return body.hasNonNull(key) ? JsonPatch.enumeration(body, key, type) : null;
    }
}
