package com.loanrun.finance.config;

import com.loanrun.common.contract.RepaymentEngine;
import com.loanrun.finance.engine.DefaultRepaymentEngine;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class RepaymentEngineConfiguration {
    @Bean
    RepaymentEngine repaymentEngine() { return new DefaultRepaymentEngine(); }
}
