package com.loanrun.payment.repository;

import com.loanrun.common.contract.FinanceModels.*;
import com.loanrun.common.error.ApiException;
import com.loanrun.loan.read.LoanSources.Due;
import com.loanrun.payment.read.StoredDueSource;
import java.sql.*;
import java.time.LocalDate;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.*;

@Repository
@Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
public class StoredDueRepository {
    private static final String SELECT="""
        SELECT d.*,l.status AS loan_status,count(a.id) AS allocation_count,
            coalesce(sum(a.principal_amount),0) AS allocated_principal,
            coalesce(sum(a.interest_amount),0) AS allocated_interest,
            coalesce(sum(a.fee_amount),0) AS allocated_fee,
            count(*) FILTER (WHERE p.payment_type<>'REGULAR') AS non_regular_allocations
        FROM due_items d JOIN loans l ON l.user_id=d.user_id AND l.id=d.loan_id
        LEFT JOIN payment_allocations a ON a.user_id=d.user_id AND a.loan_id=d.loan_id AND a.due_item_id=d.id
        LEFT JOIN payments p ON p.user_id=a.user_id AND p.loan_id=a.loan_id AND p.id=a.payment_id
        """;
    private final NamedParameterJdbcTemplate jdbc;
    public StoredDueRepository(NamedParameterJdbcTemplate jdbc) { this.jdbc=jdbc; }

    public StoredDueSource find(long userId,long dueId) {
        owner(userId);if(dueId<=0)throw missing();
        var rows=jdbc.query(SELECT+" WHERE d.user_id=:user AND d.id=:due GROUP BY d.id,l.status",
            Map.of("user",userId,"due",dueId),StoredDueRepository::row);
        if(rows.isEmpty())throw missing();return rows.getFirst();
    }
    /** Internal source history includes PAID/DELETED loans. Missing rows do not mean zero contractual burden. */
    public List<StoredDueSource> range(long userId,DateRange range,Long loanId) {
        owner(userId);Objects.requireNonNull(range);
        var p=new HashMap<String,Object>();p.put("user",userId);p.put("from",range.startInclusive());p.put("to",range.endInclusive());
        String filter="";
        if(loanId!=null) {
            if(loanId<=0||jdbc.queryForObject("SELECT count(*) FROM loans WHERE user_id=:user AND id=:loan",Map.of("user",userId,"loan",loanId),Long.class)==0)
                throw new ApiException(HttpStatus.NOT_FOUND,"LOAN_NOT_FOUND","대출을 찾을 수 없습니다.");
            filter=" AND d.loan_id=:loan";p.put("loan",loanId);
        }
        return List.copyOf(jdbc.query(SELECT+" WHERE d.user_id=:user AND d.due_date BETWEEN :from AND :to"+filter+
            " GROUP BY d.id,l.status ORDER BY d.due_date,d.id",p,StoredDueRepository::row));
    }
    private static StoredDueSource row(ResultSet rs,int index)throws SQLException {
        var contract=(Number)rs.getObject("contractual_amount");
        var due=new Due(rs.getLong("id"),rs.getLong("user_id"),rs.getLong("loan_id"),rs.getObject("due_date",LocalDate.class),
            rs.getLong("principal_amount"),rs.getLong("interest_amount"),rs.getLong("fee_amount"),contract==null?null:contract.longValue(),
            rs.getLong("required_amount"),rs.getBoolean("confirmed_unpaid"),rs.getLong("terms_revision"),rs.getTimestamp("created_at").toInstant());
        try {return new StoredDueSource(due,LoanStatus.valueOf(rs.getString("loan_status")),rs.getLong("allocation_count"),
            exact(rs,"allocated_principal"),exact(rs,"allocated_interest"),exact(rs,"allocated_fee"),rs.getLong("non_regular_allocations"));}
        catch(ArithmeticException ex){throw ApiException.unavailable();}
    }
    private static long exact(ResultSet rs,String key)throws SQLException {return rs.getBigDecimal(key).longValueExact();}
    private static void owner(long userId){if(userId<=0)throw new IllegalArgumentException("Positive internal owner required");}
    private static ApiException missing(){return new ApiException(HttpStatus.NOT_FOUND,"DUE_ITEM_NOT_FOUND","리소스를 찾을 수 없습니다.");}
}
