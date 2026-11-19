package com.loanrun.budget.service;

import com.loanrun.common.contract.*;
import com.loanrun.common.error.ApiException;
import com.loanrun.risk.domain.RiskHistoryModels.*;
import com.loanrun.risk.repository.RiskHistoryRepository;
import java.math.*;
import java.time.*;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Own-budget facts only; no loan/engine dependency or circular call through BudgetQueryPort. */
@Service
@ConditionalOnProperty(name="loanrun.risk-history.enabled",havingValue="true")
@Transactional(propagation=Propagation.MANDATORY)
public class FinanceChangeRecorder {
    public record Amounts(Long income,Long expenses){}
    private final JdbcTemplate jdbc;private final RiskHistoryRepository history;private final UserMutationLock locks;private final RevisionPort revisions;private final Clock clock;
    public FinanceChangeRecorder(JdbcTemplate jdbc,RiskHistoryRepository history,UserMutationLock locks,RevisionPort revisions,Clock clock){
        this.jdbc=jdbc;this.history=history;this.locks=locks;this.revisions=revisions;this.clock=clock;
    }
    public Amounts capture(long userId,YearMonth month){
        return jdbc.query("""
            SELECT b.*,COALESCE((SELECT sum(e.amount) FROM income_entries e WHERE e.user_id=b.user_id AND e.budget_id=b.id),0) AS extra
            FROM monthly_budgets b WHERE b.user_id=? AND b.year_month=?
            """,(r,n)->{
                long income=r.getLong("expected_income");
                if("REGULAR".equals(r.getString("income_type")))income=Math.addExact(income,r.getLong("extra"));
                else{
                    var actual=jdbc.query("""
                        SELECT COALESCE((SELECT sum(e.amount) FROM income_entries e WHERE e.user_id=b.user_id AND e.budget_id=b.id),0) AS actual
                        FROM monthly_budgets b WHERE b.user_id=? AND b.year_month<? AND b.income_type='IRREGULAR'
                        AND (b.income_confirmed OR EXISTS(SELECT 1 FROM income_entries e WHERE e.user_id=b.user_id AND e.budget_id=b.id))
                        ORDER BY b.year_month DESC LIMIT 3
                        """,(row,i)->row.getBigDecimal("actual"),userId,java.sql.Date.valueOf(month.atDay(1)));
                    if(actual.size()==3)income=actual.stream().reduce(BigDecimal.ZERO,BigDecimal::add).divide(BigDecimal.valueOf(3),0,RoundingMode.HALF_UP).longValueExact();
                }
                long expenses=Math.addExact(Math.addExact(r.getLong("fixed_essential_expenses"),r.getLong("variable_essential_expenses")),
                    Math.addExact(r.getLong("discretionary_expenses"),r.getLong("savings")));
                return new Amounts(income,expenses);
            },userId,java.sql.Date.valueOf(month.atDay(1))).stream().findFirst().orElse(null);
    }
    public void record(long userId,long revision,Origin origin,YearMonth month,Amounts before,Amounts after){
        locks.lock(userId);
        if(revisions.current(userId)!=revision)throw ApiException.unavailable();
        if(!month.equals(YearMonth.now(clock)))throw new IllegalArgumentException("Finance input changes must belong to the current month");
        if(before==null||after==null)return; // Initial source registration is a baseline, not an invented change.
        append(userId,revision,origin,month,Kind.INCOME,before.income(),after.income());
        append(userId,revision,origin,month,Kind.EXPENSE,before.expenses(),after.expenses());
    }
    private void append(long userId,long revision,Origin origin,YearMonth month,Kind kind,Long before,Long after){
        if(Objects.equals(before,after))return;
        history.append(new Change(userId,revision,origin,month,LocalDate.now(clock),kind,before,after,before!=null&&after!=null,clock.instant()));
    }
}
