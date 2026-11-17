package com.loanrun.budget.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.loanrun.budget.dto.FinanceProfileResponse;
import com.loanrun.budget.service.FinanceProfileService;
import com.loanrun.common.api.ApiResponse;
import com.loanrun.common.contract.CurrentUserProvider;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/budgets")
public class FinanceProfileController {
    private final CurrentUserProvider currentUser;
    private final FinanceProfileService service;
    public FinanceProfileController(CurrentUserProvider currentUser, FinanceProfileService service) { this.currentUser = currentUser; this.service = service; }
    @GetMapping("/profile/")
    public ApiResponse<FinanceProfileResponse> get() {
        return ApiResponse.success("FINANCE_PROFILE_DETAIL_SUCCESS", "기본 재정정보 정보를 조회했습니다.", service.get(currentUser.currentUserId()));
    }
    @PutMapping("/profile/")
    public ApiResponse<FinanceProfileResponse> save(@RequestBody JsonNode body) {
        return ApiResponse.success("FINANCE_PROFILE_SAVE_SUCCESS", "기본 재정정보 등록·수정 처리를 완료했습니다.", service.save(currentUser.currentUserId(), body));
    }
}
