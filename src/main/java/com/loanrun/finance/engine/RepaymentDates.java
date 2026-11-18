package com.loanrun.finance.engine;

import com.loanrun.common.contract.FinanceModels.LoanTerms;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

final class RepaymentDates {
    private RepaymentDates() {}

    static LocalDate dueDate(YearMonth month, int day) {
        return month.atDay(Math.min(day, month.lengthOfMonth()));
    }

    /** At most one installment per month. The final installment uses the contract maturity date. */
    static List<LocalDate> remaining(LoanTerms loan, LocalDate asOf) {
        List<LocalDate> dates = new ArrayList<>();
        if (loan.maturityDate().isBefore(asOf)) return dates;
        LocalDate date = loan.nextDueDate();
        for (int i = 0; i <= 2400; i++) {
            if (!YearMonth.from(date).isBefore(YearMonth.from(loan.maturityDate()))) date = loan.maturityDate();
            if (!date.isBefore(asOf)) dates.add(date);
            if (!YearMonth.from(date).isBefore(YearMonth.from(loan.maturityDate()))) return List.copyOf(dates);
            date = dueDate(YearMonth.from(date).plusMonths(1), loan.dueDay());
        }
        throw new IllegalArgumentException("Loan schedule exceeds supported date range");
    }
}
