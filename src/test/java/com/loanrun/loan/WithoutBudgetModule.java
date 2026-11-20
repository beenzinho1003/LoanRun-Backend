package com.loanrun.loan;

import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/** Explicitly recreates the missing-module condition for fail-closed HTTP tests. */
@TestConfiguration
public class WithoutBudgetModule {
    @Bean static BeanDefinitionRegistryPostProcessor excludeBudgetModule() {
        return new BeanDefinitionRegistryPostProcessor() {
            @Override public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) {
                for (String name : registry.getBeanDefinitionNames()) {
                    String type = registry.getBeanDefinition(name).getBeanClassName();
                    if (type != null && type.startsWith("com.loanrun.budget.")) registry.removeBeanDefinition(name);
                }
            }
            @Override public void postProcessBeanFactory(ConfigurableListableBeanFactory factory) {}
        };
    }
}
