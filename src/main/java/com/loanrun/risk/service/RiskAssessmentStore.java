package com.loanrun.risk.service;

import com.fasterxml.jackson.databind.*;
import com.loanrun.common.contract.*;
import com.loanrun.common.error.ApiException;
import com.loanrun.risk.domain.CompletedRiskInput;
import com.loanrun.risk.domain.RiskHistoryModels.Assessment;
import com.loanrun.risk.dto.RiskDtos.Detail;
import com.loanrun.risk.repository.RiskHistoryRepository;
import java.security.*;
import java.time.*;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
@ConditionalOnProperty(name="loanrun.risk-history.enabled",havingValue="true")
public class RiskAssessmentStore {
    private final RiskHistoryRepository history;private final UserMutationLock locks;private final RevisionPort revisions;private final ObjectMapper json;private final Clock clock;
    public RiskAssessmentStore(RiskHistoryRepository history,UserMutationLock locks,RevisionPort revisions,ObjectMapper json,Clock clock){this.history=history;this.locks=locks;this.revisions=revisions;this.json=json.copy().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);this.clock=clock;}
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public boolean save(CompletedRiskInput captured,Detail household,Map<Long,Detail> selected){
        requireConfirmed(captured,clock);var facts=captured.scenario().household();
        if(!selected.keySet().equals(new HashSet<>(captured.ownedLoanIds()))||household==null)throw ApiException.unavailable();
        for(var detail:selected.values())if(detail==null||!Objects.equals(detail.score(),household.score())||detail.level()!=household.level()
            ||!Objects.equals(detail.indicators(),household.indicators()))throw ApiException.unavailable();
        locks.lock(facts.userId());if(revisions.current(facts.userId())!=captured.revision())return false;
        try{
            var hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(captured)));
            history.append(new Assessment(facts.userId(),facts.month(),captured.revision(),facts.asOf(),hash,json.writeValueAsString(household),json.writeValueAsString(selected),clock.instant()));
            history.finishRequest(facts.userId(),facts.month(),captured.revision());
            return true;
        }catch(com.fasterxml.jackson.core.JsonProcessingException|NoSuchAlgorithmException e){throw new IllegalStateException("Cannot encode risk assessment");}
    }
    public static void requireConfirmed(CompletedRiskInput captured,Clock clock){
        var facts=captured.scenario().household();var month=facts.month();
        boolean valid=month.isBefore(YearMonth.now(clock))&&facts.asOf().equals(month.atEndOfMonth())&&facts.finance().month().equals(month)
            &&facts.finance().expensesConfirmed()&&facts.finance().actualIncome()!=null&&facts.capacity().month().equals(month)&&"SETTLED".equals(facts.capacity().settlementStatus())
            &&facts.due().month().equals(month)&&captured.scenario().payments().month().equals(month)&&captured.scenario().payments().observed()
            &&captured.scenario().savings().month().equals(month)&&captured.scenario().savings().observed()
            &&facts.loans().stream().allMatch(loan->loan.userId()==facts.userId());
        if(!valid)throw ApiException.unavailable();
    }
}
