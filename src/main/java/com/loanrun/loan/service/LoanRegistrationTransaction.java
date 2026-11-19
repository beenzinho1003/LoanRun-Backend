package com.loanrun.loan.service;

import com.fasterxml.jackson.databind.*;
import com.loanrun.common.api.ApiResponse;
import com.loanrun.common.contract.*;
import com.loanrun.common.contract.FinanceModels.*;
import com.loanrun.common.error.ApiException;
import com.loanrun.common.idempotency.IdempotencyService;
import com.loanrun.common.idempotency.IdempotencyService.StoredResponse;
import com.loanrun.loan.domain.PreparedLoan;
import com.loanrun.loan.dto.LoanCreated;
import com.loanrun.loan.repository.LoanRegistrationRepository;
import java.math.*;
import java.time.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LoanRegistrationTransaction {
    private final UserMutationLock locks;
    private final RevisionPort revisions;
    private final IdempotencyService idempotency;
    private final ObjectProvider<BudgetRecalculationPort> budgets;
    private final LoanRegistrationRepository loans;
    private final ObjectMapper mapper;
    private final Clock clock;
    public LoanRegistrationTransaction(UserMutationLock locks,RevisionPort revisions,IdempotencyService idempotency,
        ObjectProvider<BudgetRecalculationPort> budgets,LoanRegistrationRepository loans,ObjectMapper mapper,Clock clock) {
        this.locks=locks;this.revisions=revisions;this.idempotency=idempotency;this.budgets=budgets;this.loans=loans;this.mapper=mapper;this.clock=clock;
    }
    /** An absent replay rolls back this short probe, releasing the lock before catalog preparation. */
    public static final class NeedsPreparation extends RuntimeException {}
    @Transactional
    public StoredResponse replay(long userId,JsonNode body,String key) {
        return idempotency.execute(userId,"POST","/api/loans/",key,body,"IDEMPOTENCY_CONFLICT",()->{throw new NeedsPreparation();});
    }
    @Transactional
    public StoredResponse create(long userId,JsonNode body,String key,PreparedLoan prepared) {
        if(prepared.input().terms().userId()!=userId)throw new IllegalArgumentException("Prepared owner mismatch");
        locks.lock(userId);
        if(key!=null)return idempotency.execute(userId,"POST","/api/loans/",key,body,"IDEMPOTENCY_CONFLICT",()->save(userId,prepared));
        return save(userId,prepared);
    }
    private StoredResponse save(long userId,PreparedLoan prepared) {
        if(loans.activeCount(userId)>=7 && prepared.input().terms().outstanding()>0)
            throw new ApiException(HttpStatus.CONFLICT,"LOAN_LIMIT_EXCEEDED","등록 가능한 대출 수를 초과했습니다.");
        var budget=budgets.getIfAvailable();if(budget==null)throw ApiException.unavailable();
        var v=prepared.input();var t=v.terms();var now=clock.instant();
        // A prepared request crossing KST midnight must be validated again, not saved under an old snapshot.
        if(!t.snapshotDate().equals(LocalDate.now(clock)))throw ApiException.validation("non_field_errors","기준일이 변경되었습니다. 다시 등록해 주세요.");
        long id=loans.insert(prepared,now);
        if(!prepared.schedule().isEmpty())loans.firstDue(userId,id,prepared.schedule().getFirst(),t.contractualNextAmount(),Math.addExact(revisions.current(userId),1),now);
        budget.recalculateFrom(userId,YearMonth.from(t.snapshotDate()));
        revisions.bump(userId,"LOAN_CREATED");
        var row=prepared.schedule().isEmpty()?null:prepared.schedule().getFirst();
        long estimate=row==null?0:Math.addExact(row.scheduledPrincipal(),row.interest());
        long paid=t.principalOriginal()-t.outstanding();
        var progress=BigDecimal.valueOf(paid).multiply(BigDecimal.valueOf(100)).divide(BigDecimal.valueOf(t.principalOriginal()),2,RoundingMode.HALF_UP);
        var response=new LoanCreated(id,v.productId(),v.institutionName(),t.productName(),t.loanType(),"GENERAL",t.method(),t.principalOriginal(),t.outstanding(),
            t.annualRate(),t.rateType(),v.rateResetMonths(),v.nextRateResetDate(),v.startDate(),t.maturityDate(),t.dueDay(),v.graceMonths(),t.postGraceMethod(),
            t.contractualNextAmount(),estimate,paid,progress,t.outstanding()==0?LoanStatus.PAID:LoanStatus.ACTIVE,t.snapshotDate(),
            t.method()==RepaymentMethod.BULLET?"LUMP_SUM":"INSTALLMENT","MANUAL",now.atZone(ZoneId.of("Asia/Seoul")).toOffsetDateTime(),false,
            t.rateType()==RateType.VARIABLE?"MANUAL_REQUIRED":"NOT_APPLICABLE",t.prepaymentPolicy(),prepared.originalFeeMethod(),t.feeRate(),t.feeStartDate(),t.feeEndDate(),
            v.feeExempt(),t.currentSavingBalance(),prepared.requiredSaving(),t.snapshotDate(),t.outstanding());
        return new StoredResponse(201,mapper.valueToTree(ApiResponse.success("LOAN_CREATE_SUCCESS","대출 정보를 등록했습니다.",response)));
    }
}
