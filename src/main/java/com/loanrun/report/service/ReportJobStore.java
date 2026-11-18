package com.loanrun.report.service;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.loanrun.common.contract.*;
import com.loanrun.common.contract.FinanceModels.StrategyCandidate;
import com.loanrun.report.domain.ReportJob;
import com.loanrun.report.repository.ReportJobRepository;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Short DB phases only. No engine computation, worker dispatch or external service is invoked here. */
@Service
@Transactional(propagation=Propagation.REQUIRES_NEW)
public class ReportJobStore {
    public record Lease(ReportJob job){}
    private final ReportJobRepository jobs;private final UserMutationLock locks;private final RevisionPort revisions;private final Clock clock;private final ObjectMapper json;
    public ReportJobStore(ReportJobRepository jobs,UserMutationLock locks,RevisionPort revisions,Clock clock,ObjectMapper json){this.jobs=jobs;this.locks=locks;this.revisions=revisions;this.clock=clock;this.json=json;}
    public Optional<Lease> start(long id){
        var found=jobs.internal(id);if(found.isEmpty()) return Optional.empty();var job=found.get();locks.lock(job.userId());
        if(!jobs.start(job,clock.instant())) return Optional.empty();
        return jobs.internal(id).map(Lease::new);
    }
    public boolean complete(Lease lease,JsonNode result,JsonNode strategies,List<StrategyCandidate> candidates,String source){
        var found=running(lease);if(found.isEmpty()) return false;var job=found.get();
        if(result==null||!result.isObject()||source==null||!Set.of("MODEL","FALLBACK").contains(source)) throw new IllegalArgumentException("Invalid calculated report payload");
        for(var candidate:candidates) if(candidate.reportId()!=job.id()||candidate.dataRevision()!=job.revision()) throw new IllegalArgumentException("Candidate does not match report input");
        Instant now=clock.instant();var payload=(ObjectNode)result.deepCopy();var timestamp=json.valueToTree(now.atZone(clock.getZone()).toOffsetDateTime());
        payload.put("report_id",job.id());payload.put("status","COMPLETED");payload.set("generated_at",timestamp);payload.set("updated_at",timestamp);
        payload.put("stale",revisions.current(job.userId())!=job.revision()||!YearMonth.now(clock).equals(job.basisMonth()));
        if(!jobs.complete(job,payload.toString(),strategies==null?null:strategies.toString(),source,now)) return false;
        jobs.replaceCandidates(job,candidates);return true;
    }
    public boolean fail(Lease lease,String code){
        if(code==null||!code.matches("[A-Z0-9_]{1,64}")) throw new IllegalArgumentException("Use a fixed error code without exception details");
        var found=running(lease);return found.isPresent()&&jobs.fail(found.get(),code,clock.instant());
    }
    private Optional<ReportJob> running(Lease lease){
        locks.lock(lease.job().userId());
        return jobs.internal(lease.job().id()).filter(job->job.userId()==lease.job().userId()&&job.status()==ReportJob.Status.RUNNING&&job.updatedAt().equals(lease.job().updatedAt()));
    }
}
