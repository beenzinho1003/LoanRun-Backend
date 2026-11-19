package com.loanrun.payment.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.loanrun.common.contract.CurrentUserProvider;
import com.loanrun.payment.service.DueConfirmationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/payments/schedule")
public class DueConfirmationController {
    private final CurrentUserProvider currentUser;
    private final DueConfirmationService service;
    public DueConfirmationController(CurrentUserProvider currentUser,DueConfirmationService service) {
        this.currentUser=currentUser;this.service=service;
    }
    @PatchMapping("/{due_item_id}/confirmation/")
    public ResponseEntity<JsonNode> confirm(@PathVariable("due_item_id") long dueId,@RequestBody JsonNode body,
        @RequestHeader(name="Idempotency-Key",required=false) String key) {
        var result=service.confirm(currentUser.currentUserId(),dueId,body,key);
        return ResponseEntity.status(result.status()).body(result.body());
    }
}
