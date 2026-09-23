package com.bazlur.orders.config;

import com.bazlur.orders.ai.AgentOrderReader;
import com.bazlur.orders.ai.OllamaAgent;
import com.bazlur.orders.ai.OllamaRuntime;
import com.bazlur.orders.persistence.Database;
import com.bazlur.orders.persistence.OrderDocumentRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The local-model agent. Its data access uses the read-only agent account, never the API account. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OllamaProperties.class)
public class AgentConfiguration {
    @Bean
    AgentOrderReader agentOrderReader(@Qualifier("agentDatabase") Database database) {
        return new AgentOrderReader(new OrderDocumentRepository(database));
    }

    @Bean
    OllamaRuntime ollamaRuntime(OllamaProperties properties) {
        return new OllamaRuntime(properties.baseUrl(), properties.model(), properties.timeout());
    }

    @Bean
    OllamaAgent ollamaAgent(OllamaRuntime runtime, AgentOrderReader reader) { return new OllamaAgent(runtime, reader); }
}
