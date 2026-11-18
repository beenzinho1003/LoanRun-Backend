package com.loanrun;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loanrun.report.client.ExplanationClient;
import com.loanrun.report.config.ExplanationConfig;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.*;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class ExplanationConfigTest {
    // Every context uses an in-memory transport, including when a test explicitly enables the client.
    @TestConfiguration(proxyBeanMethods=false) static class Transport {
        @Bean("fixtureExplanationBuilder") RestClient.Builder builder(){return RestClient.builder().baseUrl("https://api.openai.com/v1");}
        @Bean MockRestServiceServer server(@Qualifier("fixtureExplanationBuilder")RestClient.Builder builder){return MockRestServiceServer.bindTo(builder).build();}
        @Bean @Primary @Qualifier("reportExplanationHttpClient") RestClient fixtureTransport(@Qualifier("fixtureExplanationBuilder")RestClient.Builder builder,MockRestServiceServer server){return builder.build();}
    }
    final ApplicationContextRunner runner=new ApplicationContextRunner().withPropertyValues("OPENAI_API_KEY=","OPENAI_MODEL=")
        .withUserConfiguration(ExplanationConfig.class,Transport.class)
        .withBean(ObjectMapper.class,ObjectMapper::new);
    final ExplanationClient.Facts facts=new ExplanationClient.Facts("STABLE","BALANCED",0,null,null);
    @Test void absentEnableFlagRemainsOffEvenWithCredentialsAndModel(){
        runner.withPropertyValues("OPENAI_API_KEY=fixture-key","OPENAI_MODEL=fixture-model").run(context->{
            assertThat(context).hasSingleBean(ExplanationClient.class);
            assertThat(context.getBean(ExplanationClient.class).explain(facts).source()).isEqualTo("FALLBACK");context.getBean(MockRestServiceServer.class).verify();
        });
    }
    @Test void explicitDisabledFlagAlsoSendsNothing(){
        runner.withPropertyValues("OPENAI_ENABLED=false","OPENAI_API_KEY=fixture-key","OPENAI_MODEL=fixture-model").run(context->{
            assertThat(context.getBean(ExplanationClient.class).explain(facts).source()).isEqualTo("FALLBACK");context.getBean(MockRestServiceServer.class).verify();
        });
    }
    @Test void missingKeyOrModelCannotTriggerACallEvenWhenEnabled(){
        for(var property:List.of("OPENAI_MODEL=fixture-model","OPENAI_API_KEY=fixture-key"))
            runner.withPropertyValues("OPENAI_ENABLED=true",property).run(context->{
                assertThat(context.getBean(ExplanationClient.class).explain(facts).source()).isEqualTo("FALLBACK");context.getBean(MockRestServiceServer.class).verify();
            });
    }
    @Test void explicitEnableFlagBindsOnlyTheNamedTransportAndValidatedModelText()throws Exception{
        var mapper=new ObjectMapper();String text=mapper.writeValueAsString(Map.of("summary","계획을 확인해 주세요.","action_note","기록을 확인해 주세요."));
        String response=mapper.writeValueAsString(Map.of("status","completed","output",List.of(Map.of("content",List.of(Map.of("type","output_text","text",text))))));
        runner.withPropertyValues("OPENAI_ENABLED=true","OPENAI_API_KEY=fixture-key","OPENAI_MODEL=fixture-model").run(context->{
            var server=context.getBean(MockRestServiceServer.class);server.expect(requestTo("https://api.openai.com/v1/responses"))
                .andExpect(header("Authorization","Bearer fixture-key")).andExpect(jsonPath("$.model").value("fixture-model"))
                .andExpect(jsonPath("$.store").value(false)).andRespond(withSuccess(response,MediaType.APPLICATION_JSON));
            assertThat(context.getBean(ExplanationClient.class).explain(facts).source()).isEqualTo("MODEL");server.verify();
        });
    }
}
