package com.loanrun.onboarding.dto;

import java.util.List;

public record OnboardingResponse(boolean completed, List<Long> registeredLoanIds,
    boolean financeProfileExists, boolean goalsConfigured, String nextStep) {
    public OnboardingResponse { registeredLoanIds = List.copyOf(registeredLoanIds); }
}
