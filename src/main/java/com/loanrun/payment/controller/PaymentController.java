package com.loanrun.payment.controller;

import com.loanrun.common.api.ApiResponse;
import com.loanrun.common.contract.CurrentUserProvider;
import com.loanrun.payment.dto.PaymentDetail;
import com.loanrun.payment.dto.PaymentList;
import com.loanrun.payment.service.PaymentReadService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/payments")
public class PaymentController {
    private final CurrentUserProvider currentUser;
    private final PaymentReadService service;
    public PaymentController(CurrentUserProvider currentUser,PaymentReadService service) {
        this.currentUser=currentUser;this.service=service;
    }

    @GetMapping("/")
    public ApiResponse<PaymentList> list(@RequestParam(name="loan_id",required=false) String loanId,
        @RequestParam(name="from",required=false) String from,@RequestParam(name="to",required=false) String to,
        @RequestParam(name="type",required=false) String type,@RequestParam(name="page",required=false) String page,
        @RequestParam(name="page_size",required=false) String pageSize) {
        return ApiResponse.success("PAYMENT_LIST_SUCCESS","상환 내역 목록 정보를 조회했습니다.",
            service.list(currentUser.currentUserId(),loanId,from,to,type,page,pageSize));
    }

    @GetMapping("/{payment_id}/")
    public ApiResponse<PaymentDetail> detail(@PathVariable("payment_id") long paymentId) {
        return ApiResponse.success("PAYMENT_DETAIL_SUCCESS","납부 기록 상세 정보를 조회했습니다.",
            service.detail(currentUser.currentUserId(),paymentId));
    }
}
