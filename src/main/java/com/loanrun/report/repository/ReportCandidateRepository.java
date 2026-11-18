package com.loanrun.report.repository;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loanrun.common.contract.FinanceModels.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ReportCandidateRepository {
    private final JdbcTemplate jdbc;private final ObjectMapper json;
    public ReportCandidateRepository(JdbcTemplate jdbc,ObjectMapper json){this.jdbc=jdbc;this.json=json;}
    /** Service first validates the owning report and the current captured input. */
    public Optional<StrategyCandidate> find(long reportId,String version,String code){return jdbc.query("""
        SELECT * FROM strategy_candidates WHERE report_id=? AND strategy_version=? AND code=?
        """,(rs,n)->{
            try{
                var simulation=json.readValue(rs.getString("simulation_payload"),SimulationResult.class);
                return new StrategyCandidate(reportId,rs.getString("strategy_version"),rs.getString("code"),rs.getString("strategy_type"),
                    rs.getLong("data_revision"),rs.getTimestamp("computed_at").toInstant(),
                    json.readValue(rs.getString("allocations_payload"),new TypeReference<List<PlannedAmount>>(){}),
                    simulation,rs.getBoolean("feasible"),simulation.warnings());
            }catch(java.io.IOException ex){throw new IllegalStateException("Invalid stored strategy candidate");}
        },reportId,version,code).stream().findFirst();}
}
