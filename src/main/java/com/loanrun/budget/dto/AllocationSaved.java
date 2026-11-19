package com.loanrun.budget.dto;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.loanrun.budget.dto.BudgetDtos.*;
import com.loanrun.common.contract.PlanPresentationPort.PlanView;
import java.util.List;

public record AllocationSaved(String yearMonth, AllocationSummary allocation, List<PlanView> updatedPlans,
    String strategyCode, boolean overallPlanExists, boolean monthlyOverrideOnly, @JsonUnwrapped AllocationMetrics metrics) {
    public AllocationSaved { updatedPlans=List.copyOf(updatedPlans); }
    public record AllocationMetrics(Long availableCash, long carryIn, Long allocationCapacity, Long recurringCapacity,
        Long deficitAmount, Long remainingAllocationCapacity, String settlementStatus, Long carryOut, Long reserveSettled,
        Long actualIncome, String incomeStatus, long arrearsAmount, long actualCashRepayment, long actualRepaymentSavings,
        Long discretionaryLimitAmount, Long actualDiscretionaryExpenses, Long discretionarySavingsAmount) {
        public static AllocationMetrics from(Metrics m) {
            return new AllocationMetrics(m.availableCash(),m.carryIn(),m.allocationCapacity(),m.recurringCapacity(),m.deficitAmount(),
                m.remainingAllocationCapacity(),m.settlementStatus(),m.carryOut(),m.reserveSettled(),m.actualIncome(),m.incomeStatus(),
                m.arrearsAmount(),m.actualCashRepayment(),m.actualRepaymentSavings(),m.discretionaryLimitAmount(),
                m.actualDiscretionaryExpenses(),m.discretionarySavingsAmount());
        }
    }
}
