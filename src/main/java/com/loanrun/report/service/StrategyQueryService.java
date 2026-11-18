package com.loanrun.report.service;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.loanrun.common.contract.*;
import com.loanrun.common.contract.FinanceModels.StrategyCandidate;
import com.loanrun.common.error.ApiException;
import com.loanrun.report.domain.ReportJob;
import com.loanrun.report.repository.*;
import java.time.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
@Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
public class StrategyQueryService implements StrategyQueryPort {
    private final ReportJobRepository jobs;private final ReportCandidateRepository candidates;private final UserAccessPort access;
    private final RevisionPort revisions;private final Clock clock;private final ObjectMapper json;
    public StrategyQueryService(ReportJobRepository jobs,ReportCandidateRepository candidates,UserAccessPort access,RevisionPort revisions,Clock clock,ObjectMapper json){
        this.jobs=jobs;this.candidates=candidates;this.access=access;this.revisions=revisions;this.clock=clock;this.json=json;
    }
    public JsonNode get(long userId,Long reportId){
        access.requirePro(userId);
        if(reportId!=null&&reportId<=0) throw ApiException.validation("report_id","양의 ID가 필요합니다.");
        var found=reportId==null?jobs.latestCompleted(userId):java.util.Optional.of(jobs.owned(userId,reportId));
        if(found.isEmpty()) return unavailable(null,false,"REPORT_NOT_READY");
        var job=found.get();boolean stale=stale(job);
        if(stale||job.status()!=ReportJob.Status.COMPLETED||job.strategyPayload()==null)
            return unavailable(job,stale,stale?"STALE":"REPORT_NOT_READY");
        var view=payload(job);view.put("report_id",job.id());view.put("stale",false);view.put("data_revision",revision(job));return view;
    }
    @Override public StrategyCandidate requireCurrentCandidate(long userId,long reportId,String version,String code,long revision){
        access.requirePro(userId);var job=jobs.owned(userId,reportId);
        if(stale(job)||revision!=job.revision()||job.status()!=ReportJob.Status.COMPLETED||job.strategyPayload()==null) throw staleCandidate();
        var view=payload(job);
        if(!view.path("strategy_available").asBoolean()||version==null||!version.equals(view.path("strategy_version").asText())) throw staleCandidate();
        var candidate=candidates.find(reportId,version,code).orElseThrow(this::staleCandidate);
        if(!candidate.feasible()||candidate.dataRevision()!=revision) throw staleCandidate();
        return candidate;
    }
    private boolean stale(ReportJob job){return job.revision()!=revisions.current(job.userId())||!job.basisMonth().equals(YearMonth.now(clock));}
    private String revision(ReportJob job){return "rev-"+job.revision()+"-"+job.basisMonth();}
    private ObjectNode payload(ReportJob job){
        try{var node=json.readTree(job.strategyPayload());if(node.isObject())return (ObjectNode)node;}
        catch(com.fasterxml.jackson.core.JsonProcessingException ex){throw new IllegalStateException("Invalid stored strategy result");}
        throw new IllegalStateException("Invalid stored strategy result");
    }
    private ObjectNode unavailable(ReportJob job,boolean stale,String reason){
        var view=json.createObjectNode().put("strategy_available",false).put("unavailable_reason",reason).put("stale",stale).put("strategy_type","NONE");
        if(job==null){view.putNull("report_id");view.putNull("data_revision");}else{view.put("report_id",job.id());view.put("data_revision",revision(job));}
        for(var name:java.util.List.of("strategy_version","current_strategy","situation","recommendation","fee_comparison_basis"))view.putNull(name);
        view.putArray("candidates");return view;
    }
    private ApiException staleCandidate(){return new ApiException(HttpStatus.CONFLICT,"STRATEGY_STALE","상환 전략이 변경되었습니다. 다시 조회해 주세요.");}
}
