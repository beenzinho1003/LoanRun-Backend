package com.loanrun.report.dto;

public record ComparisonSettings(boolean consented,AgeGroup ageGroup,String sourceType,Integer incomeDecile) {
    public enum AgeGroup {AGE_20S,AGE_30S,AGE_40S,AGE_50_PLUS}
    public static ComparisonSettings defaults(){return new ComparisonSettings(false,null,"DEMO",null);}
    public record Updated(boolean consented,AgeGroup ageGroup,String sourceType,boolean regenerationRequired,Integer incomeDecile){}
}
