package com.loanrun.loan.dto;

import com.loanrun.common.contract.FinanceModels.PrepaymentPolicy;
import java.time.LocalDate;
import java.util.List;

public record LoanPreview(long estimatedMonthlyPayment, Long contractualMonthlyPayment,
    long estimatedNextPrincipal, long estimatedNextInterest, LocalDate maturityDate, int remainingMonths,
    List<String> warnings, String repaymentCategory, PrepaymentPolicy prepaymentPolicy,
    Long estimatedTotalFee, long requiredMonthlySaving) {
    public LoanPreview { warnings = List.copyOf(warnings); }
}
