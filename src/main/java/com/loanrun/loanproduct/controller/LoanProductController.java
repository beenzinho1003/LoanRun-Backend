package com.loanrun.loanproduct.controller;

import com.loanrun.common.api.ApiResponse;
import com.loanrun.common.contract.CurrentUserProvider;
import com.loanrun.loanproduct.service.LoanProductService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/loan-products")
public class LoanProductController {
    private final CurrentUserProvider currentUser;private final LoanProductService service;
    public LoanProductController(CurrentUserProvider currentUser,LoanProductService service){this.currentUser=currentUser;this.service=service;}
    @GetMapping("/options/")public ApiResponse<LoanProductService.Options> options(@RequestParam(name="loan_type",required=false)String type,@RequestParam(name="loan_form",required=false)String form){
        currentUser.currentUserId();return ApiResponse.success("LOAN_PRODUCT_OPTIONS_SUCCESS","대출 종류·은행 목록 정보를 조회했습니다.",service.options(type,form));
    }
    @GetMapping("/")public ApiResponse<LoanProductService.Products> products(@RequestParam(name="loan_type",required=false)String type,@RequestParam(name="loan_form",required=false)String form,@RequestParam(name="institution_id")long institution){
        currentUser.currentUserId();return ApiResponse.success("LOAN_PRODUCT_LIST_SUCCESS","은행별 대출상품 목록 정보를 조회했습니다.",service.products(type,form,institution));
    }
}
