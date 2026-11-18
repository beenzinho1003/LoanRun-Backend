package com.loanrun.budget.service;

import com.loanrun.budget.domain.MonthlyBudget;
import com.loanrun.budget.dto.BudgetDtos.*;
import com.loanrun.budget.repository.*;
import com.loanrun.common.contract.*;
import com.loanrun.common.contract.FinanceModels.*;
import com.loanrun.common.error.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BudgetReadService implements BudgetQueryPort {
    public record Evaluation(MonthlyBudget budget, List<IncomeEntry> entries, DueSummary due,
        PaymentSummary payments, SavingSummary savings, Metrics metrics, Long monthlyIncome) {}
    private final MonthlyBudgetRepository budgets;
    private final FinanceProfileRepository profiles;
    private final ObjectProvider<LoanQueryPort> loans;
    private final Clock clock;
    public BudgetReadService(MonthlyBudgetRepository budgets, FinanceProfileRepository profiles, ObjectProvider<LoanQueryPort> loans, Clock clock) {
        this.budgets = budgets; this.profiles = profiles; this.loans = loans; this.clock = clock;
    }
    public LoanQueryPort loans() {
        var result = loans.getIfAvailable();
        if (result == null) throw ApiException.unavailable();
        return result;
    }

    @Transactional(readOnly=true)
    public List<Evaluation> through(long userId, YearMonth requested) {
        LoanQueryPort port = loans();
        YearMonth now = YearMonth.now(clock);
        var rows = new ArrayList<>(budgets.through(userId, requested));
        if (rows.stream().noneMatch(row -> row.month().equals(requested))) {
            var profile = profiles.find(userId);
            if (profile.isPresent() && !requested.isBefore(now)) rows.add(MonthlyBudget.forecast(profile.get(), requested));
        }
        List<Evaluation> result = new ArrayList<>();
        Evaluation previous = null;
        for (MonthlyBudget row : rows) {
            long carry = previous != null && previous.budget().month().plusMonths(1).equals(row.month())
                && previous.metrics().carryOut() != null ? previous.metrics().carryOut() : 0;
            var entries = budgets.entries(userId, row.id());
            long sum = entries.stream().map(IncomeEntry::amount).reduce(0L, Math::addExact);
            Long actual;
            String incomeStatus;
            Long planning;
            if (row.incomeType() == IncomeType.REGULAR) {
                planning = Math.addExact(row.expectedIncome(), sum);
                LocalDate payday = row.month().atDay(Math.min(row.payday(), row.month().lengthOfMonth()));
                boolean received = !LocalDate.now(clock).isBefore(payday);
                actual = received ? planning : !entries.isEmpty() || row.incomeConfirmed() ? sum : null;
                incomeStatus = received ? "ASSUMED" : "FORECAST";
            } else {
                actual = row.incomeConfirmed() || !entries.isEmpty() ? sum : null;
                incomeStatus = actual == null ? "INSUFFICIENT" : "INPUT";
                var observed = result.stream().filter(e -> e.budget().month().isBefore(now)
                    && e.budget().incomeType() == IncomeType.IRREGULAR && e.metrics().actualIncome() != null).toList();
                if (observed.size() >= 3) {
                    BigDecimal total = observed.subList(observed.size()-3, observed.size()).stream()
                        .map(e -> BigDecimal.valueOf(e.metrics().actualIncome())).reduce(BigDecimal.ZERO, BigDecimal::add);
                    planning = total.divide(BigDecimal.valueOf(3), 0, RoundingMode.HALF_UP).longValueExact();
                } else planning = row.expectedIncome();
            }
            var due = port.dueSummary(userId, row.month());
            var payments = port.paymentSummary(userId, row.month());
            var savings = port.savingSummary(userId, row.month());
            long extraCash = payments.extraCashByLoan().values().stream().reduce(0L, Math::addExact);
            var metrics = BudgetMath.calculate(row, planning, actual, incomeStatus, due, payments, savings, carry, extraCash, now);
            previous = new Evaluation(row, entries, due, payments, savings, metrics,
                row.incomeType() == IncomeType.REGULAR ? planning : actual);
            result.add(previous);
        }
        if (result.isEmpty() || !result.getLast().budget().month().equals(requested)) {
            var due = port.dueSummary(userId, requested);
            var payments = port.paymentSummary(userId, requested);
            var savings = port.savingSummary(userId, requested);
            var metrics = BudgetMath.calculate(null, null, null, "INSUFFICIENT", due, payments, savings, 0,
                payments.extraCashByLoan().values().stream().reduce(0L, Math::addExact), now);
            result.add(new Evaluation(null, List.of(), due, payments, savings, metrics, null));
        }
        return List.copyOf(result);
    }

    @Transactional(readOnly=true)
    public Evaluation evaluate(long userId, YearMonth month) { return through(userId, month).getLast(); }

    @Override @Transactional(readOnly=true)
    public BudgetCapacity capacity(long userId, YearMonth month) {
        var e = evaluate(userId, month); var m = e.metrics();
        return new BudgetCapacity(month, m.availableCash(), m.recurringCapacity(), m.carryIn(), m.allocationCapacity(),
            e.payments().extraCashByLoan().values().stream().reduce(0L, Math::addExact), e.savings().additions(), m.remainingAllocationCapacity(),
            e.budget() == null ? 0 : e.budget().reserveAmount(), m.deficitAmount(), m.incomeStatus(), m.settlementStatus(),
            m.availableCash() == null ? List.of("INSUFFICIENT_DATA") : List.of());
    }

    @Override @Transactional(readOnly=true)
    public List<PlannedAmount> monthlyOverrides(long userId, YearMonth month) {
        return budgets.find(userId, month).filter(MonthlyBudget::allocationOverridden).map(budgets::overrides).orElse(List.of());
    }

    @Override @Transactional(readOnly=true)
    public FinanceSnapshot financeSnapshot(long userId, YearMonth month) {
        var e = evaluate(userId, month); var b = e.budget(); var m = e.metrics();
        return new FinanceSnapshot(month, b == null ? null : b.incomeType(), m.planningMonthlyIncome(), m.actualIncome(),
            b == null ? null : b.fixedEssentialExpenses(), b == null ? null : b.variableEssentialExpenses(),
            b == null ? null : b.discretionaryExpenses(), m.actualDiscretionaryExpenses(), m.discretionaryLimitAmount(),
            b == null ? null : b.savings(), b == null ? null : b.emergencyFund(), b != null && b.expensesConfirmed(), b == null ? null : b.source());
    }
}
