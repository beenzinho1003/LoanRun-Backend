package com.loanrun.report.dto;

import java.time.OffsetDateTime;

public record ReportAccepted(long reportId,String status,String pollUrl,OffsetDateTime generatedAt,String dataRevision){}
