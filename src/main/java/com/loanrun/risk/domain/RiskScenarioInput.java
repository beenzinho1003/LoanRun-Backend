package com.loanrun.risk.domain;

import com.loanrun.common.contract.FinanceModels.*;
import java.util.Objects;

/** Complete immutable facts for evaluating a captured report without rereading live financial data. */
public record RiskScenarioInput(RiskInputs household,PaymentSummary payments,SavingSummary savings) {
    public RiskScenarioInput {Objects.requireNonNull(household);Objects.requireNonNull(payments);Objects.requireNonNull(savings);}
}
