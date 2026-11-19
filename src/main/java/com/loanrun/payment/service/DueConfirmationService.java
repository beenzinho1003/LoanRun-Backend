package com.loanrun.payment.service;

import com.fasterxml.jackson.databind.*;
import com.loanrun.common.api.ApiResponse;
import com.loanrun.common.contract.*;
import com.loanrun.common.error.ApiException;
import com.loanrun.common.idempotency.IdempotencyService;
import com.loanrun.common.idempotency.IdempotencyService.StoredResponse;
import com.loanrun.payment.dto.DueConfirmation;
import com.loanrun.payment.repository.DueConfirmationRepository;
import java.time.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DueConfirmationService {
    private final IdempotencyService idempotency;
    private final RevisionPort revisions;
    private final DueConfirmationRepository dues;
    private final ObjectProvider<BudgetRecalculationPort> budgets;
    private final ObjectMapper mapper;
    private final Clock clock;
    public DueConfirmationService(IdempotencyService idempotency,RevisionPort revisions,DueConfirmationRepository dues,
        ObjectProvider<BudgetRecalculationPort> budgets,ObjectMapper mapper,Clock clock) {
        this.idempotency=idempotency;this.revisions=revisions;this.dues=dues;this.budgets=budgets;this.mapper=mapper;this.clock=clock;
    }

    @Transactional
    public StoredResponse confirm(long userId,long dueId,JsonNode body,String key) {
        // The shared service acquires the user lock and returns an existing response before state/dependency checks.
        try {
            return idempotency.execute(userId,"PATCH","/api/payments/schedule/"+dueId+"/confirmation/",key,body,
                "DUE_CONFIRMATION_CONFLICT",()->save(userId,dueId,body));
        } catch(ApiException ex) {
            if("DUE_CONFIRMATION_CONFLICT".equals(ex.code()))throw DueConfirmationRepository.conflict();
            throw ex;
        }
    }
    private StoredResponse save(long userId,long dueId,JsonNode body) {
        if(body==null||!body.isObject()||body.size()!=1||!body.path("unpaid_confirmed").isBoolean())
            throw ApiException.validation("unpaid_confirmed","unpaid_confirmed boolean 값만 입력해 주세요.");
        boolean confirmed=body.path("unpaid_confirmed").booleanValue();
        var due=dues.lock(userId,dueId);
        if(!"ACTIVE".equals(due.loanStatus())||due.required()==0||dues.allocationCount(userId,due.loanId(),dueId)>0)
            throw DueConfirmationRepository.conflict();
        if(!due.date().isBefore(LocalDate.now(clock)))throw new ApiException(HttpStatus.BAD_REQUEST,
            "DUE_CONFIRMATION_VALIDATION_ERROR","기한 경과한 미기록 회차만 미납 확인할 수 있습니다.");
        var budget=budgets.getIfAvailable();if(budget==null)throw ApiException.unavailable();
        dues.confirm(userId,due,confirmed);
        budget.recalculateFrom(userId,YearMonth.from(due.date()));
        revisions.bump(userId,"DUE_CONFIRMATION_RECORDED");
        String status=confirmed?"UNPAID":"UNRECORDED";
        var response=new DueConfirmation(dueId,due.loanId(),due.date(),due.required(),0,due.required(),confirmed,status,status,true);
        return new StoredResponse(200,mapper.valueToTree(ApiResponse.success("DUE_CONFIRMATION_SUCCESS","정기 납부 미납 여부 확인 성공",response)));
    }
}
