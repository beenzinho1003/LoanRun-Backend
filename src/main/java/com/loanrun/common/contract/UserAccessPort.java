package com.loanrun.common.contract;

/** Internal services use explicit user IDs; HTTP entry points resolve the session before calling them. */
public interface UserAccessPort {
    PlanType planType(long userId);
    void requirePro(long userId);
}
