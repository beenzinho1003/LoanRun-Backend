package com.loanrun.finance.engine;

import com.loanrun.common.contract.FinanceModels.*;
import com.loanrun.common.contract.RepaymentEngine;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;

/** The single pure calculator. All mutable state belongs to one invocation. */
public final class DefaultRepaymentEngine implements RepaymentEngine {
    private static final Set<String> RULES = Set.of("NONE", "KEEP_PLANS", "MANUAL", "RATE_RISK",
        "INTEREST_SAVING", "PARALLEL", "FAST", "BALANCED", "STABLE", "SAVINGS");

    @Override
    public SimulationResult simulate(SimulationInput input) {
        validate(input);
        var warnings = new LinkedHashSet<String>();
        var states = input.terms().stream().filter(t -> t.status() == LoanStatus.ACTIVE && t.outstanding() > 0)
            .sorted(Comparator.comparingLong(LoanTerms::loanId)).map(t -> new LoanState(t, input)).toList();
        List<SimulationRow> rows = new ArrayList<>();
        long totalPrincipal = 0, totalInterest = 0, totalFee = 0, totalCash = 0;
        boolean knownFees = true, insufficient = false;
        for (var state : states) if (state.dates.isEmpty()) {
            warnings.add("MATURITY_PASSED");
            insufficient = true;
        }
        for (int period = 0; period < input.horizonMonths(); period++) {
            var month = YearMonth.from(input.asOf()).plusMonths(period);
            var amounts = input.monthlyAmounts().getOrDefault(month, List.of());
            Map<Long, Work> work = new LinkedHashMap<>();
            for (var state : states) {
                if (state.balance == 0 || state.cursor >= state.dates.size()) continue;
                var date = state.dates.get(state.cursor);
                if (!YearMonth.from(date).equals(month)) continue;
                var item = new Work(state, date);
                if (state.loan.method() == RepaymentMethod.BULLET) {
                    long requested = amounts.stream().filter(a -> a.loanId() == state.loan.loanId()
                        && a.allocationType() == AllocationType.REPAYMENT_SAVING)
                        .map(PlannedAmount::cashAmount).findFirst().orElse(0L);
                    item.saving = Math.min(requested, Math.max(state.balance - state.savings, 0));
                    state.savings = Math.addExact(state.savings, item.saving);
                }
                item.interest = RepaymentMath.interest(state.balance, state.rate);
                item.principal = state.scheduledPrincipal(item.interest, warnings);
                state.balance = Math.subtractExact(state.balance, item.principal);
                if (state.loan.method() == RepaymentMethod.BULLET && item.principal > 0) {
                    item.savingsUsed = Math.min(state.savings, item.principal);
                    state.savings -= item.savingsUsed;
                    if (item.savingsUsed < item.principal) {
                        warnings.add("BULLET_SAVINGS_SHORTFALL");
                        insufficient = true;
                    }
                }
                work.put(state.loan.loanId(), item);
            }
            allocate(amounts, work, input.allocationRule(), warnings);
            for (var item : work.values()) {
                var state = item.state;
                Long fee = PrepaymentFees.fee(state.loan, item.date, item.extraPrincipal);
                if (fee == null) { knownFees = false; warnings.add("FEE_UNKNOWN"); }
                else totalFee = Math.addExact(totalFee, fee);
                long principal = Math.addExact(item.principal, item.extraPrincipal);
                totalPrincipal = Math.addExact(totalPrincipal, principal);
                totalInterest = Math.addExact(totalInterest, item.interest);
                long cash = Math.addExact(Math.addExact(item.principal - item.savingsUsed, item.interest),
                    Math.addExact(item.extraCash, item.saving));
                totalCash = Math.addExact(totalCash, cash);
                rows.add(new SimulationRow(item.date, state.loan.loanId(), item.principal, item.interest,
                    fee, item.extraPrincipal, item.extraCash, item.saving, state.balance, state.savings));
                if (state.balance == 0) state.payoff = item.date;
                state.cursor++;
            }
        }
        boolean complete = !states.isEmpty() && states.stream().allMatch(s -> s.payoff != null);
        if (states.stream().anyMatch(s -> s.balance > 0 && !s.dates.isEmpty())) warnings.add("HORIZON_EXHAUSTED");
        if (warnings.contains("CONTRACTUAL_AMOUNT_BELOW_INTEREST") || warnings.contains("BASIC_PAYMENT_BELOW_INTEREST"))
            insufficient = true;
        LocalDate payoff = complete && !insufficient ? states.stream().map(s -> s.payoff).max(LocalDate::compareTo).orElse(null) : null;
        if (input.targetDate() != null && (payoff == null || payoff.isAfter(input.targetDate())))
            warnings.add("TARGET_NOT_MET");
        rows.sort(Comparator.comparing(SimulationRow::date).thenComparingLong(SimulationRow::loanId));
        return new SimulationResult(rows, payoff, totalPrincipal, totalInterest, knownFees ? totalFee : null,
            knownFees ? totalCash : null, insufficient, knownFees ? "NET" : "GROSS_INTEREST", List.copyOf(warnings));
    }

