package com.loanrun.dashboard.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.loanrun.common.contract.PlanType;
import com.loanrun.common.error.ApiException;
import com.loanrun.report.domain.ReportJob;
import java.time.*;
import org.springframework.http.HttpStatus;

/** Pure presentation policy. Callers must resolve server plan/revision and an owner-scoped report snapshot. */
public final class DashboardReportStatusPolicy {
    private DashboardReportStatusPolicy(){}
    public record Status(String status,Long reportId,boolean stale,boolean locked,OffsetDateTime updatedAt,@JsonProperty("is_new")boolean isNew){}
    public static Status read(long userId,PlanType plan,ReportJob latest,long revision,Clock clock){
        // Locked presentation does not reveal whether the owner previously generated a PRO report.
        if(plan==PlanType.FREE)return new Status("NOT_GENERATED",null,false,true,null,false);
        if(plan!=PlanType.PRO)throw ApiException.unavailable();
        if(latest==null)return new Status("NOT_GENERATED",null,false,false,null,false);
        if(latest.userId()!=userId)throw new ApiException(HttpStatus.NOT_FOUND,"REPORT_NOT_FOUND","상환 리포트를 찾을 수 없습니다.");
        Instant now=clock.instant();boolean stale=latest.revision()!=revision||!latest.basisMonth().equals(YearMonth.from(now.atZone(clock.getZone())));
        Instant generated=latest.generatedAt();boolean isNew=latest.status()==ReportJob.Status.COMPLETED&&!stale&&generated!=null
            &&!now.isBefore(generated)&&now.isBefore(generated.plus(Duration.ofHours(24)));
        return new Status(latest.status().name(),latest.id(),stale,false,generated==null?null:generated.atZone(clock.getZone()).toOffsetDateTime(),isNew);
    }
}
