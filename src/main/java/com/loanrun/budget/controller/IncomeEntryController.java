package com.loanrun.budget.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.loanrun.budget.service.IncomeEntryService;
import com.loanrun.common.contract.CurrentUserProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/income-entries")
public class IncomeEntryController {
    private final CurrentUserProvider currentUser;
    private final IncomeEntryService service;
    public IncomeEntryController(CurrentUserProvider currentUser, IncomeEntryService service) { this.currentUser=currentUser;this.service=service; }
    @PostMapping("/")
    public ResponseEntity<JsonNode> create(@RequestHeader(name="Idempotency-Key",required=false)String key,@RequestBody JsonNode body){
        var response=service.create(currentUser.currentUserId(),key,body);
        return ResponseEntity.status(response.status()).body(response.body());
    }
}
