package com.loanrun.loan.domain;

import com.loanrun.common.contract.FinanceModels.*;
import com.loanrun.loan.service.LoanInputValidator.Validated;
import java.util.List;

/** Validated source and derived schedule prepared before acquiring a financial mutation lock. */
public record PreparedLoan(Validated input, FeeMethod originalFeeMethod, Long baseAnchor, Long fixedPrincipal,
    long requiredSaving, List<SimulationRow> schedule) {
    public PreparedLoan { schedule=List.copyOf(schedule); }
}
