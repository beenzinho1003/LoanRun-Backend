package com.loanrun.payment.repository;

import com.loanrun.common.error.ApiException;
import java.time.LocalDate;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.*;

@Repository
@Transactional(propagation=Propagation.MANDATORY)
public class DueConfirmationRepository {
    public record Due(long id,long loanId,LocalDate date,long required,boolean confirmed,String loanStatus) {}
    private final NamedParameterJdbcTemplate jdbc;
    public DueConfirmationRepository(NamedParameterJdbcTemplate jdbc) { this.jdbc=jdbc; }

    /** Caller holds the common user lock first. Deleted loans expose no mutable scheduled resource. */
    public Due lock(long userId,long dueId) {
        if(dueId<=0)throw missing();
        var rows=jdbc.query("""
            SELECT d.*,l.status AS loan_status FROM due_items d
            JOIN loans l ON l.user_id=d.user_id AND l.id=d.loan_id
            WHERE d.user_id=:user AND d.id=:due AND l.status<>'DELETED' FOR UPDATE OF d
            """,Map.of("user",userId,"due",dueId),(rs,i)->new Due(rs.getLong("id"),rs.getLong("loan_id"),
                rs.getObject("due_date",LocalDate.class),rs.getLong("required_amount"),rs.getBoolean("confirmed_unpaid"),rs.getString("loan_status")));
        if(rows.isEmpty())throw missing();return rows.getFirst();
    }
    public void confirm(long userId,Due due,boolean confirmed) {
        int changed=jdbc.update("""
            UPDATE due_items d SET confirmed_unpaid=:confirmed
            WHERE d.user_id=:user AND d.id=:due AND d.confirmed_unpaid=:previous
              AND d.due_date=:date AND d.required_amount=:required
              AND EXISTS (SELECT 1 FROM loans l WHERE l.user_id=d.user_id AND l.id=d.loan_id AND l.status='ACTIVE')
              AND NOT EXISTS (SELECT 1 FROM payment_allocations a WHERE a.user_id=d.user_id AND a.loan_id=d.loan_id AND a.due_item_id=d.id)
            """,Map.of("user",userId,"due",due.id(),"confirmed",confirmed,"previous",due.confirmed(),"date",due.date(),"required",due.required()));
        if(changed!=1)throw conflict();
    }
    private static ApiException missing(){return new ApiException(HttpStatus.NOT_FOUND,"DUE_ITEM_NOT_FOUND","리소스를 찾을 수 없습니다.");}
    public static ApiException conflict(){return new ApiException(HttpStatus.CONFLICT,"DUE_CONFIRMATION_CONFLICT","납부 상태가 변경되었습니다. 다시 조회해 주세요.");}
}
