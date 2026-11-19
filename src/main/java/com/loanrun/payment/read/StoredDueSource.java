package com.loanrun.payment.read;

import com.loanrun.common.contract.FinanceModels.LoanStatus;
import com.loanrun.loan.read.LoanSources.Due;

/** Current persisted facts, including frozen contractual fields and unmodified actual allocation sums. */
public record StoredDueSource(Due due,LoanStatus loanStatus,long allocationCount,long principal,
    long interest,long fee,long nonRegularAllocations) {}
