package com.loanrun.payment.read;

import com.loanrun.payment.domain.DueStateCalculator.State;
import java.time.LocalDate;

/** State evaluated today from existing due rows; not a generated schedule or historical materialization. */
public record StoredDueProjection(StoredDueSource source,LocalDate evaluatedOn,State state) {}
