package com.loanrun.report.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loanrun.common.contract.UserAccessPort;
import com.loanrun.common.error.ApiException;
import com.loanrun.report.domain.ReportInput;
import java.util.*;
import org.slf4j.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class ReportWorker {
    private static final Logger log=LoggerFactory.getLogger(ReportWorker.class);
    private final ReportJobStore store;private final ObjectProvider<ReportCalculation> calculation;private final UserAccessPort access;private final ObjectMapper mapper;
    public ReportWorker(ReportJobStore store,ObjectProvider<ReportCalculation> calculation,UserAccessPort access,ObjectMapper mapper){this.store=store;this.calculation=calculation;this.access=access;this.mapper=mapper;}
    public boolean run(long reportId){
        if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Dispatch report work outside the admission transaction");
        Optional<ReportJobStore.Lease> found;
        try{found=store.start(reportId);}catch(RuntimeException ex){log.error("Report claim failed: {}",ex.getClass().getSimpleName());return false;}
        if(found.isEmpty())return false;var lease=found.get();var job=lease.job();
        try{
            access.requirePro(job.userId());var input=mapper.readValue(job.inputPayload(),ReportInput.class);
            if(input.userId()!=job.userId()||input.revision()!=job.revision()||!Objects.equals(input.loanId(),job.loanId())
                    ||!input.basisMonth().equals(job.basisMonth())||input.scenarios().household().userId()!=job.userId()
                    ||!input.scenarios().household().month().equals(job.basisMonth())
                    ||input.scenarios().household().loans().stream().anyMatch(loan->loan.userId()!=job.userId())){
                fail(lease,"REPORT_INPUT_INVALID");return false;
            }
            var evaluator=calculation.getIfAvailable();if(evaluator==null)throw ApiException.unavailable();
            var result=evaluator.calculate(input,job.id());
            return store.complete(lease,result.result(),result.strategies(),result.candidates(),result.explanationSource());
        }catch(ApiException ex){
            fail(lease,Set.of("PRO_REQUIRED","SERVICE_UNAVAILABLE","REPORT_INPUT_INCOMPLETE").contains(ex.code())?ex.code():"REPORT_GENERATION_FAILED");
        }catch(Exception ex){log.error("Report calculation failed: {}",ex.getClass().getSimpleName());fail(lease,"REPORT_GENERATION_FAILED");}
        return false;
    }
    public void reject(long reportId){
        try{store.start(reportId).ifPresent(lease->fail(lease,"REPORT_QUEUE_FULL"));}
        catch(RuntimeException ex){log.error("Report rejection persistence failed: {}",ex.getClass().getSimpleName());}
    }
    private void fail(ReportJobStore.Lease lease,String code){try{store.fail(lease,code);}catch(RuntimeException ex){log.error("Report failure persistence failed: {}",ex.getClass().getSimpleName());}}
}
