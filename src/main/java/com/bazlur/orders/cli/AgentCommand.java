package com.bazlur.orders.cli;

import module java.base;

import com.bazlur.orders.ai.MockAgent;
import com.bazlur.orders.ai.AgentOrderReader;
import com.bazlur.orders.ai.OllamaAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
@Profile({"agent", "mock-agent"})
public record AgentCommand(OllamaAgent agent, AgentOrderReader reader, Environment environment) implements ApplicationRunner {
    private static final Logger LOG = LoggerFactory.getLogger(AgentCommand.class);

    @Override public void run(ApplicationArguments args) throws Exception {
        long customerId = Long.parseLong(option(args, "customer-id", "42"));
        if (environment.matchesProfiles("mock-agent")) {
            LOG.info("Deterministic mock (no LLM call):\n{}", MockAgent.summarize(reader.getCustomerOrders(customerId)));
            return;
        }
        String question = option(args, "question", "Show me my recent orders and explain which ones are still pending.");
        LOG.info("Asking local Ollama for customer {}...", customerId);
        var result = agent.answer(customerId, question);
        LOG.info("Model: {} | digest {}", result.model().model(), result.model().digest().substring(0, 12));
        for (var call : result.tools()) LOG.info("Tool: {}({})", call.name(), call.arguments());
        LOG.info("Answer:\n{}", result.answer());
        String trace = option(args, "trace-file", environment.getProperty("OLLAMA_TRACE_FILE", ""));
        if (!trace.isBlank()) {
            var path = Path.of(trace).toAbsolutePath();
            result.writeTrace(path);
            LOG.info("Trace: {}", path);
        }
    }

    private static String option(ApplicationArguments args, String name, String fallback) {
        var values = args.getOptionValues(name);
        if (values == null) return fallback;
        if (values.size() != 1 || values.getFirst().isBlank()) throw new IllegalArgumentException("Supply one --" + name + " value");
        return values.getFirst();
    }
}
