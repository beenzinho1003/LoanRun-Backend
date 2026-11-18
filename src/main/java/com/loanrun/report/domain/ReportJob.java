package com.loanrun.report.domain;

import java.time.*;

public record ReportJob(long id,long userId,Long loanId,String scopeKey,long revision,YearMonth basisMonth,Status status,
    String inputPayload,String resultPayload,String strategyPayload,String errorCode,String explanationSource,
    Instant createdAt,Instant updatedAt,Instant generatedAt,Instant nextRetryAt) {
    public enum Status {PENDING,RUNNING,COMPLETED,FAILED}
}
