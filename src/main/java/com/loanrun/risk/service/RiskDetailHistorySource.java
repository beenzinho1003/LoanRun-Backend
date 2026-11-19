package com.loanrun.risk.service;

import com.loanrun.risk.dto.RiskDtos.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

/** Team 2 read boundary for preserved finance changes and owner-scoped ledger events.
 * No default provider: unavailable history must not masquerade as confirmed empty history.
 */
public interface RiskDetailHistorySource {
    enum Reason {RATE,INCOME,EXPENSE}
    enum Direction {INCREASE,DECREASE}
    record ChangeEvent(Long loanId,LocalDate date,Reason reason,Direction direction,Long burdenDelta,
        BigDecimal changeValue,String changeUnit,Level recordedLevel,String description){}
    record Recorded(Set<YearMonth> observedMonths,List<ChangeEvent> events){
        public Recorded {observedMonths=Set.copyOf(observedMonths);events=List.copyOf(events);}
    }
    Recorded changes(long userId,YearMonth basisMonth);
    /** Exact saved household assessment with selected detail, never reconstructed from current inputs.
     * Implementations enforce owner/selection scope; absent confirmed assessment returns unavailable.
     */
    Detail frozen(long userId,Long selectedLoanId,YearMonth month);
}
