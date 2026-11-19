package com.loanrun.risk.domain;

import java.time.*;

public final class RiskHistoryModels {
    private RiskHistoryModels(){}
    public enum Origin {PROFILE,MONTHLY_BUDGET,INCOME_ENTRY}
    public enum Kind {INCOME,EXPENSE}
    public record Change(long userId,long revision,Origin origin,YearMonth month,LocalDate eventDate,Kind kind,
        Long beforeAmount,Long afterAmount,boolean cashImpactKnown,Instant createdAt){}
    public record Assessment(long userId,YearMonth month,long revision,LocalDate asOf,String inputHash,
        String householdPayload,String loanPayloads,Instant createdAt){}
    public record InputVersion(long userId,long revision,Origin origin,YearMonth month,String beforePayload,String afterPayload,Instant createdAt){}
}
