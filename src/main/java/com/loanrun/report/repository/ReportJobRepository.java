package com.loanrun.report.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loanrun.common.contract.FinanceModels.StrategyCandidate;
import com.loanrun.common.error.ApiException;
import com.loanrun.report.domain.ReportJob;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.*;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.*;

@Repository
public class ReportJobRepository {
    private final JdbcTemplate jdbc;private final ObjectMapper json;
    public ReportJobRepository(JdbcTemplate jdbc,ObjectMapper json){this.jdbc=jdbc;this.json=json;}
    private static Instant instant(ResultSet rs,String column)throws SQLException{var value=rs.getTimestamp(column);return value==null?null:value.toInstant();}
    private final RowMapper<ReportJob> mapper=(rs,n)->new ReportJob(rs.getLong("id"),rs.getLong("user_id"),rs.getObject("loan_id",Long.class),rs.getString("scope_key"),
        rs.getLong("data_revision"),YearMonth.from(rs.getDate("basis_month").toLocalDate()),ReportJob.Status.valueOf(rs.getString("status")),rs.getString("input_payload"),
        rs.getString("result_payload"),rs.getString("strategy_payload"),rs.getString("error_code"),rs.getString("explanation_source"),
        instant(rs,"created_at"),instant(rs,"updated_at"),instant(rs,"generated_at"),instant(rs,"next_retry_at"));
    public static String scope(Long loanId){return loanId==null?"HOUSEHOLD":"LOAN:"+loanId;}
    public Optional<ReportJob> internal(long id){return jdbc.query("SELECT * FROM report_jobs WHERE id=?",mapper,id).stream().findFirst();}
    public ReportJob owned(long userId,long id){return jdbc.query("SELECT * FROM report_jobs WHERE user_id=? AND id=?",mapper,userId,id).stream().findFirst()
        .orElseThrow(()->new ApiException(HttpStatus.NOT_FOUND,"REPORT_NOT_FOUND","상환 리포트를 찾을 수 없습니다."));}
    public Optional<ReportJob> current(long userId,Long loanId,long revision,YearMonth month){return jdbc.query(
        "SELECT * FROM report_jobs WHERE user_id=? AND scope_key=? AND data_revision=? AND basis_month=?",mapper,userId,scope(loanId),revision,java.sql.Date.valueOf(month.atDay(1))).stream().findFirst();}
    public Optional<ReportJob> latest(long userId,Long loanId){return jdbc.query("SELECT * FROM report_jobs WHERE user_id=? AND scope_key=? ORDER BY id DESC LIMIT 1",mapper,userId,scope(loanId)).stream().findFirst();}
    public Optional<ReportJob> latestCompleted(long userId){return jdbc.query("SELECT * FROM report_jobs WHERE user_id=? AND scope_key='HOUSEHOLD' AND status='COMPLETED' ORDER BY id DESC LIMIT 1",mapper,userId).stream().findFirst();}
    public Optional<ReportJob> latestCompleted(long userId,Long loanId){return jdbc.query("SELECT * FROM report_jobs WHERE user_id=? AND scope_key=? AND status='COMPLETED' ORDER BY id DESC LIMIT 1",mapper,userId,scope(loanId)).stream().findFirst();}
    public List<ReportJob> expiredBefore(Instant cutoff,int limit){return jdbc.query("SELECT * FROM report_jobs WHERE status IN ('PENDING','RUNNING') AND updated_at<? ORDER BY user_id,id LIMIT ?",
        mapper,Timestamp.from(cutoff),limit);}
    @Transactional(propagation=Propagation.MANDATORY)
    public boolean expire(ReportJob observed,Instant cutoff,Instant now){return jdbc.update("""
        UPDATE report_jobs SET status='FAILED',error_code='REPORT_GENERATION_TIMEOUT',updated_at=?,next_retry_at=?
        WHERE user_id=? AND id=? AND status IN ('PENDING','RUNNING') AND updated_at=? AND updated_at<?
        """,Timestamp.from(now),Timestamp.from(now.plusSeconds(60)),observed.userId(),observed.id(),Timestamp.from(observed.updatedAt()),Timestamp.from(cutoff))==1;}
    /** Caller holds the user's mutation lock and validates optional loan ownership. No worker is dispatched here. */
    @Transactional(propagation=Propagation.MANDATORY)
    public ReportJob create(long userId,Long loanId,long revision,YearMonth month,String input,Instant now){
        long id=jdbc.queryForObject("""
            INSERT INTO report_jobs(user_id,loan_id,scope_key,data_revision,basis_month,status,input_payload,created_at,updated_at)
            VALUES (?,?,?,?,?,'PENDING',?,?,?) RETURNING id
            """,Long.class,userId,loanId,scope(loanId),revision,java.sql.Date.valueOf(month.atDay(1)),input,Timestamp.from(now),Timestamp.from(now));
        return internal(id).orElseThrow(ApiException::unavailable);
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public boolean start(ReportJob job,Instant now){return jdbc.update("UPDATE report_jobs SET status='RUNNING',updated_at=GREATEST(CAST(? AS TIMESTAMPTZ),updated_at+INTERVAL '1 microsecond') WHERE user_id=? AND id=? AND status='PENDING'",
        Timestamp.from(now),job.userId(),job.id())==1;}
    @Transactional(propagation=Propagation.MANDATORY)
    public boolean complete(ReportJob job,String result,String strategies,String source,Instant now){return jdbc.update("""
        UPDATE report_jobs SET status='COMPLETED',result_payload=?,strategy_payload=?,explanation_source=?,generated_at=?,updated_at=?,error_code=NULL,next_retry_at=NULL
        WHERE user_id=? AND id=? AND status='RUNNING' AND updated_at=?
        """,result,strategies,source,Timestamp.from(now),Timestamp.from(now),job.userId(),job.id(),Timestamp.from(job.updatedAt()))==1;}
    @Transactional(propagation=Propagation.MANDATORY)
    public boolean fail(ReportJob job,String code,Instant now){return jdbc.update("""
        UPDATE report_jobs SET status='FAILED',error_code=?,updated_at=?,next_retry_at=? WHERE user_id=? AND id=? AND status='RUNNING' AND updated_at=?
        """,code,Timestamp.from(now),Timestamp.from(now.plusSeconds(60)),job.userId(),job.id(),Timestamp.from(job.updatedAt()))==1;}
    @Transactional(propagation=Propagation.MANDATORY)
    public boolean requeueFailed(long userId,long id,String input,Instant now){return jdbc.update("""
        UPDATE report_jobs SET status='PENDING',input_payload=?,updated_at=?,error_code=NULL,next_retry_at=NULL
        WHERE user_id=? AND id=? AND status='FAILED' AND next_retry_at<=?
        """,input,Timestamp.from(now),userId,id,Timestamp.from(now))==1;}
    @Transactional(propagation=Propagation.MANDATORY)
    public void replaceCandidates(ReportJob job,List<StrategyCandidate> candidates){
        jdbc.update("DELETE FROM strategy_candidates WHERE report_id=?",job.id());
        for(var c:candidates) jdbc.update("""
            INSERT INTO strategy_candidates(report_id,strategy_version,code,strategy_type,data_revision,computed_at,allocations_payload,simulation_payload,feasible)
            VALUES (?,?,?,?,?,?,?,?,?)
            """,job.id(),c.strategyVersion(),c.code(),c.type(),c.dataRevision(),Timestamp.from(c.computedAt()),encode(c.allocations()),encode(c.simulation()),c.feasible());
    }
    private String encode(Object value){try{return json.writeValueAsString(value);}catch(com.fasterxml.jackson.core.JsonProcessingException ex){throw new IllegalStateException("Cannot serialize calculated report");}}
}
