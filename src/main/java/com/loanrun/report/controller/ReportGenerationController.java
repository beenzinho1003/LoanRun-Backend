package com.loanrun.report.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.loanrun.common.api.ApiResponse;
import com.loanrun.common.contract.CurrentUserProvider;
import com.loanrun.common.error.ApiException;
import com.loanrun.common.validation.JsonPatch;
import com.loanrun.report.dto.ReportAccepted;
import com.loanrun.report.service.ReportGenerationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/reports")
public class ReportGenerationController {
    private final CurrentUserProvider currentUser;private final ReportGenerationService service;
    public ReportGenerationController(CurrentUserProvider currentUser,ReportGenerationService service){this.currentUser=currentUser;this.service=service;}
    @PostMapping("/") public ResponseEntity<ApiResponse<ReportAccepted>> generate(@RequestBody JsonNode body){
        currentUser.requirePro();JsonPatch.fields(body,"loan_id","force");Long loanId=null;
        if(body.hasNonNull("loan_id")){
            var id=body.get("loan_id");if(!id.isIntegralNumber()||!id.canConvertToLong()||id.longValue()<=0)throw ApiException.validation("loan_id","양의 ID가 필요합니다.");
            loanId=id.longValue();
        }
        boolean force=body.has("force")&&JsonPatch.bool(body,"force");
        var result=service.generate(currentUser.currentUserId(),loanId,force);
        return ResponseEntity.status(result.status()).body(ApiResponse.success("REPORT_GENERATE_ACCEPTED","리포트 생성 요청을 접수했습니다.",result.data()));
    }
}
