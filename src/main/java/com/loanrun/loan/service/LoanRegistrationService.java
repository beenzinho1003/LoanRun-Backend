package com.loanrun.loan.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.loanrun.common.contract.*;
import com.loanrun.common.contract.FinanceModels.*;
import com.loanrun.common.error.ApiException;
import com.loanrun.common.idempotency.IdempotencyService.StoredResponse;
import com.loanrun.finance.engine.RepaymentMath;
import com.loanrun.loan.domain.PreparedLoan;
import java.util.*;
import org.springframework.stereotype.Service;

/** External catalog resolution and pure calculations finish before the financial transaction. */
@Service
public class LoanRegistrationService {
    private final LoanInputValidator validator;
    private final RepaymentEngine engine;
    private final LoanRegistrationTransaction transactions;
    public LoanRegistrationService(LoanInputValidator validator,RepaymentEngine engine,LoanRegistrationTransaction transactions) {
        this.validator=validator;this.engine=engine;this.transactions=transactions;
    }
    public StoredResponse create(long userId,JsonNode body,String key) {
        if(key!=null)try{return transactions.replay(userId,body,key);}catch(LoanRegistrationTransaction.NeedsPreparation ignored){}
        try {
            var v=validator.validate(userId,body);var t=v.terms();
            var schedule=engine.simulate(new SimulationInput(List.of(t),t.snapshotDate(),1200,Map.of(),Map.of(),null,"NONE")).rows();
            var independent=new LoanTerms(t.loanId(),t.userId(),t.productName(),t.loanType(),t.principalOriginal(),t.outstanding(),t.snapshotDate(),t.annualRate(),t.rateType(),t.method(),
                t.graceEnd(),t.postGraceMethod(),t.nextDueDate(),t.dueDay(),t.maturityDate(),null,null,null,t.prepaymentPolicy(),t.feeMethod(),t.feeRate(),t.feeStartDate(),t.feeEndDate(),
                t.initialSavingBalance(),t.currentSavingBalance(),t.status(),t.rateResetDate());
            var base=engine.simulate(new SimulationInput(List.of(independent),t.snapshotDate(),1200,Map.of(),Map.of(),null,"NONE")).rows().stream()
                .filter(r->t.graceEnd()==null||!r.date().isBefore(t.graceEnd())).findFirst().orElse(null);
            boolean principalMethod=t.method()==RepaymentMethod.EQUAL_PRINCIPAL||t.postGraceMethod()==RepaymentMethod.EQUAL_PRINCIPAL;
            Long anchor=t.method()==RepaymentMethod.BULLET||base==null||principalMethod?null:Math.addExact(base.scheduledPrincipal(),base.interest());
            Long fixed=principalMethod&&base!=null?base.scheduledPrincipal():null;
            long saving=t.method()==RepaymentMethod.BULLET?RepaymentMath.requiredSaving(t.outstanding(),t.currentSavingBalance(),v.remainingMonths()):0;
            FeeMethod fee=body.has("prepayment_fee_method")?FeeMethod.valueOf(body.get("prepayment_fee_method").textValue()):FeeMethod.UNKNOWN;
            return transactions.create(userId,body,key,new PreparedLoan(v,fee,anchor,fixed,saving,schedule));
        } catch(ArithmeticException ex){throw ApiException.validation("non_field_errors","계산 가능한 금액 범위를 초과했습니다.");}
    }
}
