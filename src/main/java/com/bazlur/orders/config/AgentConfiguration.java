package com.bazlur.orders.config;

import com.bazlur.orders.ai.AgentOrderReader;
import com.bazlur.orders.ai.OllamaAgent;
import com.bazlur.orders.persistence.OrderDocumentRepository;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The local-model agent. Its data access uses the read-only agent account, never the API account. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OllamaProperties.class)
public class AgentConfiguration {
    @Bean
    AgentOrderReader agentOrderReader(@Qualifier("agentPool") DataSource agentPool) {
        return new AgentOrderReader(new OrderDocumentRepository(agentPool));
    }

    @Bean
    OllamaAgent ollamaAgent(OllamaProperties properties, AgentOrderReader reader) {
        return new OllamaAgent(properties.chatModel(), properties.model(), reader);
    }
}
