package com.loanrun.report.service;

import com.loanrun.report.repository.ReportJobRepository;
import java.time.Clock;
import org.slf4j.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name="loanrun.reports.worker-enabled",havingValue="true",matchIfMissing=true)
public class ReportRecovery {
    private static final Logger log=LoggerFactory.getLogger(ReportRecovery.class);
    private final ReportJobRepository jobs;private final ReportJobStore store;private final Clock clock;private final long timeout;
    public ReportRecovery(ReportJobRepository jobs,ReportJobStore store,Clock clock,@Value("${loanrun.reports.timeout-seconds:600}")long timeout){
        if(timeout<1)throw new IllegalArgumentException("Report timeout must be positive");this.jobs=jobs;this.store=store;this.clock=clock;this.timeout=timeout;
    }
    @Scheduled(fixedDelayString="${loanrun.reports.recovery-interval-ms:60000}",initialDelayString="${loanrun.reports.recovery-interval-ms:60000}")
    public void scheduled(){try{recoverOnce();}catch(RuntimeException ex){log.error("Report recovery scan failed: {}",ex.getClass().getSimpleName());}}
    public int recoverOnce(){
        var cutoff=clock.instant().minusSeconds(timeout);int changed=0;
        for(var observed:jobs.expiredBefore(cutoff,100))try{if(store.expire(observed,cutoff))changed++;}
        catch(RuntimeException ex){log.error("Report recovery transition failed: {}",ex.getClass().getSimpleName());}
        return changed;
    }
}
