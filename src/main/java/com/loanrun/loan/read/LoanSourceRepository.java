package com.loanrun.loan.read;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loanrun.common.contract.FinanceModels.*;
import com.loanrun.common.error.ApiException;
import com.loanrun.loan.read.LoanSources.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.*;

/** Only owner-scoped, read-only source retrieval. No financial inference or writes. */
@Repository
@Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
public class LoanSourceRepository {
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;
    public LoanSourceRepository(NamedParameterJdbcTemplate jdbc,ObjectMapper mapper) {this.jdbc=jdbc;this.mapper=mapper;}

    /** Internal source access includes PAID/DELETED; public views must apply their own visibility policy. */
    public Loan loan(long userId,long loanId) {
        owner(userId);
        if(loanId<=0)throw missing();
        var rows=jdbc.query("SELECT * FROM loans WHERE user_id=:user AND id=:id",Map.of("user",userId,"id",loanId),this::loanRow);
        if(rows.isEmpty())throw missing();return rows.getFirst();
    }

    /** Public rate history: one statement checks visibility and reads the complete original sequence. */
    public List<Rate> rateHistory(long userId,long loanId) {
        owner(userId);
        if(loanId<=0)throw missing();
        return jdbc.query("""
            SELECT h.* FROM loans l
            LEFT JOIN loan_rate_histories h ON h.loan_id=l.id AND h.user_id=l.user_id
            WHERE l.user_id=:user AND l.id=:loan AND l.status<>'DELETED'
            ORDER BY h.effective_date,h.id
            """,Map.of("user",userId,"loan",loanId),rs->{
                if(!rs.next())throw missing();
                var rates=new ArrayList<Rate>();
                do {
                    if(rs.getObject("id")!=null)rates.add(new Rate(rs.getLong("id"),rs.getLong("user_id"),rs.getLong("loan_id"),
                        rs.getBigDecimal("annual_rate"),date(rs,"effective_date"),instant(rs,"created_at")));
                } while(rs.next());
                return List.copyOf(rates);
            });
    }

    /** Business-date cutoff applies to actuals and effective histories, not future contractual due dates.
     * Stored latest loan columns remain latest facts and are not asserted to describe a past balance/status.
     */
    public Snapshot snapshot(long userId,LocalDate actualsThrough) {
        owner(userId);Objects.requireNonNull(actualsThrough);
        var p=Map.<String,Object>of("user",userId,"through",actualsThrough);
        var loans=jdbc.query("""
            SELECT * FROM loans WHERE user_id=:user
            AND (created_at AT TIME ZONE 'Asia/Seoul')::date<=:through ORDER BY id
            """,p,this::loanRow);
        if(loans.isEmpty())return new Snapshot(actualsThrough,List.of(),List.of(),List.of(),List.of(),List.of(),List.of(),List.of());
        var ids=loans.stream().map(Loan::loanId).toList();
        var scope=Map.<String,Object>of("user",userId,"through",actualsThrough,"loans",ids);
        var balances=jdbc.query("""
            SELECT * FROM loan_balance_snapshots WHERE user_id=:user AND loan_id IN (:loans)
            AND effective_date<=:through ORDER BY effective_date,id
            """,scope,(rs,i)->new Balance(rs.getLong("id"),rs.getLong("user_id"),rs.getLong("loan_id"),date(rs,"effective_date"),rs.getLong("amount"),rs.getString("reason"),instant(rs,"created_at")));
        var rates=jdbc.query("""
            SELECT * FROM loan_rate_histories WHERE user_id=:user AND loan_id IN (:loans)
            AND effective_date<=:through ORDER BY effective_date,id
            """,scope,(rs,i)->new Rate(rs.getLong("id"),rs.getLong("user_id"),rs.getLong("loan_id"),rs.getBigDecimal("annual_rate"),date(rs,"effective_date"),instant(rs,"created_at")));
        var payments=jdbc.query("""
            SELECT * FROM payments WHERE user_id=:user AND loan_id IN (:loans)
            AND paid_at<=:through ORDER BY paid_at,id
            """,scope,PAYMENT);
        var savings=jdbc.query("""
            SELECT * FROM repayment_savings WHERE user_id=:user AND loan_id IN (:loans)
            AND saved_at<=:through ORDER BY saved_at,id
            """,scope,SAVING);
        var dues=jdbc.query("""
            SELECT * FROM due_items WHERE user_id=:user AND loan_id IN (:loans) ORDER BY due_date,id
            """,scope,(rs,i)->new Due(rs.getLong("id"),rs.getLong("user_id"),rs.getLong("loan_id"),date(rs,"due_date"),rs.getLong("principal_amount"),rs.getLong("interest_amount"),
                rs.getLong("fee_amount"),number(rs,"contractual_amount"),rs.getLong("required_amount"),rs.getBoolean("confirmed_unpaid"),rs.getLong("terms_revision"),instant(rs,"created_at")));
        var allocations=jdbc.query("""
            SELECT a.* FROM payment_allocations a
            JOIN payments p ON p.id=a.payment_id AND p.user_id=a.user_id AND p.loan_id=a.loan_id
            WHERE a.user_id=:user AND a.loan_id IN (:loans) AND p.paid_at<=:through
            ORDER BY p.paid_at,p.id,a.id
            """,scope,(rs,i)->new Allocation(rs.getLong("id"),rs.getLong("user_id"),rs.getLong("loan_id"),rs.getLong("payment_id"),rs.getLong("due_item_id"),
                rs.getLong("principal_amount"),rs.getLong("interest_amount"),rs.getLong("fee_amount")));
        return new Snapshot(actualsThrough,loans,balances,rates,payments,savings,dues,allocations);
    }

