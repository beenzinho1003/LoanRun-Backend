package com.loanrun.report.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loanrun.report.client.ExplanationClient;
import org.springframework.beans.factory.annotation.*;
import org.springframework.context.annotation.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods=false)
public class ExplanationConfig {
    @Bean("reportExplanationHttpClient") RestClient explanationTransport(){
        var factory=new SimpleClientHttpRequestFactory();factory.setConnectTimeout(3000);factory.setReadTimeout(10000);
        return RestClient.builder().baseUrl("https://api.openai.com/v1").requestFactory(factory).build();
    }
    @Bean ExplanationClient explanationClient(ObjectMapper mapper,@Qualifier("reportExplanationHttpClient")RestClient client,
            @Value("${OPENAI_ENABLED:false}")boolean enabled,@Value("${OPENAI_API_KEY:}")String key,@Value("${OPENAI_MODEL:}")String model){
        return new ExplanationClient(client,mapper,enabled,key,model);
    }
}
