package com.loanrun.risk.controller;

import com.loanrun.common.api.ApiResponse;
import com.loanrun.common.contract.CurrentUserProvider;
import com.loanrun.common.error.ApiException;
import com.loanrun.risk.dto.RiskDtos.Detail;
import com.loanrun.risk.service.RiskDetailService;
import java.time.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/risks")
public class RiskDetailController {
    private final CurrentUserProvider user;private final RiskDetailService service;private final Clock clock;
    public RiskDetailController(CurrentUserProvider user,RiskDetailService service,Clock clock){this.user=user;this.service=service;this.clock=clock;}
    @GetMapping("/") public ApiResponse<Detail> read(@RequestParam(name="loan_id",required=false)Long loanId,@RequestParam(name="month",required=false)String month){
        user.requirePro();YearMonth basis=YearMonth.now(clock);
        if(month!=null){
            if(!month.matches("[0-9]{4}-(0[1-9]|1[0-2])"))throw ApiException.validation("month","YYYY-MM 형식이어야 합니다.");
            basis=YearMonth.parse(month);if(basis.getYear()<1)throw ApiException.validation("month","유효한 연도가 필요합니다.");
        }
        return ApiResponse.success("RISK_DETAIL_SUCCESS","재정 위험 분석 정보를 조회했습니다.",service.read(user.currentUserId(),loanId,basis));
    }
}
