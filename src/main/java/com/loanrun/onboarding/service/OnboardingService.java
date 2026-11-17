package com.loanrun.onboarding.service;

import com.loanrun.common.contract.*;
import com.loanrun.common.contract.FinanceModels.*;
import com.loanrun.common.error.ApiException;
import com.loanrun.onboarding.dto.OnboardingResponse;
import com.loanrun.user.repository.DemoUserRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OnboardingService {
    public record CompletionResponse(boolean completed, boolean dashboardReady) {}
    private final DemoUserRepository users;
    private final FinanceProfileQueryPort profiles;
    private final ObjectProvider<LoanQueryPort> loans;
    private final ObjectProvider<PlanQueryPort> plans;
    private final RevisionPort revisions;
    private final Clock clock;
    public OnboardingService(DemoUserRepository users, FinanceProfileQueryPort profiles, ObjectProvider<LoanQueryPort> loans,
            ObjectProvider<PlanQueryPort> plans, RevisionPort revisions, Clock clock) {
        this.users = users; this.profiles = profiles; this.loans = loans; this.plans = plans; this.revisions = revisions; this.clock = clock;
    }

    @Transactional(readOnly = true)
    public OnboardingResponse get(long userId) {
        var user = users.findById(userId).orElseThrow(ApiException::unavailable);
        var loanPort = loans.getIfAvailable();
        if (loanPort == null) throw ApiException.unavailable();
        LocalDate today = LocalDate.now(clock);
        var active = loanPort.activeLoans(userId, today);
        boolean financeExists = profiles.incomeType(userId).isPresent();
        boolean configured = false;
        if (!active.isEmpty()) {
            var planPort = plans.getIfAvailable();
            if (planPort == null) throw ApiException.unavailable();
            var savedPlans = planPort.currentPlans(userId, today);
            configured = active.stream().allMatch(loan -> savedPlans.stream().anyMatch(plan -> valid(loan, plan)));
        }
        String step = user.isOnboardingCompleted() ? "COMPLETE" : active.isEmpty() ? "LOAN" : !financeExists ? "FINANCE"
            : !configured ? "REPAYMENT_GOAL" : "COMPLETE";
        return new OnboardingResponse(user.isOnboardingCompleted(), active.stream().map(LoanTerms::loanId).toList(), financeExists, configured, step);
    }

    private boolean valid(LoanTerms loan, PlanSnapshot plan) {
        if (plan.loanId() != loan.loanId() || !plan.configured()) return false;
        if (plan.mode() == null || !Set.of("MATURITY", "PREFERENCE", "TARGET_DATE", "EXTRA_AMOUNT", "MONTHLY_AMOUNT").contains(plan.mode())) return false;
        if ("PREFERENCE".equals(plan.mode()) && (plan.preference() == null || !Set.of("FAST", "BALANCED", "STABLE").contains(plan.preference()))) return false;
        if ("TARGET_DATE".equals(plan.mode()) && plan.targetPayoffDate() == null) return false;
        if (plan.targetPayoffDate() != null && plan.targetPayoffDate().isAfter(loan.maturityDate())) return false;
        if (loan.method() == RepaymentMethod.BULLET) {
            return plan.targetPayoffDate() != null && !plan.targetPayoffDate().isAfter(loan.maturityDate())
                && plan.monthlySaving() >= 0;
        }
        return loan.prepaymentPolicy() != null;
    }

    @Transactional
    public CompletionResponse complete(long userId) {
        var user = users.findLocked(userId).orElseThrow(ApiException::unavailable);
        if (user.isOnboardingCompleted()) return new CompletionResponse(true, true);
        var progress = get(userId);
        List<String> missing = new ArrayList<>();
        if (progress.registeredLoanIds().isEmpty()) missing.add("LOAN");
        if (!progress.financeProfileExists()) missing.add("FINANCE");
        if (!progress.goalsConfigured()) missing.add("REPAYMENT_GOAL");
        if (!missing.isEmpty()) throw new ApiException(HttpStatus.BAD_REQUEST, "ONBOARDING_INCOMPLETE", "필수 온보딩 정보가 누락되었습니다.", Map.of("missing_steps", missing));
        user.setOnboardingCompleted(true);
        revisions.bump(userId, "ONBOARDING_COMPLETED");
        return new CompletionResponse(true, true);
    }
}
