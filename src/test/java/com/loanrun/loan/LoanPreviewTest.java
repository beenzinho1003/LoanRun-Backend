package com.loanrun.loan;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.loanrun.common.contract.*;
import com.loanrun.common.contract.FinanceModels.*;
import com.loanrun.common.error.*;
import com.loanrun.finance.engine.DefaultRepaymentEngine;
import com.loanrun.loan.controller.LoanController;
import com.loanrun.loan.service.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class LoanPreviewTest {
    final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    final Clock clock = Clock.fixed(Instant.parse("2025-12-31T15:00:00Z"), ZoneId.of("Asia/Seoul"));
    final StaticListableBeanFactory beans = new StaticListableBeanFactory();
    LoanInputValidator validator() { return new LoanInputValidator(clock, beans.getBeanProvider(ProductQueryPort.class)); }
    LoanPreviewService service() { return new LoanPreviewService(validator(), new DefaultRepaymentEngine()); }
    ObjectNode body() throws Exception {
        return (ObjectNode) mapper.readTree("""
        {"loan_form":"GENERAL","loan_type":"MORTGAGE","institution_name":"가상은행","product_name":"가상상품",
         "repayment_method":"EQUAL_PRINCIPAL","original_principal":12000000,"remaining_principal":12000000,
         "annual_rate":6,"rate_type":"FIXED","start_date":"2025-01-25","maturity_date":"2026-12-25",
         "payment_day":25,"prepayment_policy":"KEEP_PAYMENT"}
        """);
    }
    @Test void exactRouteEnvelopeAndNumericResponseWithoutPersistence() throws Exception {
        CurrentUserProvider current = new CurrentUserProvider() {
            public long currentUserId() { return 7; } public PlanType currentPlanType() { return PlanType.FREE; } public void requirePro() { throw new AssertionError(); }
        };
        var mvc = MockMvcBuilders.standaloneSetup(new LoanController(current, service(), null))
            .setControllerAdvice(new GlobalExceptionHandler()).setMessageConverters(new MappingJackson2HttpMessageConverter(mapper)).build();
        mvc.perform(post("/api/loans/preview/").contentType("application/json").content(body().toString()))
            .andExpect(status().isOk()).andExpect(jsonPath("code").value("LOAN_PREVIEW_SUCCESS"))
            .andExpect(jsonPath("data.estimated_monthly_payment").value(1060000))
            .andExpect(jsonPath("data.estimated_next_principal").value(1000000))
            .andExpect(jsonPath("data.estimated_next_interest").value(60000))
            .andExpect(jsonPath("data.remaining_months").value(12));
        mvc.perform(get("/api/loans/preview/")).andExpect(status().isMethodNotAllowed());
    }
    @Test void annuityBulletGraceAndZeroBalancesUseRealEngine() throws Exception {
        var body = body(); body.put("repayment_method", "EQUAL_PAYMENT");
        assertThat(service().preview(7, body).estimatedMonthlyPayment()).isEqualTo(1032797);
        body.put("repayment_method", "BULLET"); body.putNull("prepayment_policy"); body.put("initial_savings_balance", 2000000);
        var result = service().preview(7, body);
        assertThat(result.estimatedNextPrincipal()).isZero(); assertThat(result.estimatedNextInterest()).isEqualTo(60000);
        assertThat(result.requiredMonthlySaving()).isEqualTo(833334); assertThat(result.estimatedTotalFee()).isNull();
        body.put("remaining_principal", 0); assertThat(service().preview(7, body).estimatedMonthlyPayment()).isZero();
        body = body(); body.put("repayment_method", "GRACE_INSTALLMENT"); body.put("grace_months", 15); body.put("post_grace_method", "EQUAL_PRINCIPAL");
        assertThat(service().preview(7, body).estimatedNextPrincipal()).isZero();
        assertThat(service().preview(7, body).estimatedMonthlyPayment()).isEqualTo(60000);
    }
    @Test void optionalDefaultsNullsContractualAmountAndExemptionRemainDistinct() throws Exception {
        var body = body(); body.put("contractual_monthly_payment", 280000); body.put("fee_exempt", true);
        var result = service().preview(7, body);
        assertThat(result.estimatedNextPrincipal()).isEqualTo(220000); assertThat(result.contractualMonthlyPayment()).isEqualTo(280000);
        assertThat(result.estimatedTotalFee()).isZero();
        body.putNull("contractual_monthly_payment"); assertThat(service().preview(7, body).contractualMonthlyPayment()).isNull();
        body.putNull("initial_savings_balance"); assertThatThrownBy(() -> service().preview(7, body)).isInstanceOf(ApiException.class);
    }
    @ParameterizedTest
    @ValueSource(strings = {"{\"user_id\":1}","{\"annual_rate\":\"6\"}","{\"annual_rate\":30}","{\"annual_rate\":6.001}",
        "{\"remaining_principal\":12000001}","{\"remaining_principal\":-1}","{\"remaining_principal\":1.2}",
        "{\"payment_day\":0}","{\"payment_day\":32}","{\"start_date\":\"2027-01-01\"}","{\"maturity_date\":\"2025-12-31\"}",
        "{\"start_date\":\"2025-02-30\"}","{\"loan_form\":\"OVERDRAFT\"}","{\"prepayment_policy\":null}",
        "{\"rate_reset_months\":3}","{\"grace_months\":1}","{\"initial_savings_balance\":1}",
        "{\"prepayment_fee_method\":\"LINEAR\"}","{\"prepayment_fee_method\":null}","{\"product_id\":0}",
        "{\"original_principal\":9223372036854775808}","{\"fee_exempt\":\"true\"}"})
    void rejectsInvalidTypesRangesAndConditionalFields(String patch) throws Exception {
        var body = body(); body.setAll((ObjectNode) mapper.readTree(patch));
        assertThatThrownBy(() -> service().preview(7, body)).isInstanceOfSatisfying(ApiException.class,
            ex -> assertThat(ex.code()).isEqualTo("VALIDATION_ERROR"));
    }
    @Test void missingRequiredFieldsAndExplicitNullCannotBecomeZero() throws Exception {
        for (String key : List.of("loan_form","loan_type","repayment_method","original_principal","remaining_principal","annual_rate","rate_type","start_date","maturity_date","payment_day","institution_name","product_name")) {
            var body = body(); body.remove(key);
            assertThatThrownBy(() -> service().preview(7, body)).as(key).isInstanceOf(ApiException.class);
            body.putNull(key); assertThatThrownBy(() -> service().preview(7, body)).as(key).isInstanceOf(ApiException.class);
        }
    }
    @Test void selectedProductUsesServerMetadataAndDoesNotFakeUnavailableCatalog() throws Exception {
        var body = body(); body.put("product_id", 42); body.remove("institution_name"); body.remove("product_name");
        assertThatThrownBy(() -> service().preview(7, body)).isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.code()).isEqualTo("SERVICE_UNAVAILABLE"));
        beans.addBean("catalog", (ProductQueryPort) id -> null);
        assertThatThrownBy(() -> service().preview(7, body)).isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.code()).isEqualTo("LOAN_PRODUCT_NOT_FOUND"));
        beans.addBean("catalog", (ProductQueryPort) id -> new EligibleProduct(id, 1, "서버은행", "서버상품", LoanType.MORTGAGE, List.of(RepaymentMethod.EQUAL_PRINCIPAL)));
        assertThat(validator().validate(7, body).terms().productName()).isEqualTo("서버상품");
        body.put("loan_type", "CREDIT"); assertThatThrownBy(() -> service().preview(7, body)).isInstanceOf(ApiException.class);
    }
    @Test void overflowIsValidationAndPassedDueDateStartsNextMonth() throws Exception {
        var body = body(); body.put("original_principal", Long.MAX_VALUE); body.put("remaining_principal", Long.MAX_VALUE);
        assertThatThrownBy(() -> service().preview(7, body)).isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.code()).isEqualTo("VALIDATION_ERROR"));
        var late = new LoanInputValidator(Clock.fixed(Instant.parse("2026-01-26T00:00:00Z"), ZoneId.of("Asia/Seoul")), beans.getBeanProvider(ProductQueryPort.class));
        assertThat(late.validate(7, body()).terms().nextDueDate()).isEqualTo(LocalDate.of(2026, 2, 25));
        assertThat(late.validate(7, body()).remainingMonths()).isEqualTo(11);
    }
    @Test void datesClampAndResetConditionsHaveStableKstResults() throws Exception {
        var body = body(); body.put("payment_day", 31); body.put("rate_type", "VARIABLE"); body.put("rate_reset_months", 6);
        assertThat(validator().validate(7, body).terms().nextDueDate()).isEqualTo(LocalDate.of(2026, 1, 31));
        assertThat(service().preview(7, body)).isEqualTo(service().preview(7, body));
        assertThat(validator().validate(7, body).terms().snapshotDate()).isEqualTo(LocalDate.of(2026, 1, 1));
    }
}
