package com.loanrun.loanproduct.repository;

import com.loanrun.common.contract.FinanceModels.*;
import com.loanrun.loanproduct.client.FssCatalogClient.Snapshot;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class CatalogRepository {
    public record InstitutionRow(long id,String name){}
    private final JdbcTemplate jdbc;
    public CatalogRepository(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public boolean ready(){return jdbc.queryForObject("SELECT count(*) FROM catalog_sync_state",Long.class)>0;}
    public boolean institutionExists(long id){return jdbc.queryForObject("SELECT count(*) FROM financial_institutions WHERE id=?",Long.class,id)>0;}
    public List<InstitutionRow> institutions(LoanType type){
        String sql="SELECT DISTINCT i.id,i.name FROM financial_institutions i JOIN loan_products p ON p.institution_id=i.id WHERE p.eligible"
            +(type==null?"":" AND p.loan_type=?")+" ORDER BY i.id";
        return jdbc.query(sql,(rs,n)->new InstitutionRow(rs.getLong(1),rs.getString(2)),type==null?new Object[]{}:new Object[]{type.name()});
    }
    public List<EligibleProduct> products(long institutionId,LoanType type){
        String sql="SELECT p.*,i.name AS institution_name FROM loan_products p JOIN financial_institutions i ON i.id=p.institution_id WHERE p.institution_id=? AND p.eligible"
            +(type==null?"":" AND p.loan_type=?")+" ORDER BY p.id";
        return jdbc.query(sql,(rs,n)->new EligibleProduct(rs.getLong("id"),rs.getLong("institution_id"),rs.getString("institution_name"),
            rs.getString("name"),LoanType.valueOf(rs.getString("loan_type")),methods(rs.getString("repayment_methods"))),
            type==null?new Object[]{institutionId}:new Object[]{institutionId,type.name()});
    }
    public Optional<EligibleProduct> eligible(long productId){
        return jdbc.query("SELECT p.*,i.name AS institution_name FROM loan_products p JOIN financial_institutions i ON i.id=p.institution_id WHERE p.id=? AND p.eligible",
            (rs,n)->new EligibleProduct(rs.getLong("id"),rs.getLong("institution_id"),rs.getString("institution_name"),rs.getString("name"),
                LoanType.valueOf(rs.getString("loan_type")),methods(rs.getString("repayment_methods"))),productId).stream().findFirst();
    }
    private List<RepaymentMethod> methods(String values){return values.isBlank()?List.of():Arrays.stream(values.split(",")).map(RepaymentMethod::valueOf).toList();}

    @Transactional(propagation=Propagation.MANDATORY)
    public void replace(Snapshot snapshot,Instant now){
        jdbc.execute("SELECT pg_advisory_xact_lock(74011001)");
        jdbc.update("UPDATE loan_products SET eligible=FALSE WHERE loan_type IN ('CREDIT','MORTGAGE','JEONSE')");
        for(var p:snapshot.products()){
            jdbc.update("INSERT INTO financial_institutions(external_code,name) VALUES (?,?) ON CONFLICT(external_code) DO UPDATE SET name=excluded.name",p.institutionCode(),p.institutionName());
            Long institution=jdbc.queryForObject("SELECT id FROM financial_institutions WHERE external_code=?",Long.class,p.institutionCode());
            jdbc.update("""
                INSERT INTO loan_products(institution_id,external_code,name,loan_type,repayment_methods,source,source_url,updated_at)
                VALUES (?,?,?,?,?,'LIVE',?,?) ON CONFLICT(institution_id,external_code,loan_type) DO UPDATE SET name=excluded.name,
                repayment_methods=excluded.repayment_methods,source='LIVE',source_url=excluded.source_url,updated_at=excluded.updated_at,eligible=TRUE
                """,institution,p.code(),p.name(),p.type().name(),p.methods().stream().map(Enum::name).collect(Collectors.joining(",")),p.sourceUrl(),Timestamp.from(now));
        }
        jdbc.update("INSERT INTO catalog_sync_state(id,last_success_at) VALUES (1,?) ON CONFLICT(id) DO UPDATE SET last_success_at=excluded.last_success_at",Timestamp.from(now));
    }
}
