package com.loanrun;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.loanrun.common.contract.PlanType;
import com.loanrun.common.error.ApiException;
import com.loanrun.common.session.UserAccessService;
import com.loanrun.user.domain.DemoUser;
import com.loanrun.user.repository.DemoUserRepository;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class UserAccessServiceTest {
    @Test void internalChecksNeedNoRequestContextAndReadPlanChangesEveryTime(){
        var users=mock(DemoUserRepository.class);var user=mock(DemoUser.class);
        var plan=new AtomicReference<>(PlanType.FREE);
        when(users.findById(7L)).thenReturn(Optional.of(user));when(user.getPlanType()).thenAnswer(i->plan.get());
        var access=new UserAccessService(users);
        assertThatThrownBy(()->access.requirePro(7)).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code()).isEqualTo("PRO_REQUIRED"));
        plan.set(PlanType.PRO);assertThatCode(()->access.requirePro(7)).doesNotThrowAnyException();
        plan.set(PlanType.FREE);
        assertThatThrownBy(()->access.requirePro(7)).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.status().value()).isEqualTo(403));
    }
    @Test void aMissingInternalOwnerNeverFallsBackToAnotherUser(){
        var users=mock(DemoUserRepository.class);when(users.findById(77L)).thenReturn(Optional.empty());
        assertThatThrownBy(()->new UserAccessService(users).planType(77)).isInstanceOfSatisfying(ApiException.class,
            e->assertThat(e.code()).isEqualTo("SERVICE_UNAVAILABLE"));
    }
}
