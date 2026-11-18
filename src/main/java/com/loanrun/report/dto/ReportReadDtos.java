package com.loanrun.report.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;
import java.util.List;

public final class ReportReadDtos {
    private ReportReadDtos(){}
    public record Latest(Long reportId,String status,OffsetDateTime generatedAt,boolean stale,JsonNode report,OffsetDateTime updatedAt){}
    public record Failure(String code,String message){}
    public record State(long reportId,String status,OffsetDateTime generatedAt,boolean stale,String summary,JsonNode paceAnalysis,
        JsonNode comparison,List<JsonNode> payoffScenarios,List<JsonNode> suggestions,Failure error,OffsetDateTime updatedAt,
        boolean strategyAvailable,String strategyType){}
}
