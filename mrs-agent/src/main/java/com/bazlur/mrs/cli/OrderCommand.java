package com.bazlur.mrs.cli;

import module java.base;

import com.bazlur.mrs.agent.OrderAgent;
import com.bazlur.mrs.client.MrsClient;
import com.bazlur.mrs.client.MrsOrders;
import com.bazlur.mrs.domain.OrderValidation;
import com.bazlur.mrs.support.Json;
import com.bazlur.mrs.workflow.Confirmation;
import dev.langchain4j.model.ollama.OllamaChatModel;
import picocli.CommandLine.ArgGroup;
import picocli.CommandLine.Command;
import picocli.CommandLine.ITypeConverter;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;
import picocli.CommandLine.TypeConversionException;

@Command(
        name = "mrs-agent",
        mixinStandardHelpOptions = true,
        version = OrderCommand.VERSION,
        description = "Read your orders and request controlled cancellations through MRS.",
        footer =
                "With no action option, starts an interactive session. Configure MRS_USER, MRS_PASSWORD,%n"
                        + "MRS_EXECUTOR_PASSWORD (optional), and OLLAMA_MODEL through the environment.")
public final class OrderCommand implements Callable<Integer> {
    static final String VERSION = "mrs-agent 1.0.0";

    @Spec private CommandSpec spec;

    @ArgGroup(exclusive = true, multiplicity = "0..1")
    private Action action;

    static final class Action {
        @Option(names = "--list", description = "List up to ten recent orders as JSON.")
        boolean list;

        @Option(
                names = "--read",
                paramLabel = "ORDER_ID",
                converter = PositiveOrderId.class,
                description = "Read an owned order's JSON document.")
        Long orderId;

        @Option(names = "--requests", description = "List your cancellation requests as JSON.")
        boolean requests;

        @Option(
                names = "--ask",
                paramLabel = "QUESTION",
                description = "Ask one order question without interactive confirmation.")
        String question;
    }

    public static final class PositiveOrderId implements ITypeConverter<Long> {
        @Override
        public Long convert(String value) {
            try {
                long id = Long.parseLong(value);
                OrderValidation.positiveId(id);
                return id;
            } catch (IllegalArgumentException error) {
                throw new TypeConversionException("Order ID must be a positive integer");
            }
        }
    }

    public static void main(String[] args) {
        System.exit(CliSupport.commandLine(new OrderCommand()).execute(args));
    }

    @Override
    public Integer call() throws Exception {
        var out = spec.commandLine().getOut();
        var err = spec.commandLine().getErr();
        var console = System.console();
        if (action == null && console == null) {
            err.println(
                    "Interactive confirmation requires a terminal. Use --ask, --list, --read, or --requests.");
            return 1;
        }
        if (action != null
                && action.question != null
                && (action.question.isBlank() || action.question.length() > 2000)) {
            err.println("Question must contain 1–2000 characters.");
            return 2;
        }
        if (action != null
                && !action.list
                && !action.requests
                && action.orderId == null
                && action.question == null) {
            err.println(
                    "Select an action, or omit action options to start an interactive session.");
            return 2;
        }

        URI url = URI.create(env("MRS_URL", "https://localhost:8443/orders"));
        Path certificate = Path.of(env("MRS_CA", ".mrs/tls/server.crt"));
        var customer = login(url, certificate, env("MRS_USER", "alice"), "MRS_PASSWORD");
        var orders = new MrsOrders(customer);
        if (action != null) {
            if (action.list) {
                out.println(Json.encode(orders.listOrders()));
                return 0;
            }
            if (action.orderId != null) {
                out.println(Json.encode(orders.getOrder(action.orderId)));
                return 0;
            }
            if (action.requests) {
                out.println(Json.encode(customer.cancellations()));
                return 0;
            }
        }

        var model =
                OllamaChatModel.builder()
                        .baseUrl(env("OLLAMA_BASE_URL", "http://localhost:11434"))
                        .modelName(env("OLLAMA_MODEL", "llama3.1:8b"))
                        .temperature(0.0)
                        .seed(42)
                        .numCtx(8192)
                        .numPredict(1024)
                        .think(false)
                        .returnThinking(false)
                        .timeout(
                                Duration.ofSeconds(
                                        Long.parseLong(env("OLLAMA_TIMEOUT_SECONDS", "180"))))
                        .maxRetries(0)
                        .build();
        var agent = new OrderAgent(model, orders);
        if (action != null) {
            var answer = agent.answer(action.question);
            out.println(Screens.answer(answer));
            for (var request : answer.requests()) {
                out.println();
                out.println(Screens.recorded(request));
                out.println(
                        "To approve it, start an interactive session and enter: /confirm "
                                + Screens.oneLine(request.path("requestId").asString()));
            }
            return 0;
        }

        Confirmation confirmation = null;
        if (System.getenv("MRS_EXECUTOR_PASSWORD") != null) {
            confirmation =
                    new Confirmation(
                            customer, login(url, certificate, "executor", "MRS_EXECUTOR_PASSWORD"));
        }
        new InteractiveShell(
                        customer,
                        agent,
                        confirmation,
                        prompt -> console.readLine("%s", prompt),
                        out)
                .run();
        return 0;
    }

    private static MrsClient login(URI url, Path certificate, String user, String passwordVariable)
            throws Exception {
        return new MrsClient(url, certificate)
                .login(user, () -> required(passwordVariable).toCharArray());
    }

    private static String env(String name, String fallback) {
        return System.getenv().getOrDefault(name, fallback);
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            // Only known environment variable names reach this message; values never do.
            throw new MissingConfiguration(name);
        }
        return value;
    }

    static final class MissingConfiguration extends IllegalArgumentException {
        MissingConfiguration(String name) {
            super("Set " + name + " before starting the application.");
        }
    }
}
