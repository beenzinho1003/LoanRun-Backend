package com.loanrun.loanproduct.client;

import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.client.RestClient;

@Configuration
@EnableScheduling
public class FssConfig {
    @Bean FssCatalogClient fssCatalogClient(@Value("${FSS_API_KEY:}")String key,Clock clock){
        var factory=new SimpleClientHttpRequestFactory();factory.setConnectTimeout(3000);factory.setReadTimeout(5000);
        var client=RestClient.builder().baseUrl("https://finlife.fss.or.kr/finlifeapi").requestFactory(factory).build();
        return new FssCatalogClient(client,key,clock);
    }
}
