package com.loanrun.risk.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loanrun.common.error.ApiException;
import com.loanrun.risk.domain.RiskHistoryModels.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.*;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.*;

/** Append-only owner-scoped storage. SQL proposal is deliberately not a production migration yet. */
@Repository
public class RiskHistoryRepository {
    private final JdbcTemplate jdbc;private final ObjectMapper json;
    public RiskHistoryRepository(JdbcTemplate jdbc,ObjectMapper json){this.jdbc=jdbc;this.json=json;}
    private static final RowMapper<Change> CHANGES=(r,n)->new Change(r.getLong("user_id"),r.getLong("data_revision"),Origin.valueOf(r.getString("origin")),
        YearMonth.from(r.getDate("basis_month").toLocalDate()),r.getDate("event_date").toLocalDate(),Kind.valueOf(r.getString("kind")),
        r.getObject("before_amount",Long.class),r.getObject("after_amount",Long.class),r.getBoolean("cash_impact_known"),r.getTimestamp("created_at").toInstant());
    private static final RowMapper<Assessment> ASSESSMENTS=(r,n)->new Assessment(r.getLong("user_id"),YearMonth.from(r.getDate("basis_month").toLocalDate()),
        r.getLong("data_revision"),r.getDate("as_of").toLocalDate(),r.getString("input_hash"),r.getString("household_payload"),r.getString("loan_payloads"),r.getTimestamp("created_at").toInstant());
    private static final RowMapper<InputVersion> INPUTS=(r,n)->new InputVersion(r.getLong("user_id"),r.getLong("data_revision"),Origin.valueOf(r.getString("origin")),
        YearMonth.from(r.getDate("basis_month").toLocalDate()),r.getString("before_payload"),r.getString("after_payload"),r.getTimestamp("created_at").toInstant());
    @Transactional(propagation=Propagation.MANDATORY)
    public void appendInput(InputVersion input){
        int inserted=jdbc.update("""
            INSERT INTO finance_input_versions(user_id,data_revision,origin,basis_month,before_payload,after_payload,created_at)
            VALUES (?,?,?,?,?::jsonb,?::jsonb,?) ON CONFLICT(user_id,data_revision,origin) DO NOTHING
            """,input.userId(),input.revision(),input.origin().name(),date(input.month()),input.beforePayload(),input.afterPayload(),Timestamp.from(input.createdAt()));
        if(inserted==0){var saved=input(input.userId(),input.revision(),input.origin()).orElseThrow(ApiException::unavailable);
            if(!saved.month().equals(input.month())||!sameJson(saved.beforePayload(),input.beforePayload())||!sameJson(saved.afterPayload(),input.afterPayload()))throw conflict();}
    }
    public Optional<InputVersion> input(long userId,long revision,Origin origin){return jdbc.query(
        "SELECT * FROM finance_input_versions WHERE user_id=? AND data_revision=? AND origin=?",INPUTS,userId,revision,origin.name()).stream().findFirst();}
    @Transactional(propagation=Propagation.MANDATORY)
    public void append(Change change){
        int inserted=jdbc.update("""
            INSERT INTO finance_change_events(user_id,data_revision,origin,basis_month,event_date,kind,before_amount,after_amount,cash_impact_known,created_at)
            VALUES (?,?,?,?,?,?,?,?,?,?) ON CONFLICT(user_id,data_revision,origin,kind) DO NOTHING
            """,change.userId(),change.revision(),change.origin().name(),date(change.month()),java.sql.Date.valueOf(change.eventDate()),change.kind().name(),
            change.beforeAmount(),change.afterAmount(),change.cashImpactKnown(),Timestamp.from(change.createdAt()));
        if(inserted==0){
            var existing=jdbc.query("SELECT * FROM finance_change_events WHERE user_id=? AND data_revision=? AND origin=? AND kind=?",CHANGES,
                change.userId(),change.revision(),change.origin().name(),change.kind().name()).getFirst();
            // A retried write may have a later wall clock time; the original timestamp stays unchanged.
            if(!existing.month().equals(change.month())||!existing.eventDate().equals(change.eventDate())
                ||!Objects.equals(existing.beforeAmount(),change.beforeAmount())||!Objects.equals(existing.afterAmount(),change.afterAmount())
                ||existing.cashImpactKnown()!=change.cashImpactKnown())throw conflict();
        }
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public void append(Assessment assessment){
        int inserted=jdbc.update("""
            INSERT INTO risk_assessment_versions(user_id,basis_month,data_revision,as_of,input_hash,household_payload,loan_payloads,created_at)
            VALUES (?,?,?,?,?,?::jsonb,?::jsonb,?) ON CONFLICT(user_id,basis_month,data_revision) DO NOTHING
            """,assessment.userId(),date(assessment.month()),assessment.revision(),java.sql.Date.valueOf(assessment.asOf()),assessment.inputHash(),
            assessment.householdPayload(),assessment.loanPayloads(),Timestamp.from(assessment.createdAt()));
        if(inserted==0){
            var existing=jdbc.query("SELECT * FROM risk_assessment_versions WHERE user_id=? AND basis_month=? AND data_revision=?",ASSESSMENTS,
                assessment.userId(),date(assessment.month()),assessment.revision()).getFirst();
            if(!existing.inputHash().equals(assessment.inputHash())||!sameJson(existing.householdPayload(),assessment.householdPayload())
                ||!sameJson(existing.loanPayloads(),assessment.loanPayloads()))throw conflict();
        }
    }
    public List<Change> changes(long userId,LocalDate start,LocalDate end){return List.copyOf(jdbc.query(
        "SELECT * FROM finance_change_events WHERE user_id=? AND event_date>=? AND event_date<=? ORDER BY event_date,id",CHANGES,userId,java.sql.Date.valueOf(start),java.sql.Date.valueOf(end)));}
    public Optional<Assessment> latest(long userId,YearMonth month){return jdbc.query(
        "SELECT * FROM risk_assessment_versions WHERE user_id=? AND basis_month=? ORDER BY data_revision DESC LIMIT 1",ASSESSMENTS,userId,date(month)).stream().findFirst();}
    @Transactional(propagation=Propagation.MANDATORY)
    public void request(long userId,YearMonth month,long revision){jdbc.update("""
        INSERT INTO risk_assessment_requests(user_id,basis_month,data_revision,pending) VALUES (?,?,?,TRUE)
        ON CONFLICT(user_id,basis_month) DO UPDATE SET data_revision=GREATEST(risk_assessment_requests.data_revision,EXCLUDED.data_revision),pending=TRUE
        """,userId,date(month),revision);}
    public boolean pending(long userId,YearMonth month){return Boolean.TRUE.equals(jdbc.query(
        "SELECT pending FROM risk_assessment_requests WHERE user_id=? AND basis_month=?",(r,n)->r.getBoolean(1),userId,date(month)).stream().findFirst().orElse(false));}
    @Transactional(propagation=Propagation.MANDATORY)
    public void finishRequest(long userId,YearMonth month,long revision){jdbc.update("UPDATE risk_assessment_requests SET pending=FALSE WHERE user_id=? AND basis_month=? AND data_revision<=?",userId,date(month),revision);}
    public Set<YearMonth> confirmedMonths(long userId,YearMonth start,YearMonth end){return Set.copyOf(jdbc.query(
        "SELECT DISTINCT basis_month FROM risk_assessment_versions WHERE user_id=? AND basis_month>=? AND basis_month<=?",
        (r,n)->YearMonth.from(r.getDate(1).toLocalDate()),userId,date(start),date(end)));}
    private static java.sql.Date date(YearMonth month){return java.sql.Date.valueOf(month.atDay(1));}
    private boolean sameJson(String first,String second){if(first==null||second==null)return first==null&&second==null;try{return json.readTree(first).equals(json.readTree(second));}catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalArgumentException("Invalid assessment JSON");}}
    private static ApiException conflict(){return new ApiException(HttpStatus.CONFLICT,"RISK_HISTORY_CONFLICT","저장된 위험 이력과 요청이 일치하지 않습니다.");}
}
