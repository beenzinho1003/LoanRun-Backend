package com.loanrun.report.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@ConditionalOnProperty(name="loanrun.reports.worker-enabled",havingValue="true",matchIfMissing=true)
public class ReportRecoveryConfig {}
