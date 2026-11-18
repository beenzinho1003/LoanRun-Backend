package com.loanrun;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import com.loanrun.common.contract.PlanType;
import com.loanrun.common.error.ApiException;
import com.loanrun.dashboard.service.DashboardReportStatusPolicy;
import com.loanrun.report.domain.ReportJob;
import java.time.*;
import org.junit.jupiter.api.Test;

class DashboardReportStatusPolicyTest {
    final ZoneId zone=ZoneId.of("Asia/Seoul");final Instant now=Instant.parse("2026-11-18T03:00:00Z");
    ReportJob job(long owner,long revision,YearMonth month,ReportJob.Status status,Instant generated){return new ReportJob(42,owner,null,"HOUSEHOLD",revision,month,status,"{}",null,null,null,"FALLBACK",now,now,generated,null);}
    ReportJob completed(Instant generated){return job(7,3,YearMonth.of(2026,11),ReportJob.Status.COMPLETED,generated);}
    DashboardReportStatusPolicy.Status read(ReportJob job,Instant time){return DashboardReportStatusPolicy.read(7,PlanType.PRO,job,3,Clock.fixed(time,zone));}
    @Test void freeHidesAllReportMetadataEvenWhenACompletedSnapshotExists(){
        var status=DashboardReportStatusPolicy.read(7,PlanType.FREE,completed(now.minusSeconds(1)),3,Clock.fixed(now,zone));
        assertThat(status.locked()).isTrue();assertThat(status.reportId()).isNull();assertThat(status.updatedAt()).isNull();assertThat(status.isNew()).isFalse();
    }
    @Test void noReportIsUnlockedButNotGeneratedForPro(){var status=read(null,now);assertThat(status.status()).isEqualTo("NOT_GENERATED");assertThat(status.locked()).isFalse();assertThat(status.isNew()).isFalse();}
    @Test void currentCompletionHasNewBadgeAndLastSuccessKstTimestamp(){
        var generated=now.minusSeconds(60);var status=read(completed(generated),now);assertThat(status.isNew()).isTrue();assertThat(status.stale()).isFalse();
        assertThat(status.updatedAt()).isEqualTo(generated.atZone(zone).toOffsetDateTime());assertThat(status.updatedAt().getOffset()).isEqualTo(ZoneOffset.ofHours(9));
    }
    @Test void exactlyTwentyFourHoursIsExcludedButOneNanosecondBeforeIsIncluded(){
        var job=completed(now.minus(Duration.ofHours(24)));assertThat(read(job,now.minusNanos(1)).isNew()).isTrue();assertThat(read(job,now).isNew()).isFalse();
    }
    @Test void revisionOrBasisMonthChangeRemovesBadgeEvenInsideTheTimeWindow(){
        var changed=job(7,2,YearMonth.of(2026,11),ReportJob.Status.COMPLETED,now.minusSeconds(1));assertThat(read(changed,now).stale()).isTrue();assertThat(read(changed,now).isNew()).isFalse();
        var monthBoundary=Instant.parse("2026-10-31T15:00:00Z");var old=job(7,3,YearMonth.of(2026,10),ReportJob.Status.COMPLETED,monthBoundary.minusSeconds(1));
        assertThat(read(old,monthBoundary).stale()).isTrue();assertThat(read(old,monthBoundary).isNew()).isFalse();
    }
    @Test void clockRollbackNeverMarksAFutureCompletionNew(){assertThat(read(completed(now.plusSeconds(1)),now).isNew()).isFalse();}
    @Test void incompleteStatesHaveNoNewBadgeAndNoInventedSuccessTimestamp(){
        for(var state:new ReportJob.Status[]{ReportJob.Status.PENDING,ReportJob.Status.RUNNING,ReportJob.Status.FAILED}){
            var result=read(job(7,3,YearMonth.of(2026,11),state,null),now);assertThat(result.status()).isEqualTo(state.name());assertThat(result.isNew()).isFalse();assertThat(result.updatedAt()).isNull();
        }
    }
    @Test void foreignSnapshotFailsAndJsonKeepsTheExactIsNewKey(){
        assertThatThrownBy(()->read(job(8,3,YearMonth.of(2026,11),ReportJob.Status.COMPLETED,now),now)).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.status().value()).isEqualTo(404));
        var json=new ObjectMapper().findAndRegisterModules().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).valueToTree(read(completed(now),now));
        assertThat(json.path("is_new").asBoolean()).isTrue();assertThat(json.has("new")).isFalse();
    }
}
