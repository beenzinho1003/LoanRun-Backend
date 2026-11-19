package com.loanrun.risk.service;

import com.loanrun.common.contract.FinanceModels.*;
import com.loanrun.risk.dto.RiskDtos.*;
import com.loanrun.risk.service.RiskDetailHistorySource.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** Pure presentation of confirmed sources. This class never estimates a historical cash impact. */
public final class RiskDetailPresentation {
    private RiskDetailPresentation(){}
    public record History(List<HistoryEvent> events,List<Change> changes,ChangeSummary summary){}
    public static List<Event> events(List<LoanTerms> loans,Long selected,LocalDate asOf){
        List<Event> events=new ArrayList<>();
        for(var loan:loans)if(selected==null||loan.loanId()==selected){
            event(events,"RATE_RESET",loan.loanId(),loan.rateResetDate(),asOf);
            if(loan.method()==RepaymentMethod.GRACE_INSTALLMENT)event(events,"GRACE_END",loan.loanId(),loan.graceEnd(),asOf);
            if(loan.method()==RepaymentMethod.BULLET)event(events,"MATURITY",loan.loanId(),loan.maturityDate(),asOf);
        }
        events.sort(Comparator.comparing(Event::eventDate).thenComparingLong(Event::loanId).thenComparing(Event::type));
        return List.copyOf(events);
    }
    public static History history(Recorded recorded,Long selected,YearMonth month){
        var changes=recorded.events().stream().filter(e->!e.date().isBefore(month.minusMonths(6).atDay(1))&&e.date().isBefore(month.atDay(1)))
            .filter(e->selected==null||e.loanId()==null||e.loanId().equals(selected))
            .sorted(Comparator.comparing(ChangeEvent::date).thenComparing(e->e.reason().name()).thenComparing(e->e.loanId()==null?0:e.loanId())).toList();
        var events=changes.stream().map(e->new HistoryEvent(e.date(),e.reason()==Reason.RATE?"RATE_CHANGE":"BUDGET_CHANGE",e.recordedLevel(),e.description())).toList();
        var sixMonths=changes.stream().map(e->new Change(YearMonth.from(e.date()).toString(),e.direction().name(),e.reason().name(),e.burdenDelta(),e.date(),e.changeValue(),e.changeUnit())).toList();
        boolean observed=recorded.observedMonths().stream().anyMatch(m->!m.isBefore(month.minusMonths(6))&&m.isBefore(month));
        Long net=(!observed&&changes.isEmpty())||changes.stream().anyMatch(e->e.burdenDelta()==null)?null:changes.stream().map(ChangeEvent::burdenDelta).reduce(0L,Math::addExact);
        return new History(events,sixMonths,new ChangeSummary(net,count(changes,Reason.RATE),count(changes,Reason.INCOME),count(changes,Reason.EXPENSE)));
    }
    private static int count(List<ChangeEvent> events,Reason reason){return Math.toIntExact(events.stream().filter(e->e.reason()==reason).count());}
    private static void event(List<Event> events,String type,long loanId,LocalDate date,LocalDate asOf){
        if(date==null||date.isBefore(asOf))return;int days=Math.toIntExact(ChronoUnit.DAYS.between(asOf,date));
        events.add(new Event(type,loanId,date,days,days,days<=30));
    }
}
