package com.loanrun.onboarding.controller;

import com.loanrun.common.api.ApiResponse;
import com.loanrun.common.contract.CurrentUserProvider;
import com.loanrun.onboarding.dto.OnboardingResponse;
import com.loanrun.onboarding.service.OnboardingService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/onboarding")
public class OnboardingController {
    private final CurrentUserProvider currentUser;
    private final OnboardingService service;
    public OnboardingController(CurrentUserProvider currentUser, OnboardingService service) { this.currentUser = currentUser; this.service = service; }
    @GetMapping("/")
    public ApiResponse<OnboardingResponse> get() {
        return ApiResponse.success("ONBOARDING_DETAIL_SUCCESS", "온보딩 진행 정보를 조회했습니다.", service.get(currentUser.currentUserId()));
    }
    @PostMapping("/complete/")
    public ApiResponse<OnboardingService.CompletionResponse> complete() {
        return ApiResponse.success("ONBOARDING_COMPLETE_SUCCESS", "온보딩을 완료했습니다.", service.complete(currentUser.currentUserId()));
    }
}
