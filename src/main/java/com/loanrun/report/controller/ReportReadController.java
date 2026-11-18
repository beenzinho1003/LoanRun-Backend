package com.loanrun.report.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.loanrun.common.api.ApiResponse;
import com.loanrun.common.contract.CurrentUserProvider;
import com.loanrun.report.dto.ReportReadDtos.Latest;
import com.loanrun.report.service.ReportReadService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/reports")
public class ReportReadController {
    private final CurrentUserProvider currentUser;private final ReportReadService service;
    public ReportReadController(CurrentUserProvider currentUser,ReportReadService service){this.currentUser=currentUser;this.service=service;}
    @GetMapping("/latest/") public ApiResponse<Latest> latest(@RequestParam(name="loan_id",required=false)Long loanId){
        return ApiResponse.success("REPORT_LATEST_SUCCESS","최신 상환 리포트 정보를 조회했습니다.",service.latest(currentUser.currentUserId(),loanId));
    }
    @GetMapping("/{report_id}/") public ApiResponse<JsonNode> get(@PathVariable("report_id")long id){
        return ApiResponse.success("REPORT_DETAIL_SUCCESS","상환 리포트 결과·상태 정보를 조회했습니다.",service.get(currentUser.currentUserId(),id));
    }
}
