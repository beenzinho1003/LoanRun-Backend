package com.loanrun.dashboard.controller;

import com.loanrun.common.api.ApiResponse;
import com.loanrun.common.contract.CurrentUserProvider;
import com.loanrun.dashboard.dto.DashboardDtos.Detail;
import com.loanrun.dashboard.service.DashboardService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {
    private final CurrentUserProvider user;private final DashboardService service;
    public DashboardController(CurrentUserProvider user,DashboardService service){this.user=user;this.service=service;}
    @GetMapping("/") public ApiResponse<Detail> read(@RequestParam(name="loan_id",required=false)Long loanId){
        return ApiResponse.success("DASHBOARD_DETAIL_SUCCESS","홈 종합 현황 정보를 조회했습니다.",service.read(user.currentUserId(),loanId));
    }
}
