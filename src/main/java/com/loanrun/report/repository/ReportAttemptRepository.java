package com.loanrun.report.repository;

import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.*;

@Repository
public class ReportAttemptRepository {
    private final JdbcTemplate jdbc;public ReportAttemptRepository(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public long countSince(long userId,Instant since){return jdbc.queryForObject("SELECT count(*) FROM report_attempts WHERE user_id=? AND attempted_at>=?",Long.class,userId,Timestamp.from(since));}
    @Transactional(propagation=Propagation.MANDATORY)
    public void record(long userId,Instant now){jdbc.update("INSERT INTO report_attempts(user_id,attempted_at) VALUES (?,?)",userId,Timestamp.from(now));}
}