    private static void allocate(List<PlannedAmount> amounts, Map<Long, Work> work, String rule, Set<String> warnings) {
        boolean strategy = rule.equals("RATE_RISK") || rule.equals("INTEREST_SAVING") || rule.equals("PARALLEL");
        List<PlannedAmount> pooled = new ArrayList<>();
        for (var amount : amounts) {
            if (amount.allocationType() != AllocationType.EXTRA_PAYMENT) continue;
            if (strategy && amount.priority() != null) { pooled.add(amount); continue; }
            var item = work.get(amount.loanId());
            if (eligible(item)) item.addExtra(amount.cashAmount());
            else if (item != null && item.state.inGrace() && amount.cashAmount() > 0)
                warnings.add("GRACE_EXTRA_NOT_APPLIED");
        }
        long budget = pooled.stream().map(PlannedAmount::cashAmount).reduce(0L, Math::addExact);
        var ordered = pooled.stream().sorted(Comparator.comparing(PlannedAmount::priority)
            .thenComparingLong(PlannedAmount::loanId)).map(a -> work.get(a.loanId()))
            .filter(DefaultRepaymentEngine::eligible).toList();
        if (!rule.equals("PARALLEL")) {
            for (var item : ordered) budget -= item.addExtra(budget);
            return;
        }
        // Reweight remaining principal after basic payments, then redistribute unused caps/fee rounding.
        var remaining = new ArrayList<>(ordered);
        remaining.sort(Comparator.comparingLong(w -> w.state.loan.loanId()));
        while (budget > 0 && !remaining.isEmpty()) {
            var total = remaining.stream().map(w -> BigDecimal.valueOf(w.state.balance))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
            long[] shares = new long[remaining.size()];
            long assigned = 0;
            for (int i = 0; i < shares.length; i++) {
                shares[i] = BigDecimal.valueOf(budget).multiply(BigDecimal.valueOf(remaining.get(i).state.balance))
                    .divide(total, 0, RoundingMode.DOWN).longValueExact();
                assigned = Math.addExact(assigned, shares[i]);
            }
            for (int i = 0; assigned < budget; i++, assigned++) shares[i]++;
            long used = 0;
            for (int i = 0; i < shares.length; i++) used = Math.addExact(used, remaining.get(i).addExtra(shares[i]));
            budget -= used;
            remaining.removeIf(w -> w.state.balance == 0);
            if (used == 0) {
                // A one-won share may not cover a rounded fee, although another loan can use it.
                for (var item : remaining) {
                    long spent = item.addExtra(budget);
                    budget -= spent;
                    used = Math.addExact(used, spent);
                }
                if (used == 0) break;
            }
        }
    }

    private static boolean eligible(Work item) {
        return item != null && item.state.balance > 0 && item.state.loan.method() != RepaymentMethod.BULLET
            && !item.state.inGrace();
    }

    @Override
    public TargetResult minimumExtra(TargetInput target) {
        Objects.requireNonNull(target);
        validate(target.baseline());
        var loan = target.baseline().terms().stream().filter(t -> t.loanId() == target.loanId()).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Target loan is outside the simulation"));
        if (target.targetDate() == null || target.targetDate().isBefore(target.baseline().asOf())
                || target.targetDate().isAfter(loan.maturityDate()))
            return new TargetResult(null, false, List.of("INVALID_TARGET_DATE"));
        if (loan.status() != LoanStatus.ACTIVE) return new TargetResult(null, false, List.of("LOAN_NOT_ACTIVE"));
        if (loan.outstanding() == 0) return new TargetResult(0L, true, List.of());
        var dates = RepaymentDates.remaining(loan, target.baseline().asOf()).stream()
            .filter(d -> !d.isAfter(target.targetDate())).toList();
        int months = Math.toIntExact(java.time.temporal.ChronoUnit.MONTHS.between(
            YearMonth.from(target.baseline().asOf()), YearMonth.from(target.targetDate())) + 1);
        if (months > target.baseline().horizonMonths())
            return new TargetResult(null, false, List.of("TARGET_OUTSIDE_HORIZON"));
        if (loan.method() == RepaymentMethod.BULLET) {
            long amount = RepaymentMath.requiredSaving(loan.outstanding(), loan.currentSavingBalance(), dates.size());
            return new TargetResult(amount, dates.size() > 0 || amount == 0,
                dates.isEmpty() && amount > 0 ? List.of("NO_SAVING_PERIOD") : List.of());
        }
        if (dates.isEmpty()) return new TargetResult(null, false, List.of("NO_REPAYMENT_PERIOD"));
        var rates = target.baseline().rateOverrides().containsKey(loan.loanId())
            ? Map.of(loan.loanId(), target.baseline().rateOverrides().get(loan.loanId())) : Map.<Long, BigDecimal>of();
        long low = 0, high = Long.MAX_VALUE;
        var upper = targetSimulation(target, loan, dates, months, rates, high);
        if (!meets(upper, target.targetDate())) return new TargetResult(null, false, upper.warnings());
        while (low < high) {
            long middle = low + (high - low) / 2;
            if (meets(targetSimulation(target, loan, dates, months, rates, middle), target.targetDate())) high = middle;
            else low = middle + 1;
        }
        var result = targetSimulation(target, loan, dates, months, rates, low);
        return new TargetResult(low, true, result.warnings());
    }

