package com.bazlur.orders.ai;

import com.bazlur.orders.application.OrderException;
import com.bazlur.orders.json.Json;
import com.bazlur.orders.persistence.Database;
import com.bazlur.orders.persistence.OrderDocumentRepository;
import module java.base;
import module jdk.httpserver;

import tools.jackson.databind.JsonNode;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.bazlur.orders.application.OrderException.Kind.*;

/** Exercises real LangChain4j dispatch and HTTP serialization against a local Ollama stub. */
class OllamaAgentTest {
    private HttpServer server;
    private OllamaRuntime runtime;
    private final Queue<String> replies = new ArrayDeque<>();
    private final List<JsonNode> requests = new CopyOnWriteArrayList<>();
    private final List<Long> orderReads = new ArrayList<>();
    private final List<Long> historyReads = new ArrayList<>();
    private String history = "{\"_id\":42,\"orders\":[{\"id\":1003,\"status\":\"PENDING\"}]}";
    private String order = "{\"_id\":1001,\"customer\":{\"id\":42},\"status\":\"PROCESSING\"}";
    private String show = """
            {"capabilities":["completion","tools"],"details":{"parameter_size":"8B","quantization_level":"Q4_K_M"}}
            """;
    private int chatStatus = 200;

    // The overridden methods never open a connection, so there is no data source.
    private final AgentOrderReader data = new AgentOrderReader(new OrderDocumentRepository(new Database(null))) {
        @Override public String getOrder(long id) { orderReads.add(id); return order; }
        @Override public String getCustomerOrders(long id) { historyReads.add(id); return history; }
    };

