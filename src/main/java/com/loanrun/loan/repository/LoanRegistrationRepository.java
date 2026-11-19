package com.loanrun.loan.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loanrun.common.contract.FinanceModels.*;
import com.loanrun.loan.domain.PreparedLoan;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.stereotype.Repository;

@Repository
public class LoanRegistrationRepository {
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;
    public LoanRegistrationRepository(NamedParameterJdbcTemplate jdbc, ObjectMapper mapper) { this.jdbc=jdbc;this.mapper=mapper; }
    public long activeCount(long userId) {
        return jdbc.queryForObject("SELECT count(*) FROM loans WHERE user_id=:user AND status='ACTIVE'",Map.of("user",userId),Long.class);
    }
    public long insert(PreparedLoan prepared, Instant now) {
        var v=prepared.input();var t=v.terms();
        Map<String,Object> values=new LinkedHashMap<>();
        values.put("user_id",t.userId());values.put("product_id",v.productId());
        values.put("institution_name",v.institutionName());values.put("product_name",t.productName());
        values.put("loan_form","GENERAL");values.put("loan_type",t.loanType().name());
        values.put("repayment_method",t.method().name());values.put("original_principal",t.principalOriginal());
        values.put("remaining_principal",t.outstanding());values.put("annual_rate",t.annualRate());values.put("rate_type",t.rateType().name());
        values.put("rate_reset_months",v.rateResetMonths());values.put("next_rate_reset_date",v.nextRateResetDate());
        values.put("start_date",v.startDate());values.put("maturity_date",t.maturityDate());values.put("payment_day",t.dueDay());
        values.put("next_due_date",t.nextDueDate());values.put("contractual_monthly_payment",t.contractualNextAmount());
        values.put("base_anchor_amount",prepared.baseAnchor());values.put("fixed_principal",prepared.fixedPrincipal());
        values.put("grace_months",v.graceMonths());values.put("grace_end_date",t.graceEnd());values.put("post_grace_method",name(t.postGraceMethod()));
        values.put("prepayment_policy",name(t.prepaymentPolicy()));values.put("prepayment_fee_method",prepared.originalFeeMethod().name());
        values.put("prepayment_fee_rate",t.feeRate());values.put("fee_start_date",t.feeStartDate());values.put("fee_end_date",t.feeEndDate());
        values.put("fee_exempt",v.feeExempt());values.put("initial_savings_balance",t.initialSavingBalance());
        values.put("status",t.outstanding()==0?"PAID":"ACTIVE");values.put("snapshot_date",t.snapshotDate());values.put("snapshot_principal",t.outstanding());
        values.put("initial_plan_snapshot","{}");values.put("created_at",Timestamp.from(now));values.put("updated_at",Timestamp.from(now));
        String columns=String.join(",",values.keySet());
        String parameters=values.keySet().stream().map(k->k.equals("initial_plan_snapshot")?"CAST(:"+k+" AS JSONB)":":"+k).collect(java.util.stream.Collectors.joining(","));
        long id=jdbc.queryForObject("INSERT INTO loans("+columns+") VALUES ("+parameters+") RETURNING id",values,Long.class);
        var plan=new PlanSnapshot(id,true,t.maturityDate(),null,0,prepared.requiredSaving(),prepared.requiredSaving(),"MATURITY",null,t.nextDueDate(),1);
        jdbc.update("UPDATE loans SET initial_plan_snapshot=CAST(:plan AS JSONB) WHERE user_id=:user AND id=:id",
            Map.of("plan",json(plan),"user",t.userId(),"id",id));
        var params=new MapSqlParameterSource().addValue("user",t.userId()).addValue("id",id).addValue("date",t.snapshotDate())
            .addValue("amount",t.outstanding()).addValue("rate",t.annualRate()).addValue("now",Timestamp.from(now));
        jdbc.update("INSERT INTO loan_balance_snapshots(user_id,loan_id,effective_date,amount,reason,created_at) VALUES (:user,:id,:date,:amount,'REGISTRATION',:now)",params);
        jdbc.update("INSERT INTO loan_rate_histories(user_id,loan_id,annual_rate,effective_date,created_at) VALUES (:user,:id,:rate,:date,:now)",params);
        return id;
    }
    public void firstDue(long userId,long loanId,SimulationRow row,Long contractualAmount,long revision,Instant now) {
        var p=new MapSqlParameterSource().addValue("user",userId).addValue("loan",loanId).addValue("date",row.date())
            .addValue("principal",row.scheduledPrincipal()).addValue("interest",row.interest())
            .addValue("contractual",contractualAmount).addValue("required",contractualAmount==null?Math.addExact(row.scheduledPrincipal(),row.interest()):contractualAmount).addValue("revision",revision).addValue("now",Timestamp.from(now));
        jdbc.update("INSERT INTO due_items(user_id,loan_id,due_date,principal_amount,interest_amount,fee_amount,contractual_amount,required_amount,terms_revision,created_at) VALUES (:user,:loan,:date,:principal,:interest,0,:contractual,:required,:revision,:now)",p);
    }
    private String json(Object value) {
        try{return mapper.writeValueAsString(value);}catch(JsonProcessingException ex){throw new IllegalStateException("Cannot serialize registration plan");}
    }
    private static String name(Enum<?> value){return value==null?null:value.name();}
}