    /** Preserved actual payments include deleted loans and use paid_at, never due-month attribution. */
    public List<Payment> payments(long userId,DateRange range) {
        owner(userId);Objects.requireNonNull(range);
        return List.copyOf(jdbc.query("""
            SELECT * FROM payments WHERE user_id=:user AND paid_at BETWEEN :start AND :end ORDER BY paid_at,id
            """,Map.of("user",userId,"start",range.startInclusive(),"end",range.endInclusive()),PAYMENT));
    }
    public List<Saving> savings(long userId,DateRange range) {
        owner(userId);Objects.requireNonNull(range);
        return List.copyOf(jdbc.query("""
            SELECT * FROM repayment_savings WHERE user_id=:user AND saved_at BETWEEN :start AND :end ORDER BY saved_at,id
            """,Map.of("user",userId,"start",range.startInclusive(),"end",range.endInclusive()),SAVING));
    }

    private Loan loanRow(ResultSet rs,int row) throws SQLException {
        long id=rs.getLong("id");PlanSnapshot plan;
        try {plan=mapper.readValue(rs.getString("initial_plan_snapshot"),PlanSnapshot.class);}
        catch(JsonProcessingException|IllegalArgumentException ex){throw ApiException.unavailable();}
        if(plan==null||plan.loanId()!=id||plan.version()<1||plan.monthlyExtraCash()<0||plan.monthlySaving()<0||plan.requiredMonthlySaving()<0)
            throw ApiException.unavailable();
        var reset=number(rs,"rate_reset_months");
        return new Loan(id,rs.getLong("user_id"),number(rs,"product_id"),rs.getString("institution_name"),rs.getString("product_name"),
            LoanType.valueOf(rs.getString("loan_type")),rs.getString("loan_form"),RepaymentMethod.valueOf(rs.getString("repayment_method")),
            rs.getLong("original_principal"),rs.getLong("remaining_principal"),rs.getBigDecimal("annual_rate"),RateType.valueOf(rs.getString("rate_type")),
            reset==null?null:reset.intValue(),date(rs,"next_rate_reset_date"),date(rs,"start_date"),date(rs,"maturity_date"),rs.getInt("payment_day"),date(rs,"next_due_date"),
            number(rs,"contractual_monthly_payment"),number(rs,"base_anchor_amount"),number(rs,"fixed_principal"),rs.getInt("grace_months"),date(rs,"grace_end_date"),
            enumeration(rs,"post_grace_method",RepaymentMethod.class),enumeration(rs,"prepayment_policy",PrepaymentPolicy.class),FeeMethod.valueOf(rs.getString("prepayment_fee_method")),
            rs.getBigDecimal("prepayment_fee_rate"),date(rs,"fee_start_date"),date(rs,"fee_end_date"),rs.getObject("fee_exempt",Boolean.class),rs.getLong("initial_savings_balance"),
            LoanStatus.valueOf(rs.getString("status")),date(rs,"snapshot_date"),rs.getLong("snapshot_principal"),plan,instant(rs,"created_at"),instant(rs,"updated_at"));
    }
    private static final RowMapper<Payment> PAYMENT=(rs,i)->new Payment(rs.getLong("id"),rs.getLong("user_id"),rs.getLong("loan_id"),date(rs,"paid_at"),
        PaymentKind.valueOf(rs.getString("payment_type")),rs.getLong("amount"),rs.getLong("principal_amount"),rs.getLong("extra_amount"),rs.getLong("interest_amount"),
        rs.getLong("fee_amount"),rs.getLong("savings_used_amount"),rs.getString("memo"),instant(rs,"created_at"));
    private static final RowMapper<Saving> SAVING=(rs,i)->new Saving(rs.getLong("id"),rs.getLong("user_id"),rs.getLong("loan_id"),date(rs,"saved_at"),rs.getLong("amount"),rs.getString("memo"),instant(rs,"created_at"));
    private static LocalDate date(ResultSet rs,String key)throws SQLException{return rs.getObject(key,LocalDate.class);}
    private static Instant instant(ResultSet rs,String key)throws SQLException{return rs.getTimestamp(key).toInstant();}
    private static Long number(ResultSet rs,String key)throws SQLException{Number value=(Number)rs.getObject(key);return value==null?null:value.longValue();}
    private static <E extends Enum<E>> E enumeration(ResultSet rs,String key,Class<E> type)throws SQLException {String value=rs.getString(key);return value==null?null:Enum.valueOf(type,value);}
    private static void owner(long userId){if(userId<=0)throw new IllegalArgumentException("Positive internal owner required");}
    private static ApiException missing(){return new ApiException(HttpStatus.NOT_FOUND,"LOAN_NOT_FOUND","대출을 찾을 수 없습니다.");}
}
