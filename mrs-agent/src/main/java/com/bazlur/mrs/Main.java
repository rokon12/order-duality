package com.bazlur.mrs;

import module java.base;
import dev.langchain4j.model.ollama.OllamaChatModel;

public final class Main {
    public static void main(String[] args) throws Exception {
        URI url = URI.create(env("MRS_URL", "https://localhost:8443/orders"));
        Path ca = Path.of(env("MRS_CA", ".mrs/tls/server.crt"));
        var customer = login(url, ca, env("MRS_USER", "alice"), required("MRS_PASSWORD"));
        var orders = new MrsOrders(customer);
        if (args.length == 1 && args[0].equals("--list")) { System.out.println(Json.encode(orders.listOrders())); return; }
        if (args.length == 2 && args[0].equals("--read")) { System.out.println(Json.encode(orders.getOrder(Long.parseLong(args[1])))); return; }
        if (args.length == 1 && args[0].equals("--requests")) { System.out.println(Json.encode(customer.cancellations())); return; }
        var model = OllamaChatModel.builder().baseUrl(env("OLLAMA_BASE_URL", "http://localhost:11434"))
                .modelName(env("OLLAMA_MODEL", "llama3.1:8b")).temperature(0.0).seed(42)
                .numCtx(8192).numPredict(1024).think(false).returnThinking(false)
                .timeout(Duration.ofSeconds(Long.parseLong(env("OLLAMA_TIMEOUT_SECONDS", "180")))).maxRetries(0).build();
        var agent = new OrderAgent(model, orders);
        if (args.length == 2 && args[0].equals("--ask")) { print(agent.answer(args[1])); return; }
        if (args.length != 0) throw new IllegalArgumentException("Use --list, --requests, --read ID, --ask QUESTION, or no arguments for interactive mode");
        var console = System.console();
        if (console == null) throw new IllegalStateException("Interactive confirmation requires a terminal; use --ask for a noninteractive question");
        Confirmation confirmation = null;
        if (System.getenv("MRS_EXECUTOR_PASSWORD") != null) {
            confirmation = new Confirmation(customer, login(url, ca, "executor", required("MRS_EXECUTOR_PASSWORD")));
        }
        System.out.println("Order assistant. Ask an order question, /requests, /review REQUEST_ID, /confirm REQUEST_ID, or /quit.");
        for (String input; (input = console.readLine("> ")) != null;) {
            if (input.equals("/quit")) return;
            try {
                if (input.equals("/requests")) {
                    System.out.println(Json.encode(customer.cancellations()));
                } else if (input.startsWith("/review ")) {
                    System.out.println(Json.encode(customer.cancellation(UUID.fromString(input.substring(8).trim()))));
                } else if (input.startsWith("/confirm ")) {
                    if (confirmation == null) throw new IllegalStateException("Confirmation executor is not configured");
                    var review = confirmation.review(UUID.fromString(input.substring(9).trim()));
                    // JSON encoding escapes control characters in untrusted reasons before displaying them.
                    System.out.println("Review: " + Json.encode(review));
                    String response = console.readLine("Type CONFIRM %s to cancel this order: ", review.requestId());
                    System.out.println(Json.encode(confirmation.confirm(review, response)));
                } else print(agent.answer(input));
            } catch (Exception error) {
                System.err.println("Operation failed: " + error.getClass().getSimpleName()
                        + ". No success is assumed; review the request before retrying.");
            }
        }
    }
    private static void print(OrderAgent.Answer answer) {
        System.out.println("Assistant: " + safeTerminal(answer.explanation()));
        for (var request : answer.requests()) System.out.println("Recorded request (requires separate confirmation): " + Json.encode(request));
    }
    private static String safeTerminal(String text) { return text.replaceAll("[\\p{Cc}&&[^\\n\\t]]", ""); }
    private static MrsClient login(URI url, Path ca, String user, String password) throws Exception {
        char[] secret = password.toCharArray();
        try { return new MrsClient(url, ca).login(user, secret); }
        finally { Arrays.fill(secret, '\0'); }
    }
    private static String env(String name, String fallback) { return System.getenv().getOrDefault(name, fallback); }
    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Set " + name);
        return value;
    }
}
