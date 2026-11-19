package com.loanrun.budget.dto;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.loanrun.common.contract.IncomeType;
import com.loanrun.common.contract.FinanceModels.AllocationType;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

public final class BudgetDtos {
    private BudgetDtos() {}
    public record IncomeEntry(long incomeEntryId, String entryType, LocalDate receivedDate, long amount) {}
    public record Allocation(long loanId, long amount, AllocationType allocationType, long actualAmount) {}
    public record AllocationSummary(List<Allocation> allocations, long reserveAmount, long totalAllocated, Long unallocatedAmount) {
        public AllocationSummary { allocations = List.copyOf(allocations); }
    }
    public record Metrics(Long availableCash, Long planningMonthlyIncome, Long actualAvailableCash, long carryIn,
        Long allocationCapacity, Long recurringCapacity, Long deficitAmount, Long remainingAllocationCapacity,
        String settlementStatus, Long carryOut, Long reserveSettled, Long actualIncome, String incomeStatus,
        long arrearsAmount, long actualCashRepayment, long actualRepaymentSavings, Long discretionaryLimitAmount,
        Long actualDiscretionaryExpenses, Long discretionarySavingsAmount) {}
    public record Monthly(String yearMonth, Long monthlyIncome, IncomeType incomeType, Integer payday,
        Long essentialExpenses, Long discretionaryExpenses, Long savings, Long emergencyFund, long scheduledRepayment,
        long actualRepayment, AllocationSummary allocation, String dataStatus, OffsetDateTime updatedAt,
        Long fixedEssentialExpenses, Long variableEssentialExpenses, String source, Long baseIncome,
        List<IncomeEntry> incomeEntries, boolean allocationNeedsReview, @JsonUnwrapped Metrics metrics) {
        public Monthly { incomeEntries = List.copyOf(incomeEntries); }
    }
}
