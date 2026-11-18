package com.loanrun.report.service;

import jakarta.annotation.PreDestroy;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.*;

@Component
@ConditionalOnProperty(name="loanrun.reports.worker-enabled",havingValue="true",matchIfMissing=true)
public class ReportDispatcher {
    private final ReportWorker worker;private final ThreadPoolTaskExecutor executor;
    public ReportDispatcher(ReportWorker worker){
        this.worker=worker;executor=new ThreadPoolTaskExecutor();executor.setCorePoolSize(1);executor.setMaxPoolSize(2);executor.setQueueCapacity(16);
        executor.setThreadNamePrefix("report-worker-");executor.setWaitForTasksToCompleteOnShutdown(true);executor.setAwaitTerminationSeconds(5);executor.initialize();
    }
    @TransactionalEventListener(phase=TransactionPhase.AFTER_COMMIT)
    public void requested(ReportGenerationService.Requested event){
        try{executor.execute(()->worker.run(event.reportId()));}catch(TaskRejectedException ex){worker.reject(event.reportId());}
    }
    @PreDestroy public void close(){executor.shutdown();}
}
