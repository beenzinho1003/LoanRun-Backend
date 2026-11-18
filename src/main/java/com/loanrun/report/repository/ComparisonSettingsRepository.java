package com.loanrun.report.repository;

import com.loanrun.report.dto.ComparisonSettings;
import com.loanrun.report.dto.ComparisonSettings.AgeGroup;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ComparisonSettingsRepository {
    private final JdbcTemplate jdbc;
    public ComparisonSettingsRepository(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public Optional<ComparisonSettings> find(long userId){
        return jdbc.query("SELECT consented,age_group,income_decile FROM comparison_settings WHERE user_id=?",
            (rs,n)->new ComparisonSettings(rs.getBoolean(1),rs.getString(2)==null?null:AgeGroup.valueOf(rs.getString(2)),"DEMO",rs.getObject(3,Integer.class)),userId).stream().findFirst();
    }
    public void save(long userId,ComparisonSettings settings){
        jdbc.update("""
            INSERT INTO comparison_settings(user_id,consented,age_group,income_decile) VALUES (?,?,?,?)
            ON CONFLICT(user_id) DO UPDATE SET consented=excluded.consented,age_group=excluded.age_group,income_decile=excluded.income_decile
            """,userId,settings.consented(),settings.ageGroup()==null?null:settings.ageGroup().name(),settings.incomeDecile());
    }
}
