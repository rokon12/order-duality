package com.bazlur.orders.cli;

import module java.base;
import module java.sql;

import com.bazlur.orders.ai.AgentOrderReader;
import com.bazlur.orders.ai.MockAgent;
import com.bazlur.orders.ai.OllamaAgent;
import com.bazlur.orders.application.OrderException;
import dev.langchain4j.exception.LangChain4jException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Runs one agent question from the command line. Expected failures (a refused customer scope, a model
 * that cannot answer, Ollama not running) are logged as one line with a non-zero exit code; anything else
 * still fails with a stack trace.
 */
@Component
@Profile({"agent", "mock-agent"})
public final class AgentCommand implements ApplicationRunner, ExitCodeGenerator {
    private static final Logger LOG = LoggerFactory.getLogger(AgentCommand.class);

    private final OllamaAgent agent;
    private final AgentOrderReader reader;
    private final Environment environment;
    private int exitCode;

    public AgentCommand(OllamaAgent agent, AgentOrderReader reader, Environment environment) {
        this.agent = agent;
        this.reader = reader;
        this.environment = environment;
    }

    @Override public void run(ApplicationArguments args) throws IOException, SQLException {
        long customerId = Long.parseLong(option(args, "customer-id", "42"));
        try {
            if (environment.matchesProfiles("mock-agent")) {
                LOG.info("Deterministic mock (no LLM call):\n{}", MockAgent.summarize(reader.getCustomerOrders(customerId)));
                return;
            }
            ask(args, customerId);
        } catch (OrderException e) {
            fail("Cannot answer for customer %d: %s".formatted(customerId, e.getMessage()));
        } catch (IOException | LangChain4jException e) {
            fail("The model could not answer: " + e.getMessage());
        } catch (RuntimeException e) {
            if (!causedBy(e, ConnectException.class)) throw e;
            fail("Cannot reach Ollama at " + environment.getProperty("orders.ollama.base-url") + "; is it running?");
        }
    }

    @Override public int getExitCode() { return exitCode; }

    private void ask(ApplicationArguments args, long customerId) throws IOException {
        String question = option(args, "question", "Show me my recent orders and explain which ones are still pending.");
        LOG.info("Asking local Ollama for customer {}...", customerId);
        var result = agent.answer(customerId, question);
        LOG.info("Model: {}", result.model());
        for (var call : result.tools()) LOG.info("Tool: {}({})", call.name(), call.arguments());
        LOG.info("Answer:\n{}", result.answer());
        String trace = option(args, "trace-file", environment.getProperty("OLLAMA_TRACE_FILE", ""));
        if (!trace.isBlank()) {
            var path = Path.of(trace).toAbsolutePath();
            result.writeTrace(path);
            LOG.info("Trace: {}", path);
        }
    }

    private void fail(String message) {
        LOG.warn(message);
        exitCode = 1;
    }

    private static boolean causedBy(Throwable error, Class<? extends Throwable> type) {
        for (var cause = error; cause != null; cause = cause.getCause()) {
            if (type.isInstance(cause)) return true;
        }
        return false;
    }

    private static String option(ApplicationArguments args, String name, String fallback) {
        var values = args.getOptionValues(name);
        if (values == null) return fallback;
        if (values.size() != 1 || values.getFirst().isBlank()) throw new IllegalArgumentException("Supply one --" + name + " value");
        return values.getFirst();
    }
}