    private SimulationResult targetSimulation(TargetInput target, LoanTerms loan, List<LocalDate> dates,
            int months, Map<Long, BigDecimal> rates, long cash) {
        Map<YearMonth, List<PlannedAmount>> amounts = new HashMap<>();
        for (var date : dates) amounts.put(YearMonth.from(date),
            List.of(new PlannedAmount(loan.loanId(), AllocationType.EXTRA_PAYMENT, cash, null)));
        return simulate(new SimulationInput(List.of(loan), target.baseline().asOf(), months,
            amounts, rates, target.targetDate(), "KEEP_PLANS"));
    }

    private static boolean meets(SimulationResult result, LocalDate target) {
        return !result.insufficient() && result.projectedPayoffDate() != null
            && !result.projectedPayoffDate().isAfter(target);
    }

    private static void validate(SimulationInput input) {
        Objects.requireNonNull(input);
        Objects.requireNonNull(input.asOf());
        if (!RULES.contains(input.allocationRule())) throw new IllegalArgumentException("Unknown allocation rule");
        Map<Long, LoanTerms> loans = new HashMap<>();
        Long owner = null;
        for (var loan : input.terms()) {
            if (loan.loanId() <= 0 || loan.userId() <= 0 || loans.put(loan.loanId(), loan) != null)
                throw new IllegalArgumentException("Invalid or duplicate loan identity");
            if (owner != null && owner != loan.userId()) throw new IllegalArgumentException("Mixed simulation owners");
            owner = loan.userId();
            Objects.requireNonNull(loan.status());
            if (loan.status() != LoanStatus.ACTIVE) continue;
            RepaymentMath.requireAmount(loan.outstanding());
            RepaymentMath.requireAmount(loan.principalOriginal());
            RepaymentMath.requireAmount(loan.currentSavingBalance());
            RepaymentMath.requireAmount(loan.initialSavingBalance());
            RepaymentMath.monthlyRate(loan.annualRate());
            Objects.requireNonNull(loan.method());
            Objects.requireNonNull(loan.loanType());
            Objects.requireNonNull(loan.rateType());
            Objects.requireNonNull(loan.snapshotDate());
            Objects.requireNonNull(loan.maturityDate());
            Objects.requireNonNull(loan.nextDueDate());
            if (loan.outstanding() > loan.principalOriginal() || loan.dueDay() < 1 || loan.dueDay() > 31
                    || input.asOf().isBefore(loan.snapshotDate())
                    || loan.nextDueDate().isBefore(loan.snapshotDate()))
                throw new IllegalArgumentException("Invalid loan amount or dates");
            if (loan.contractualNextAmount() != null) RepaymentMath.requireAmount(loan.contractualNextAmount());
            if (loan.baseAnchorAmount() != null) RepaymentMath.requireAmount(loan.baseAnchorAmount());
            if (loan.fixedPrincipal() != null) RepaymentMath.requireAmount(loan.fixedPrincipal());
            if (loan.method() != RepaymentMethod.BULLET && loan.prepaymentPolicy() == null)
                throw new IllegalArgumentException("Installment loans require a prepayment policy");
            if (loan.method() == RepaymentMethod.GRACE_INSTALLMENT && (loan.graceEnd() == null
                    || loan.graceEnd().isAfter(loan.maturityDate())
                    || loan.postGraceMethod() != RepaymentMethod.EQUAL_PAYMENT
                        && loan.postGraceMethod() != RepaymentMethod.EQUAL_PRINCIPAL))
                throw new IllegalArgumentException("Invalid grace terms");
        }
        for (var override : input.rateOverrides().entrySet()) {
            if (!loans.containsKey(override.getKey())) throw new IllegalArgumentException("Unknown rate override loan");
            RepaymentMath.monthlyRate(override.getValue());
        }
        for (var entry : input.monthlyAmounts().entrySet()) {
            Set<Long> seen = new HashSet<>();
            for (var amount : entry.getValue()) {
                var loan = loans.get(amount.loanId());
                if (loan == null || !seen.add(amount.loanId()) || amount.allocationType() == null
                        || amount.cashAmount() < 0 || amount.priority() != null && amount.priority() < 1)
                    throw new IllegalArgumentException("Invalid monthly allocation");
                boolean saving = amount.allocationType() == AllocationType.REPAYMENT_SAVING;
                if (saving != (loan.method() == RepaymentMethod.BULLET))
                    throw new IllegalArgumentException("Allocation does not match repayment method");
            }
        }
    }

