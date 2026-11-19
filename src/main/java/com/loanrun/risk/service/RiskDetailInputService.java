package com.loanrun.risk.service;

import com.loanrun.common.contract.*;
import com.loanrun.common.error.ApiException;
import com.loanrun.risk.domain.RiskScenarioInput;
import com.loanrun.risk.dto.RiskDtos.Detail;
import java.time.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class RiskDetailInputService {
    public record Input(Long selectedLoanId,RiskScenarioInput current,RiskDetailHistorySource.Recorded history,Detail frozen){}
    private final UserAccessPort access;private final ObjectProvider<LoanQueryPort> loans;
    private final ObjectProvider<RiskDetailHistorySource> history;private final RiskSimulationService risks;private final Clock clock;
    public RiskDetailInputService(UserAccessPort access,ObjectProvider<LoanQueryPort> loans,ObjectProvider<RiskDetailHistorySource> history,
            RiskSimulationService risks,Clock clock){this.access=access;this.loans=loans;this.history=history;this.risks=risks;this.clock=clock;}
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Input capture(long userId,Long selected,YearMonth month){
        access.requirePro(userId);
        if(selected!=null&&selected<=0)throw ApiException.validation("loan_id","양의 ID가 필요합니다.");
        if(month.isAfter(YearMonth.now(clock)))throw ApiException.validation("month","미래 월은 조회할 수 없습니다.");
        var source=history.getIfAvailable();if(source==null)throw ApiException.unavailable();
        if(month.isBefore(YearMonth.now(clock))){
            var frozen=source.frozen(userId,selected,month);if(frozen==null)throw ApiException.unavailable();
            return new Input(selected,null,null,frozen);
        }
        if(selected!=null){
            var port=loans.getIfAvailable();if(port==null)throw ApiException.unavailable();
            var owned=port.terms(userId,selected,LocalDate.now(clock));
            if(owned==null||owned.userId()!=userId||owned.loanId()!=selected)throw ApiException.unavailable();
        }
        var recorded=source.changes(userId,month);if(recorded==null)throw ApiException.unavailable();
        return new Input(selected,risks.capture(userId),recorded,null);
    }
}
