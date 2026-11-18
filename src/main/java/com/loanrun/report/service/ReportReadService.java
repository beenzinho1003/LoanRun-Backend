package com.loanrun.report.service;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.loanrun.common.contract.*;
import com.loanrun.common.error.ApiException;
import com.loanrun.report.domain.ReportJob;
import com.loanrun.report.dto.ReportReadDtos.*;
import com.loanrun.report.repository.ReportJobRepository;
import com.loanrun.user.repository.DemoUserRepository;
import java.math.*;
import java.time.*;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
@Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
public class ReportReadService {
    private final ReportJobRepository jobs;private final DemoUserRepository users;private final RevisionPort revisions;
    private final ComparisonSettingsService comparison;private final ObjectProvider<LoanQueryPort> loans;private final Clock clock;private final ObjectMapper mapper;
    public ReportReadService(ReportJobRepository jobs,DemoUserRepository users,RevisionPort revisions,ComparisonSettingsService comparison,
            ObjectProvider<LoanQueryPort> loans,Clock clock,ObjectMapper mapper){
        this.jobs=jobs;this.users=users;this.revisions=revisions;this.comparison=comparison;this.loans=loans;this.clock=clock;this.mapper=mapper;
    }
    public Latest latest(long userId,Long loanId){
        requirePro(userId);
        if(loanId!=null){
            if(loanId<=0) throw ApiException.validation("loan_id","양의 ID가 필요합니다.");
            var port=loans.getIfAvailable();if(port==null) throw ApiException.unavailable();port.terms(userId,loanId,LocalDate.now(clock));
        }
        var found=jobs.latest(userId,loanId);if(found.isEmpty()) return new Latest(null,"NOT_GENERATED",null,false,null,null);
        var job=found.get();boolean stale=stale(job);JsonNode result=null;
        if(!stale&&job.status()==ReportJob.Status.COMPLETED){result=view(job);normalizeRate((ObjectNode)result,true);}
        return new Latest(job.id(),job.status().name(),time(job.generatedAt()),stale,result,time(job.generatedAt()));
    }
    public JsonNode get(long userId,long id){
        requirePro(userId);if(id<=0) throw ApiException.validation("report_id","양의 ID가 필요합니다.");return view(jobs.owned(userId,id));
    }
    private ObjectNode view(ReportJob job){
        boolean stale=stale(job);ObjectNode result;
        if(job.status()==ReportJob.Status.COMPLETED){
            if(job.resultPayload()==null) throw new IllegalStateException("Completed report has no stored result");
            try{var parsed=mapper.readTree(job.resultPayload());if(!parsed.isObject()) throw new IllegalStateException("Invalid stored report result");result=(ObjectNode)parsed;}
            catch(com.fasterxml.jackson.core.JsonProcessingException ex){throw new IllegalStateException("Invalid stored report result");}
            result.put("report_id",job.id());result.put("status",job.status().name());
            result.set("generated_at",mapper.valueToTree(time(job.generatedAt())));result.set("updated_at",mapper.valueToTree(time(job.generatedAt())));
            normalizeRate(result,false);
        }else{
            var error=job.status()==ReportJob.Status.FAILED?new Failure(job.errorCode()==null?"REPORT_GENERATION_FAILED":job.errorCode(),"리포트 생성에 실패했습니다. 잠시 후 다시 시도해 주세요."):null;
            result=mapper.valueToTree(new State(job.id(),job.status().name(),null,stale,null,null,null,List.of(),List.of(),error,null,false,"NONE"));
        }
        result.put("stale",stale);if(stale) result.put("strategy_available",false);
        if(!comparison.snapshot(job.userId()).consented()) result.putNull("comparison");
        return result;
    }
    private void normalizeRate(ObjectNode result,boolean latest){
        var pace=result.path("pace_analysis");var value=pace.path("monthly_principal_repayment_rate");
        if(!pace.isObject()||value.isMissingNode()||value.isNull()) return;
        try{
            BigDecimal rate=value.isNumber()?value.decimalValue():new BigDecimal(value.textValue());
            if(latest)((ObjectNode)pace).put("monthly_principal_repayment_rate",rate.toPlainString());
            else ((ObjectNode)pace).put("monthly_principal_repayment_rate",rate);
        }catch(NumberFormatException|NullPointerException ex){throw new IllegalStateException("Invalid stored principal rate");}
    }
    private boolean stale(ReportJob job){return job.revision()!=revisions.current(job.userId())||!job.basisMonth().equals(YearMonth.now(clock));}
    private OffsetDateTime time(Instant instant){return instant==null?null:instant.atZone(clock.getZone()).toOffsetDateTime();}
    private void requirePro(long userId){if(users.findById(userId).orElseThrow(ApiException::unavailable).getPlanType()!=PlanType.PRO)
        throw new ApiException(HttpStatus.FORBIDDEN,"PRO_REQUIRED","PRO 이용자만 사용할 수 있습니다.");}
}
