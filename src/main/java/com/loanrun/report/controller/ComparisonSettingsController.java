package com.loanrun.report.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.loanrun.common.api.ApiResponse;
import com.loanrun.common.contract.CurrentUserProvider;
import com.loanrun.report.dto.ComparisonSettings;
import com.loanrun.report.service.ComparisonSettingsService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/reports")
public class ComparisonSettingsController {
    private final CurrentUserProvider currentUser;private final ComparisonSettingsService service;
    public ComparisonSettingsController(CurrentUserProvider currentUser,ComparisonSettingsService service){this.currentUser=currentUser;this.service=service;}
    @GetMapping("/comparison-settings/") public ApiResponse<ComparisonSettings> get(){
        return ApiResponse.success("REPORT_COMPARISON_SETTINGS_SUCCESS","또래 비교 기준·동의 정보를 조회했습니다.",service.get(currentUser.currentUserId()));
    }
    @PatchMapping("/comparison-settings/") public ApiResponse<ComparisonSettings.Updated> patch(@RequestBody JsonNode body){
        return ApiResponse.success("REPORT_COMPARISON_SETTINGS_UPDATE_SUCCESS","비교 기준 및 동의를 저장했습니다.",service.patch(currentUser.currentUserId(),body));
    }
}
