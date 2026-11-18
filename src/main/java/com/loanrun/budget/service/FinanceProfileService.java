package com.loanrun.budget.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.loanrun.budget.domain.FinanceProfile;
import com.loanrun.budget.dto.FinanceProfileResponse;
import com.loanrun.budget.repository.FinanceProfileRepository;
import com.loanrun.common.contract.*;
import com.loanrun.common.error.ApiException;
import com.loanrun.common.validation.JsonPatch;
import com.loanrun.user.repository.DemoUserRepository;
import java.time.Clock;
import java.time.YearMonth;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

@Service
public class FinanceProfileService implements FinanceProfileQueryPort {
    private final FinanceProfileRepository profiles;
    private final DemoUserRepository users;
    private final RevisionPort revisions;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    public FinanceProfileService(FinanceProfileRepository profiles, DemoUserRepository users, RevisionPort revisions, JdbcTemplate jdbc, Clock clock) {
        this.profiles = profiles; this.users = users; this.revisions = revisions; this.jdbc = jdbc; this.clock = clock;
    }

    @Override @Transactional(readOnly = true)
    public Optional<IncomeType> incomeType(long userId) { return profiles.find(userId).map(FinanceProfile::incomeType); }

    @Transactional(readOnly = true)
    public FinanceProfileResponse get(long userId) { return requireProfile(userId).response(); }

    public FinanceProfile requireProfile(long userId) {
        return profiles.find(userId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "FINANCE_PROFILE_NOT_FOUND", "기본 재정정보를 찾을 수 없습니다."));
    }

    @Transactional(propagation=Propagation.MANDATORY)
    public void ensureCurrentSnapshots(long userId) {
        users.findLocked(userId).orElseThrow(ApiException::unavailable);
        profiles.find(userId).ifPresent(profile -> snapshotMissingMonths(profile, YearMonth.now(clock)));
    }

    @Transactional
    public FinanceProfileResponse save(long userId, JsonNode body) {
        try {
            JsonPatch.fields(body, "monthly_income", "income_type", "payday", "fixed_essential_expenses",
                "variable_essential_expenses", "discretionary_expenses", "savings", "emergency_fund");
            IncomeType incomeType = JsonPatch.enumeration(body, "income_type", IncomeType.class);
            Integer payday = null;
            if (incomeType == IncomeType.REGULAR) {
                long day = BudgetInput.amount(body, "payday");
                if (day < 1 || day > 31) throw BudgetInput.invalid("payday", "1~31일 중 선택해 주세요.");
                payday = (int)day;
            } else if (body.hasNonNull("payday")) throw BudgetInput.invalid("payday", "비정기 소득의 지급일은 null이어야 합니다.");
            var user = users.findLocked(userId).orElseThrow(ApiException::unavailable);
            var now = clock.instant();
            var previous = profiles.find(userId);
            var profile = new FinanceProfile(userId, BudgetInput.amount(body, "monthly_income"), incomeType, payday,
                BudgetInput.amount(body, "fixed_essential_expenses"), BudgetInput.amount(body, "variable_essential_expenses"),
                BudgetInput.amount(body, "discretionary_expenses"), BudgetInput.amount(body, "savings"),
                BudgetInput.optionalAmount(body, "emergency_fund"), previous.map(FinanceProfile::createdAt).orElse(now), now);
            Math.addExact(Math.addExact(profile.fixedEssentialExpenses(), profile.variableEssentialExpenses()),
                Math.addExact(profile.discretionaryExpenses(), profile.savings()));
            snapshotMissingMonths(previous.orElse(profile), YearMonth.now(clock));
            profiles.save(profile);
            user.setIncomeType(incomeType);
            // Preserve completed/confirmed months and each month's original discretionary limit.
            jdbc.update("""
                UPDATE monthly_budgets SET expected_income=?,income_type=?,payday=?,fixed_essential_expenses=?,
                variable_essential_expenses=?,discretionary_expenses=?,savings=?,emergency_fund=?,updated_at=?
                WHERE user_id=? AND year_month>=? AND source='DEFAULT' AND expenses_confirmed=FALSE AND income_confirmed=FALSE
                """, profile.monthlyIncome(), incomeType.name(), payday, profile.fixedEssentialExpenses(),
                profile.variableEssentialExpenses(), profile.discretionaryExpenses(), profile.savings(), profile.emergencyFund(),
                java.sql.Timestamp.from(now), userId, java.sql.Date.valueOf(YearMonth.now(clock).atDay(1)));
            revisions.bump(userId, "FINANCE_PROFILE_CHANGED");
            return profile.response();
        } catch (ApiException ex) {
            if (!ex.code().equals("VALIDATION_ERROR")) throw ex;
            throw new ApiException(HttpStatus.BAD_REQUEST, "BUDGET_VALIDATION_ERROR", "월별 재정정보를 확인해 주세요.", ex.errors());
        } catch (ArithmeticException ex) { throw BudgetInput.invalid("non_field_errors", "금액 합계가 지원 범위를 초과합니다."); }
    }

    private void snapshotMissingMonths(FinanceProfile profile, YearMonth currentMonth) {
        // Freeze expected values before a profile update crosses a month boundary. No actual income/expense is invented.
        var latest = jdbc.query("SELECT max(year_month) FROM monthly_budgets WHERE user_id=? AND year_month<=?",
            (rs, i) -> rs.getDate(1), profile.userId(), java.sql.Date.valueOf(currentMonth.atDay(1)));
        YearMonth start = latest.isEmpty() || latest.getFirst() == null
            ? YearMonth.from(profile.createdAt().atZone(clock.getZone())) : YearMonth.from(latest.getFirst().toLocalDate()).plusMonths(1);
        for (YearMonth month = start; !month.isAfter(currentMonth); month = month.plusMonths(1)) {
            jdbc.update("""
                INSERT INTO monthly_budgets(user_id,year_month,income_type,expected_income,payday,fixed_essential_expenses,
                variable_essential_expenses,discretionary_expenses,discretionary_limit,savings,emergency_fund,created_at,updated_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(user_id,year_month) DO NOTHING
                """, profile.userId(), java.sql.Date.valueOf(month.atDay(1)), profile.incomeType().name(), profile.monthlyIncome(),
                profile.payday(), profile.fixedEssentialExpenses(), profile.variableEssentialExpenses(), profile.discretionaryExpenses(),
                profile.discretionaryExpenses(), profile.savings(), profile.emergencyFund(), java.sql.Timestamp.from(clock.instant()), java.sql.Timestamp.from(clock.instant()));
        }
    }
}