    @BeforeEach void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/", exchange -> {
            try (exchange) {
                String path = exchange.getRequestURI().getPath();
                String reply = switch (path) {
                    case "/api/show" -> show;
                    case "/api/tags" -> "{\"models\":[{\"name\":\"test:8b\",\"digest\":\"test-digest\"}]}";
                    case "/api/chat" -> {
                        requests.add(Json.parse(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
                        yield replies.isEmpty() ? "{}" : replies.remove();
                    }
                    default -> "{}";
                };
                var bytes = reply.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(path.equals("/api/chat") ? chatStatus : 200, bytes.length);
                exchange.getResponseBody().write(bytes);
            }
        });
        server.start();
        runtime = new OllamaRuntime(URI.create("http://127.0.0.1:" + server.getAddress().getPort()), "test:8b", Duration.ofSeconds(5));
    }

    @AfterEach void stop() { server.stop(0); }

    private OllamaAgent.Run ask() throws Exception { return new OllamaAgent(runtime, data).answer(42, "Which orders are pending?"); }

    private static String call(String name, String arguments) {
        return """
                {"model":"test:8b","done":true,"done_reason":"stop","prompt_eval_count":10,"eval_count":5,
                 "message":{"role":"assistant","content":"","tool_calls":[{"function":{"name":"%s","arguments":%s}}]}}
                """.formatted(name, arguments);
    }

    private static String answer(String text) {
        return """
                {"model":"test:8b","done":true,"done_reason":"stop","prompt_eval_count":20,"eval_count":10,
                 "message":{"role":"assistant","content":"%s"}}
                """.formatted(text);
    }

    @Test void langchain4jDispatchesAnnotatedToolAndExplainsItsDocument() throws Exception {
        replies.add(call("getCustomerOrders", "{\"customerId\":42}"));
        replies.add(answer("Order 1003 is pending."));
        var run = ask();
        assertEquals(List.of(42L), historyReads);
        assertEquals("Order 1003 is pending.", run.answer());
        assertEquals("test-digest", run.model().digest());
        assertEquals(Json.parse(history), run.tools().getFirst().result());
        assertEquals(2, requests.size());
        assertTrue(requests.getLast().at("/messages/1/content").asString().contains(history));
        assertTrue(requests.getLast().path("tools").isMissingNode() || requests.getLast().path("tools").isEmpty());
        assertFalse(requests.getFirst().path("stream").asBoolean());
        assertFalse(requests.getFirst().path("think").asBoolean(true));
        assertEquals(2, requests.getFirst().path("tools").size());
        var names = new HashSet<String>();
        for (var tool : requests.getFirst().path("tools")) names.add(tool.at("/function/name").asString());
        assertEquals(Set.of("getOrder", "getCustomerOrders"), names);
        Files.createDirectories(Path.of("target/evidence"));
        Files.writeString(Path.of("target/evidence/langchain4j-tools.json"),
                Json.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(requests.getFirst().path("tools")) + "\n");
    }

    @Test void frameworkRejectsUnknownToolWithoutDatabaseAccess() {
        replies.add(call("shipOrder", "{\"orderId\":1001}"));
        assertThrows(RuntimeException.class, this::ask);
        assertTrue(orderReads.isEmpty());
        assertEquals(1, requests.size());
    }

    @Test void langchain4jCoercesNumericStringsAndIgnoresExtraArguments() throws Exception {
        replies.add(call("getOrder", "{\"orderId\":\"9007199254740993\",\"sql\":\"DROP TABLE orders\"}"));
        replies.add(answer("Order data read."));
        ask();
        assertEquals(List.of(9007199254740993L), orderReads, "The framework must preserve long precision");
    }

    @Test void malformedArgumentsDoNotReachTheDataLayer() throws Exception {
        for (String args : List.of("{}", "{\"orderId\":null}", "{\"orderId\":1.5}",
                "{\"orderId\":\"oops\"}", "{\"orderId\":9223372036854775808}")) {
            replies.add(call("getOrder", args));
            replies.add(answer("Could not read the order."));
            assertThrows(IOException.class, this::ask, args);
            assertTrue(orderReads.isEmpty(), args);
        }
    }

    @Test void frameworkLetsTheModelCorrectAnInvalidCall() throws Exception {
        replies.add(call("getOrder", "{\"orderId\":\"oops\"}"));
        replies.add(call("getOrder", "{\"orderId\":1001}"));
        replies.add(answer("Order 1001 is processing."));
        var run = ask();
        assertEquals(List.of(1001L), orderReads);
        assertEquals(3, requests.size());
        assertTrue(requests.get(1).at("/messages/3/content").asString().contains("Invalid tool arguments"));
        assertTrue(run.tools().stream().anyMatch(OllamaAgent.ToolExchange::successful));
    }

    @Test void foreignCustomerScopeFailsBeforeDataAccess() {
        replies.add(call("getCustomerOrders", "{\"customerId\":43}"));
        assertEquals(NOT_FOUND, assertThrows(OrderException.class, this::ask).kind());
        assertTrue(historyReads.isEmpty());
        assertEquals(1, requests.size());
    }

    @Test void foreignOrderIsNotSentToTheExplanationModel() {
        order = "{\"_id\":1004,\"customer\":{\"id\":43},\"private\":\"other customer\"}";
        replies.add(call("getOrder", "{\"orderId\":1004}"));
        assertEquals(NOT_FOUND, assertThrows(OrderException.class, this::ask).kind());
        assertEquals(1, requests.size());
    }

    @Test void nonpositiveIdsFailBeforeDataAccess() {
        replies.add(call("getOrder", "{\"orderId\":0}"));
        assertEquals(INVALID_REQUEST, assertThrows(OrderException.class, this::ask).kind());
        assertTrue(orderReads.isEmpty());
    }

    @Test void boundsArgumentCorrectionRounds() {
        for (int i = 0; i < 6; i++) replies.add(call("getOrder", "{\"orderId\":\"oops\"}"));
        assertThrows(RuntimeException.class, this::ask);
        assertTrue(requests.size() <= 4, "Correction must be bounded");
        assertTrue(orderReads.isEmpty());
    }

    @Test void orderHistoryReachesTheModelNewestFirstWhateverOrderMySqlReturns() throws Exception {
        // The order that made the real model misread dates in the captured runs.
        history = """
                {"_id":42,"name":"Alice","orders":[
                  {"id":1002,"status":"SHIPPED","createdAt":"2026-09-10 14:15:00.000000"},
                  {"id":1001,"status":"PROCESSING","createdAt":"2026-09-18 09:30:00.000000"},
                  {"id":1003,"status":"PENDING","createdAt":"2026-09-20 11:45:00.000000"}]}
                """;
        replies.add(call("getCustomerOrders", "{\"customerId\":42}"));
        replies.add(answer("Sorted."));
        var ids = new ArrayList<Long>();
        ask().tools().getFirst().result().path("orders").forEach(order -> ids.add(order.path("id").asLong()));
        assertEquals(List.of(1003L, 1001L, 1002L), ids);
    }

    @Test void emptyOrderHistoryStaysNull() throws Exception {
        history = "{\"_id\":42,\"name\":\"Alice\",\"orders\":null}";
        replies.add(call("getCustomerOrders", "{\"customerId\":42}"));
        replies.add(answer("No orders."));
        assertTrue(ask().tools().getFirst().result().path("orders").isNull());
    }

    @Test void refusesAnAnswerWithoutARead() {
        replies.add(answer("Your order is delivered."));
        assertThrows(IOException.class, this::ask);
    }

    @Test void oversizedDocumentDoesNotReachTheExplanationModel() {
        history = "{\"name\":\"" + "x".repeat(20_000) + "\"}";
        replies.add(call("getCustomerOrders", "{\"customerId\":42}"));
        assertTrue(assertThrows(IllegalStateException.class, this::ask).getMessage().contains("context budget"));
        assertEquals(1, requests.size());
    }

    @Test void httpFailureDoesNotRetryOrFallBackToTheMock() {
        chatStatus = 503;
        assertThrows(RuntimeException.class, this::ask);
        assertEquals(1, requests.size());
    }

    @Test void rejectsCloudModelsAndModelsWithoutTools() {
        var local = URI.create("http://127.0.0.1:11434");
        for (String cloud : List.of("gpt-oss:120b-cloud", "qwen3-coder:480b-cloud", "some-model:cloud")) {
            assertTrue(assertThrows(IllegalArgumentException.class, () -> new OllamaRuntime(local, cloud, Duration.ofSeconds(1)))
                    .getMessage().contains("locally installed"), cloud);
        }
        assertDoesNotThrow(() -> new OllamaRuntime(local, "cloudy-model:8b", Duration.ofSeconds(1)));
        show = "{\"capabilities\":[\"completion\"]}";
        assertTrue(assertThrows(IOException.class, runtime::inspect).getMessage().contains("tool calling"));
    }

    @Test void acceptsComposeServiceAndRejectsRemoteOrigins() {
        assertDoesNotThrow(() -> new OllamaRuntime(URI.create("http://ollama:11434"), "test:8b", Duration.ofSeconds(1)));
        for (String url : List.of("https://ollama.com", "http://example.com:11434", "http://ollama.example.com:11434",
                "http://localhost:11434/other", "http://u:p@localhost:11434", "http:/missing-host")) {
            assertThrows(IllegalArgumentException.class, () -> new OllamaRuntime(URI.create(url), "test:8b", Duration.ofSeconds(1)));
        }
    }

    @Test void reportsTruncatedGeneration() {
        replies.add(answer("partial").replace("\"stop\"", "\"length\""));
        assertTrue(assertThrows(IOException.class, this::ask).getMessage().contains("generation budget"));
    }
}
