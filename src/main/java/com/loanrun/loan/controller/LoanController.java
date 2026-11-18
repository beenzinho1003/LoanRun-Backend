package com.loanrun.loan.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.loanrun.common.api.ApiResponse;
import com.loanrun.common.contract.CurrentUserProvider;
import com.loanrun.loan.dto.LoanPreview;
import com.loanrun.loan.service.LoanPreviewService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/loans")
public class LoanController {
    private final CurrentUserProvider currentUser;
    private final LoanPreviewService service;
    public LoanController(CurrentUserProvider currentUser, LoanPreviewService service) { this.currentUser = currentUser; this.service = service; }
    @PostMapping("/preview/")
    public ApiResponse<LoanPreview> preview(@RequestBody JsonNode body) {
        return ApiResponse.success("LOAN_PREVIEW_SUCCESS", "기본 월 상환액을 계산했습니다.", service.preview(currentUser.currentUserId(), body));
    }
}
