package com.loanrun.common.session;

import com.loanrun.common.contract.CurrentUserProvider;
import com.loanrun.common.contract.PlanType;
import com.loanrun.common.error.ApiException;
import com.loanrun.common.contract.UserAccessPort;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.context.annotation.RequestScope;

@Component
@RequestScope
public class SessionCurrentUserProvider implements CurrentUserProvider {
    private final HttpServletRequest request;
    private final AnonymousUserService anonymousUsers;
    private final UserAccessPort access;
    private Long userId;

    public SessionCurrentUserProvider(HttpServletRequest request, AnonymousUserService anonymousUsers, UserAccessPort access) {
        this.request = request; this.anonymousUsers = anonymousUsers; this.access = access;
    }

    @Override
    public long currentUserId() {
        if (userId == null) {
            HttpSession session = request.getSession(true);
            userId = anonymousUsers.resolve(session.getId());
            session.setAttribute("demo_user_id", userId);
        }
        return userId;
    }

    @Override
    public PlanType currentPlanType() {
        return access.planType(currentUserId());
    }

    @Override
    public void requirePro() {
        access.requirePro(currentUserId());
    }
}
