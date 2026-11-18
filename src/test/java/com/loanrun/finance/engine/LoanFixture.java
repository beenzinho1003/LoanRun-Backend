package com.loanrun.finance.engine;

import com.loanrun.common.contract.FinanceModels.*;
import java.math.BigDecimal;
import java.time.LocalDate;

/** Synthetic inputs only; no Spring, database, session, or production fixture. */
final class LoanFixture {
    long id = 1, owner = 7, balance = 12_000_000, original = 12_000_000, initialSaving, saving;
    LoanType type = LoanType.MORTGAGE;
    BigDecimal rate = new BigDecimal("6.00"), feeRate;
    RateType rateType = RateType.FIXED;
    RepaymentMethod method = RepaymentMethod.EQUAL_PAYMENT, postGrace;
    LocalDate snapshot = LocalDate.of(2026, 1, 1), next = LocalDate.of(2026, 1, 25),
        maturity = LocalDate.of(2026, 12, 25), grace, feeStart, feeEnd;
    int day = 25;
    Long nextAmount, anchor, fixedPrincipal;
    PrepaymentPolicy policy = PrepaymentPolicy.KEEP_PAYMENT;
    FeeMethod feeMethod = FeeMethod.NONE;
    LoanStatus status = LoanStatus.ACTIVE;

    LoanFixture() {}
    LoanFixture(RepaymentMethod method) { this.method = method; }

    LoanTerms terms() {
        return new LoanTerms(id, owner, "가상 대출 " + id, type, original, balance, snapshot, rate,
            rateType, method, grace, postGrace, next, day, maturity, nextAmount, anchor, fixedPrincipal,
            policy, feeMethod, feeRate, feeStart, feeEnd, initialSaving, saving, status, null);
    }
}
