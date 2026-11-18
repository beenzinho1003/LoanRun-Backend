package com.loanrun.report.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.loanrun.common.api.ApiResponse;
import com.loanrun.common.contract.CurrentUserProvider;
import com.loanrun.report.service.StrategyQueryService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/reports")
public class StrategyController {
    private final CurrentUserProvider user;private final StrategyQueryService service;
    public StrategyController(CurrentUserProvider user,StrategyQueryService service){this.user=user;this.service=service;}
    @GetMapping("/strategy/") public ApiResponse<JsonNode> get(@RequestParam(name="report_id",required=false)Long reportId){
        return ApiResponse.success("REPORT_STRATEGY_SUCCESS","상환 전략 정보를 조회했습니다.",service.get(user.currentUserId(),reportId));
    }
}
