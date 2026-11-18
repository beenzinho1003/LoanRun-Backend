package com.loanrun.loan.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.loanrun.common.contract.*;
import com.loanrun.common.contract.FinanceModels.*;
import com.loanrun.finance.engine.RepaymentMath;
import com.loanrun.loan.dto.LoanPreview;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class LoanPreviewService {
    private final LoanInputValidator validator;
    private final RepaymentEngine engine;
    public LoanPreviewService(LoanInputValidator validator, RepaymentEngine engine) { this.validator = validator; this.engine = engine; }
    public LoanPreview preview(long userId, JsonNode body) {
        try { return calculate(userId, body); }
        catch (ArithmeticException ex) { throw com.loanrun.common.error.ApiException.validation("non_field_errors", "계산 가능한 금액 범위를 초과했습니다."); }
    }
    private LoanPreview calculate(long userId, JsonNode body) {
        var validated = validator.validate(userId, body); var loan = validated.terms();
        // Include the next calendar month even when the current month's due date has passed.
        var result = engine.simulate(new SimulationInput(List.of(loan), loan.snapshotDate(), 1200, Map.of(), Map.of(), null, "NONE"));
        var row = result.rows().stream().findFirst().orElse(null);
        var warnings = new ArrayList<>(result.warnings());
        // This endpoint calculates the basic contract, not the feasibility of an unfunded BULLET plan.
        warnings.remove("BULLET_SAVINGS_SHORTFALL");
        long principal = row == null ? 0 : row.scheduledPrincipal(), interest = row == null ? 0 : row.interest();
        return new LoanPreview(Math.addExact(principal, interest), loan.contractualNextAmount(), principal, interest,
            loan.maturityDate(), validated.remainingMonths(), warnings, loan.method() == RepaymentMethod.BULLET ? "LUMP_SUM" : "INSTALLMENT",
            loan.prepaymentPolicy(), result.totalFee(), loan.method() == RepaymentMethod.BULLET
                ? RepaymentMath.requiredSaving(loan.outstanding(), loan.currentSavingBalance(), validated.remainingMonths()) : 0);
    }
}
