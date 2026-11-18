package com.loanrun.budget.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.loanrun.budget.service.MonthlyAllocationService;
import com.loanrun.common.contract.CurrentUserProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/budgets")
public class MonthlyAllocationController {
    private final CurrentUserProvider currentUser;
    private final MonthlyAllocationService service;
    public MonthlyAllocationController(CurrentUserProvider currentUser,MonthlyAllocationService service){this.currentUser=currentUser;this.service=service;}
    @PutMapping("/{year_month}/allocation/")
    public ResponseEntity<JsonNode> save(@PathVariable("year_month")String month,@RequestHeader(name="Idempotency-Key",required=false)String key,@RequestBody JsonNode body){
        var response=service.save(currentUser.currentUserId(),month,key,body);
        return ResponseEntity.status(response.status()).body(response.body());
    }
}
