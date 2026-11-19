package com.loanrun.risk.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loanrun.common.contract.*;
import com.loanrun.common.contract.FinanceModels.DateRange;
import com.loanrun.common.error.ApiException;
import com.loanrun.risk.domain.RiskHistoryModels.Kind;
import com.loanrun.risk.dto.RiskDtos.Detail;
import com.loanrun.risk.repository.RiskHistoryRepository;
import java.math.*;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
@ConditionalOnProperty(name="loanrun.risk-history.enabled",havingValue="true")
@Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
public class RiskHistorySourceAdapter implements RiskDetailHistorySource {
    private final RiskHistoryRepository history;private final ObjectProvider<LoanEventQueryPort> loans;private final ObjectProvider<LoanQueryPort> ownership;private final ObjectMapper json;private final JdbcTemplate jdbc;
    public RiskHistorySourceAdapter(RiskHistoryRepository history,ObjectProvider<LoanEventQueryPort> loans,ObjectProvider<LoanQueryPort> ownership,ObjectMapper json,JdbcTemplate jdbc){this.history=history;this.loans=loans;this.ownership=ownership;this.json=json;this.jdbc=jdbc;}
    public Recorded changes(long userId,YearMonth basis){
        var port=loans.getIfAvailable();if(port==null)throw ApiException.unavailable();
        var start=basis.minusMonths(6).atDay(1);var end=basis.atDay(1).minusDays(1);
        List<ChangeEvent> events=new ArrayList<>();
        for(var row:history.changes(userId,start,end)){
            Long before=row.beforeAmount(),after=row.afterAmount();if(before==null||after==null)continue;
            long difference=Math.subtractExact(after,before),burden=row.kind()==Kind.INCOME?Math.negateExact(difference):difference;
            var value=row.kind()==Kind.EXPENSE?BigDecimal.valueOf(difference):before==0?null:BigDecimal.valueOf(difference).multiply(BigDecimal.valueOf(100)).divide(BigDecimal.valueOf(before),2,RoundingMode.HALF_UP);
            events.add(new ChangeEvent(null,row.eventDate(),row.kind()==Kind.INCOME?Reason.INCOME:Reason.EXPENSE,
                burden<0?Direction.DECREASE:Direction.INCREASE,row.cashImpactKnown()?burden:null,value,row.kind()==Kind.INCOME?"PCT":"KRW",null,
                row.kind()==Kind.INCOME?"기록된 계획 소득이 변경되었습니다.":"기록된 계획 지출이 변경되었습니다."));
        }
        var rates=port.rateChanges(userId,new DateRange(start,end));if(rates==null)throw ApiException.unavailable();
        for(var event:rates){
            if(event.eventDate().isBefore(start)||event.eventDate().isAfter(end)||event.rateChangePp()==null||event.rateChangePp().signum()==0)continue;
            boolean increase=event.monthlyBurdenDelta()==null?event.rateChangePp().signum()>0:event.monthlyBurdenDelta()>=0;
            events.add(new ChangeEvent(event.loanId(),event.eventDate(),Reason.RATE,increase?Direction.INCREASE:Direction.DECREASE,
                event.monthlyBurdenDelta(),event.rateChangePp(),"PP",null,"기록된 대출 금리가 변경되었습니다."));
        }
        events.sort(Comparator.comparing(ChangeEvent::date).thenComparing(e->e.reason().name()).thenComparing(e->e.loanId()==null?0:e.loanId()));
        // Observation comes from the budget settlement, independently of whether a risk result exists.
        var observed=jdbc.query("SELECT year_month FROM monthly_budgets WHERE user_id=? AND year_month>=? AND year_month<? AND settlement_status='SETTLED'",
            (r,n)->YearMonth.from(r.getDate(1).toLocalDate()),userId,java.sql.Date.valueOf(start),java.sql.Date.valueOf(basis.atDay(1)));
        return new Recorded(Set.copyOf(observed),events);
    }
    public Detail frozen(long userId,Long selected,YearMonth month){
        if(history.pending(userId,month))throw ApiException.unavailable();
        var found=history.latest(userId,month);
        if(found.isEmpty()){
            if(selected!=null){var owner=ownership.getIfAvailable();if(owner==null)throw ApiException.unavailable();
                var loan=owner.terms(userId,selected,month.atEndOfMonth());
                if(loan==null||loan.userId()!=userId||loan.loanId()!=selected)throw ApiException.unavailable();}
            throw ApiException.unavailable();
        }
        var snapshot=found.get();
        try{
            var payload=selected==null?json.readTree(snapshot.householdPayload()):json.readTree(snapshot.loanPayloads()).get(selected.toString());
            if(payload==null)throw new ApiException(HttpStatus.NOT_FOUND,"LOAN_NOT_FOUND","대출을 찾을 수 없습니다.");
            return json.treeToValue(payload,Detail.class);
        }catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalStateException("Invalid saved risk assessment");}
    }
}
