package com.loanrun.loan.controller;

import com.loanrun.common.api.ApiResponse;
import com.loanrun.common.contract.CurrentUserProvider;
import com.loanrun.loan.dto.LoanRateHistory;
import com.loanrun.loan.service.LoanRateHistoryService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/loans")
public class LoanRateHistoryController {
    private final CurrentUserProvider currentUser;
    private final LoanRateHistoryService service;
    public LoanRateHistoryController(CurrentUserProvider currentUser,LoanRateHistoryService service) {
        this.currentUser=currentUser;this.service=service;
    }

    @GetMapping("/{loan_id}/rate-history/")
    public ApiResponse<LoanRateHistory> history(@PathVariable("loan_id") long loanId) {
        return ApiResponse.success("LOAN_RATE_HISTORY_SUCCESS","대출 금리 변경 이력 정보를 조회했습니다.",
            service.history(currentUser.currentUserId(),loanId));
    }
}
