package com.loanrun.budget.controller;

import com.loanrun.budget.dto.BudgetDtos.Monthly;
import com.loanrun.budget.service.MonthlyBudgetReader;
import com.loanrun.budget.service.MonthlyBudgetWriter;
import com.fasterxml.jackson.databind.JsonNode;
import com.loanrun.common.api.ApiResponse;
import com.loanrun.common.contract.CurrentUserProvider;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/budgets")
public class MonthlyBudgetController {
    private final CurrentUserProvider currentUser;
    private final MonthlyBudgetReader service;
    private final MonthlyBudgetWriter writer;
    public MonthlyBudgetController(CurrentUserProvider currentUser,MonthlyBudgetReader service,MonthlyBudgetWriter writer){this.currentUser=currentUser;this.service=service;this.writer=writer;}
    @GetMapping("/{year_month}/") public ApiResponse<Monthly> get(@PathVariable("year_month")String month){
        return ApiResponse.success("MONTHLY_BUDGET_DETAIL_SUCCESS","월별 예산·재정정보 정보를 조회했습니다.",service.get(currentUser.currentUserId(),month));
    }
    @PutMapping("/{year_month}/") public ApiResponse<Monthly> save(@PathVariable("year_month")String month,@RequestBody JsonNode body){
        return ApiResponse.success("MONTHLY_BUDGET_SAVE_SUCCESS","월별 재정정보를 저장했습니다.",writer.save(currentUser.currentUserId(),month,body));
    }
}
