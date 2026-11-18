package com.loanrun.common.session;

import com.loanrun.common.contract.*;
import com.loanrun.common.error.ApiException;
import com.loanrun.user.repository.DemoUserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly=true)
public class UserAccessService implements UserAccessPort {
    private final DemoUserRepository users;
    public UserAccessService(DemoUserRepository users){this.users=users;}
    @Override public PlanType planType(long userId){return users.findById(userId).orElseThrow(ApiException::unavailable).getPlanType();}
    @Override public void requirePro(long userId){
        if(planType(userId)!=PlanType.PRO)throw new ApiException(HttpStatus.FORBIDDEN,"PRO_REQUIRED","PRO 이용자만 사용할 수 있습니다.");
    }
}
