package com.loanrun.report.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loanrun.common.contract.*;
import com.loanrun.common.error.ApiException;
import com.loanrun.report.domain.ReportJob;
import com.loanrun.report.dto.ReportAccepted;
import com.loanrun.report.repository.*;
import java.time.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReportGenerationService {
    public record Requested(long reportId){}
    public record Generation(int status,ReportAccepted data){}
    private final ReportJobRepository jobs;private final ReportAttemptRepository attempts;private final ReportInputService inputs;
    private final UserMutationLock locks;private final UserAccessPort access;private final RevisionPort revisions;
    private final ObjectMapper mapper;private final Clock clock;private final ApplicationEventPublisher events;private final int limit;
    public ReportGenerationService(ReportJobRepository jobs,ReportAttemptRepository attempts,ReportInputService inputs,UserMutationLock locks,
            UserAccessPort access,RevisionPort revisions,ObjectMapper mapper,Clock clock,ApplicationEventPublisher events,
            @Value("${loanrun.reports.max-attempts-per-hour:20}")int limit){
        if(limit<1)throw new IllegalArgumentException("Report attempt limit must be positive");
        this.jobs=jobs;this.attempts=attempts;this.inputs=inputs;this.locks=locks;this.access=access;this.revisions=revisions;
        this.mapper=mapper;this.clock=clock;this.events=events;this.limit=limit;
    }
    @Transactional
    public Generation generate(long userId,Long loanId,boolean force){
        locks.lock(userId);access.requirePro(userId);long revision=revisions.current(userId);var month=YearMonth.now(clock);
        var existing=jobs.current(userId,loanId,revision,month);
        if(existing.isPresent()){
            var job=existing.get();
            if(force&&job.status()!=ReportJob.Status.FAILED)throw ApiException.validation("force","실패한 리포트만 재시도할 수 있습니다.");
            if(job.status()!=ReportJob.Status.FAILED||!force)return response(job);
            if(job.nextRetryAt()!=null&&clock.instant().isBefore(job.nextRetryAt()))throw limited();
        }else if(force)throw ApiException.validation("force","실패한 리포트만 재시도할 수 있습니다.");
        if(attempts.countSince(userId,clock.instant().minus(Duration.ofHours(1)))>=limit)throw limited();
        var captured=inputs.capture(userId,loanId,revision);String payload;
        try{payload=mapper.writeValueAsString(captured);}catch(com.fasterxml.jackson.core.JsonProcessingException ex){throw new IllegalStateException("Cannot serialize captured report input");}
        attempts.record(userId,clock.instant());ReportJob job;
        if(existing.isPresent()){
            if(!jobs.requeueFailed(userId,existing.get().id(),payload,clock.instant()))throw limited();
            job=jobs.owned(userId,existing.get().id());
        }else job=jobs.create(userId,loanId,revision,month,payload,clock.instant());
        events.publishEvent(new Requested(job.id()));
        return response(job);
    }
    private Generation response(ReportJob job){
        int status=job.status()==ReportJob.Status.PENDING||job.status()==ReportJob.Status.RUNNING?202:200;
        return new Generation(status,new ReportAccepted(job.id(),job.status().name(),"/api/reports/"+job.id()+"/",
            job.generatedAt()==null?null:job.generatedAt().atZone(clock.getZone()).toOffsetDateTime(),"rev-"+job.revision()+"-"+job.basisMonth()));
    }
    private ApiException limited(){return new ApiException(HttpStatus.TOO_MANY_REQUESTS,"RATE_LIMIT_EXCEEDED","요청이 너무 많습니다. 잠시 후 다시 시도해 주세요.");}
}