    private static final class LoanState {
        final LoanTerms loan;
        final List<LocalDate> dates;
        final BigDecimal rate;
        final boolean rateChanged;
        long balance, savings;
        int cursor;
        Long anchor;
        boolean recalculate;
        LocalDate payoff;

        LoanState(LoanTerms loan, SimulationInput input) {
            this.loan = loan;
            this.dates = RepaymentDates.remaining(loan, input.asOf());
            if (dates.size() > 1200) throw new IllegalArgumentException("Too many remaining installments");
            this.balance = loan.outstanding();
            this.savings = loan.currentSavingBalance();
            this.rate = input.rateOverrides().getOrDefault(loan.loanId(), loan.annualRate());
            this.rateChanged = rate.compareTo(loan.annualRate()) != 0;
        }

        boolean inGrace() {
            return loan.method() == RepaymentMethod.GRACE_INSTALLMENT && dates.get(cursor).isBefore(loan.graceEnd());
        }

        long scheduledPrincipal(long interest, Set<String> warnings) {
            boolean last = cursor == dates.size() - 1;
            if (loan.method() == RepaymentMethod.BULLET) return last ? balance : 0;
            if (inGrace()) return 0;
            var method = loan.method() == RepaymentMethod.GRACE_INSTALLMENT ? loan.postGraceMethod() : loan.method();
            int remaining = dates.size() - cursor;
            if (anchor == null || recalculate && loan.prepaymentPolicy() == PrepaymentPolicy.KEEP_MATURITY) {
                if (method == RepaymentMethod.EQUAL_PRINCIPAL)
                    anchor = anchor == null && loan.fixedPrincipal() != null ? loan.fixedPrincipal()
                        : RepaymentMath.fixedPrincipal(balance, remaining);
                else anchor = anchor == null && !rateChanged && loan.baseAnchorAmount() != null ? loan.baseAnchorAmount()
                    : RepaymentMath.annuity(balance, rate, remaining);
                recalculate = false;
            }
            if (last) return balance;
            if (!rateChanged && loan.contractualNextAmount() != null && dates.get(cursor).equals(loan.nextDueDate())) {
                if (loan.contractualNextAmount() < interest) warnings.add("CONTRACTUAL_AMOUNT_BELOW_INTEREST");
                return Math.min(balance, Math.max(loan.contractualNextAmount() - interest, 0));
            }
            if (method == RepaymentMethod.EQUAL_PAYMENT && anchor < interest) warnings.add("BASIC_PAYMENT_BELOW_INTEREST");
            return Math.min(balance, method == RepaymentMethod.EQUAL_PRINCIPAL ? anchor : Math.max(anchor - interest, 0));
        }
    }

    private static final class Work {
        final LoanState state;
        final LocalDate date;
        long principal, interest, extraPrincipal, extraCash, saving, savingsUsed;
        Work(LoanState state, LocalDate date) { this.state = state; this.date = date; }

        long addExtra(long cash) {
            // Fee rounding applies to the period's cumulative extra principal, not each allocation pass.
            long totalBudget = Math.addExact(extraCash, cash);
            var extra = PrepaymentFees.withinCash(state.loan, date,
                Math.addExact(state.balance, extraPrincipal), totalBudget);
            long used = extra.cash() - extraCash;
            state.balance -= extra.principal() - extraPrincipal;
            state.recalculate |= extra.principal() > extraPrincipal;
            extraPrincipal = extra.principal();
            extraCash = extra.cash();
            return used;
        }
    }
}
