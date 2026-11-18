package com.loanrun.report.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.loanrun.common.contract.*;
import com.loanrun.common.error.ApiException;
import com.loanrun.common.validation.JsonPatch;
import com.loanrun.report.dto.ComparisonSettings;
import com.loanrun.report.dto.ComparisonSettings.*;
import com.loanrun.report.repository.ComparisonSettingsRepository;
import com.loanrun.user.domain.DemoUser;
import com.loanrun.user.repository.DemoUserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ComparisonSettingsService {
    private final ComparisonSettingsRepository settings;
    private final DemoUserRepository users;
    private final RevisionPort revisions;
    public ComparisonSettingsService(ComparisonSettingsRepository settings,DemoUserRepository users,RevisionPort revisions){this.settings=settings;this.users=users;this.revisions=revisions;}
    /** Internal report snapshots must check their own job/user access before reading. */
    @Transactional(readOnly=true) public ComparisonSettings snapshot(long userId){return settings.find(userId).orElseGet(ComparisonSettings::defaults);}
    @Transactional(readOnly=true) public ComparisonSettings get(long userId){
        requirePro(users.findById(userId).orElseThrow(ApiException::unavailable));return snapshot(userId);
    }
    @Transactional public Updated patch(long userId,JsonNode body){
        requirePro(users.findLocked(userId).orElseThrow(ApiException::unavailable));
        try {
            JsonPatch.fields(body,"consented","age_group","income_decile");var old=snapshot(userId);
            boolean consent=body.has("consented")?JsonPatch.bool(body,"consented"):old.consented();
            AgeGroup age=body.has("age_group")?body.get("age_group").isNull()?null:JsonPatch.enumeration(body,"age_group",AgeGroup.class):old.ageGroup();
            Integer decile=old.incomeDecile();
            if(body.has("income_decile")) {
                var value=body.get("income_decile");
                if(value.isNull()) decile=null;
                else if(!value.isIntegralNumber()||!value.canConvertToInt()||value.intValue()<1||value.intValue()>5)
                    throw ApiException.validation("income_decile","1~5 중 선택해 주세요.");
                else decile=value.intValue();
            }
            if(consent&&(age==null||decile==null)) throw ApiException.validation("non_field_errors","동의 시 연령대와 소득 그룹이 필요합니다.");
            var next=new ComparisonSettings(consent,age,"DEMO",decile);boolean changed=!old.equals(next);
            if(changed){settings.save(userId,next);revisions.bump(userId,"COMPARISON_CHANGED");}
            return new Updated(consent,age,"DEMO",changed,decile);
        } catch(ApiException ex){
            if(!ex.code().equals("VALIDATION_ERROR")) throw ex;
            throw new ApiException(HttpStatus.BAD_REQUEST,"COMPARISON_SETTINGS_INVALID","또래 비교 기준을 확인해 주세요.",ex.errors());
        }
    }
    private void requirePro(DemoUser user){if(user.getPlanType()!=PlanType.PRO) throw new ApiException(HttpStatus.FORBIDDEN,"PRO_REQUIRED","PRO 이용자만 사용할 수 있습니다.");}
}
