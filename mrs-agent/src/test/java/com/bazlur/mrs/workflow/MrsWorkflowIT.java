package com.bazlur.mrs.workflow;

import static org.junit.jupiter.api.Assertions.*;

import module java.base;

import com.bazlur.mrs.agent.OrderAgent;
import com.bazlur.mrs.cli.InteractiveShell;
import com.bazlur.mrs.client.MrsClient;
import com.bazlur.mrs.client.MrsOrders;
import com.bazlur.mrs.domain.SessionExpired;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.*;

/** Real HTTPS/SCRAM/MRS/SQL integration. No mock REST service or skipped fallback. */
class MrsWorkflowIT {
    static final Path ROOT = Path.of("..").toAbsolutePath().normalize();
    static final URI URL =
            URI.create(System.getenv().getOrDefault("MRS_URL", "https://localhost:8443/orders"));
    static final Path CA = ROOT.resolve(".mrs/tls/server.crt");
    MrsClient alice, bob, executor;
    long order;

    static MrsClient login(String name) throws Exception {
        return new MrsClient(URL, CA).login(name, () -> (name + "-Local!26").toCharArray());
    }

    /** Waiting out the real 15-minute token is too slow for a test; MRS rejects this one too. */
    static void expire(MrsClient client) throws Exception {
        var token = MrsClient.class.getDeclaredField("token");
        token.setAccessible(true);
        token.set(client, "expired-token");
    }

    static String sql(String statement) throws Exception {
        var process =
                new ProcessBuilder(
                                "docker",
                                "compose",
                                "-f",
                                "compose.mrs.yml",
                                "exec",
                                "-T",
                                "-e",
                                "MYSQL_PWD=local-mrs-root-only",
                                "mysql",
                                "mysql",
                                "-uroot",
                                "-Dorder_demo",
                                "-Nse",
                                statement)
                        .directory(ROOT.toFile())
                        .redirectErrorStream(true)
                        .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.waitFor() != 0) throw new IllegalStateException(output);
        return output.strip();
    }

    @BeforeEach
    void setup() throws Exception {
        assertEquals("1", sql("SELECT version FROM mrs_demo_setup"));
        alice = login("alice");
        bob = login("bob");
        executor = login("executor");
        order = 10_000_000_000L + new SecureRandom().nextInt(1_000_000_000);
        sql("INSERT INTO orders(id,customer_id,status) VALUES (" + order + ",42,'PROCESSING')");
    }

    @AfterEach
    void cleanup() throws Exception {
        sql(
                "DELETE a FROM action_audit a JOIN cancellation_requests r ON r.id=a.request_id WHERE r.order_id="
                        + order
                        + "; DELETE FROM cancellation_requests WHERE order_id="
                        + order
                        + "; DELETE FROM order_items WHERE order_id="
                        + order
                        + "; DELETE FROM orders WHERE id="
                        + order);
    }

    @Test
    void javaReadsNativeDocumentAndCannotReadAnotherCustomer() throws Exception {
        var read = new MrsOrders(alice).getOrder(order);
        assertTrue(read.path("ok").asBoolean());
        assertEquals(order, read.at("/document/_id").asLong());
        assertEquals(
                "ORDER_UNAVAILABLE", new MrsOrders(bob).getOrder(order).path("code").asString());
        assertFalse(new MrsOrders(alice).listOrders().path("orders").isEmpty());
    }

    @Test
    void confirmationRequiresExplicitYesAndOwnReviewedRequest() throws Exception {
        var request =
                new MrsOrders(alice)
                        .requestCancellation(order, "Duplicate order", UUID.randomUUID());
        UUID id = UUID.fromString(request.path("requestId").asString());
        var confirmation = new Confirmation(alice, executor);
        var review = confirmation.review(id);
        assertEquals(order, review.orderId());
        assertEquals("PROCESSING", review.order().path("status").asString());
        assertFalse(review.expiresAt().isBlank());
        for (String input :
                Arrays.asList(
                        null,
                        "",
                        " ",
                        "n",
                        "no",
                        "true",
                        "CONFIRM",
                        "yes please",
                        "CONFIRM " + id)) {
            assertThrows(IllegalArgumentException.class, () -> confirmation.confirm(review, input));
        }
        assertEquals("PROCESSING", sql("SELECT status FROM orders WHERE id=" + order));
        var denied =
                assertThrows(
                        MrsClient.Failure.class, () -> new Confirmation(bob, executor).review(id));
        assertEquals(404, denied.status());
        assertEquals("CONFIRMED", confirmation.confirm(review, " YeS ").path("state").asString());
        assertEquals("CONFIRMED", confirmation.confirm(review, "y").path("state").asString());
        assertEquals("CANCELLED", sql("SELECT status FROM orders WHERE id=" + order));
        assertEquals(
                "1",
                sql(
                        "SELECT COUNT(*) FROM action_audit WHERE event='CANCELLED' AND request_id='"
                                + id
                                + "'"));
    }

    @Test
    void javaCredentialCannotBypassTheConfirmationRole() throws Exception {
        var request = new MrsOrders(alice).requestCancellation(order, "Test", UUID.randomUUID());
        var denied =
                assertThrows(
                        MrsClient.Failure.class,
                        () ->
                                alice.call(
                                        MrsClient.Operation.CONFIRM,
                                        Map.of(
                                                "requestId",
                                                request.path("requestId").asString(),
                                                "ownerHex",
                                                alice.principalId())));
        assertEquals(403, denied.status());
        assertEquals("PROCESSING", sql("SELECT status FROM orders WHERE id=" + order));
    }

    @Test
    void picocliConfirmationDeclinesThenRepromptsAndConfirms() throws Exception {
        var request =
                new MrsOrders(alice)
                        .requestCancellation(order, "CLI integration test", UUID.randomUUID());
        String id = request.path("requestId").asString();
        var lines =
                new ArrayDeque<>(
                        List.of(
                                "/review " + id,
                                "/confirm " + id,
                                "",
                                "/confirm " + id,
                                "maybe",
                                "y",
                                "/quit"));
        var agent = new OrderAgent(new ChatModel() {}, new MrsOrders(alice));
        var screen = new StringWriter();
        new InteractiveShell(
                        alice,
                        agent,
                        new Confirmation(alice, executor),
                        prompt -> {
                            assertFalse(
                                    lines.isEmpty(),
                                    "The /quit command must terminate the session");
                            String line = lines.removeFirst();
                            if (line.equals("maybe") || line.equals("y")) {
                                try {
                                    assertEquals(
                                            "PROCESSING",
                                            sql("SELECT status FROM orders WHERE id=" + order));
                                } catch (Exception error) {
                                    throw new AssertionError(error);
                                }
                            }
                            return line;
                        },
                        new PrintWriter(screen, true))
                .run();
        assertTrue(lines.isEmpty());
        String text = screen.toString();
        assertTrue(text.contains("Cancellation request for order " + order), text);
        assertTrue(text.contains("was not cancelled"), text);
        assertTrue(text.contains("Please answer"), text);
        assertTrue(text.contains("Order " + order + " cancelled."), text);
        assertEquals("CANCELLED", sql("SELECT status FROM orders WHERE id=" + order));
        assertEquals(
                "1",
                sql(
                        "SELECT COUNT(*) FROM action_audit WHERE event='CANCELLED' AND request_id='"
                                + id
                                + "'"));
    }

    @Test
    void customerRequestsReviewsAndApprovesTheLatestRequest() throws Exception {
        sql(
                "INSERT INTO order_items(id,order_id,product_id,quantity,unit_price) VALUES ("
                        + order
                        + ","
                        + order
                        + ",504,3,18.00)");
        var replies =
                new ArrayDeque<>(
                        List.of(
                                AiMessage.from(
                                        ToolExecutionRequest.builder()
                                                .id("1")
                                                .name("requestCancellation")
                                                .arguments(
                                                        "{\"orderId\":"
                                                                + order
                                                                + ",\"reason\":\"Not stated\"}")
                                                .build()),
                                AiMessage.from("Requested.")));
        var model =
                new ChatModel() {
                    @Override
                    public ChatResponse doChat(ChatRequest request) {
                        return ChatResponse.builder().aiMessage(replies.removeFirst()).build();
                    }
                };
        var lines = new ArrayDeque<>(List.of("cancel it", "/confirm", "y", "/confirm", "/quit"));
        var screen = new StringWriter();
        new InteractiveShell(
                        alice,
                        new OrderAgent(model, new MrsOrders(alice)),
                        new Confirmation(alice, executor),
                        prompt -> lines.removeFirst(),
                        new PrintWriter(screen, true))
                .run();
        String text = screen.toString();
        assertTrue(text.contains("Assistant: Requested."), text);
        assertTrue(text.contains("Order " + order + " is not cancelled yet."), text);
        assertTrue(text.contains("Type /confirm"), text);
        assertTrue(text.contains("3 x Braided USB-C Cable (CB-004) at 18.00 each"), text);
        assertTrue(text.contains("\"Not stated\" (written by the assistant)"), text);
        assertTrue(text.contains("Order " + order + " cancelled."), text);
        // The approved request is no longer the selection, so a repeat cannot act on it.
        assertTrue(text.contains("No request selected"), text);
        assertEquals("CANCELLED", sql("SELECT status FROM orders WHERE id=" + order));
    }

    @Test
    void expiredTokensAreRenewedWithoutTheCallerNoticing() throws Exception {
        var request = new MrsOrders(alice).requestCancellation(order, "Renew", UUID.randomUUID());
        var confirmation = new Confirmation(alice, executor);
        expire(alice);
        var review = confirmation.review(UUID.fromString(request.path("requestId").asString()));
        assertEquals(order, review.orderId());
        expire(executor);
        assertEquals("CONFIRMED", confirmation.confirm(review, "y").path("state").asString());
        assertEquals("CANCELLED", sql("SELECT status FROM orders WHERE id=" + order));
    }

    @Test
    void rejectedRenewalReportsAnExpiredSession() throws Exception {
        var passwords = new ArrayDeque<>(List.of("alice-Local!26", "wrong-password"));
        var client =
                new MrsClient(URL, CA).login("alice", () -> passwords.removeFirst().toCharArray());
        expire(client);
        assertThrows(SessionExpired.class, client::orders);
    }

    @Test
    void stateChangeBetweenReviewAndConfirmationIsRejected() throws Exception {
        var request = new MrsOrders(alice).requestCancellation(order, "Test", UUID.randomUUID());
        var confirmation = new Confirmation(alice, executor);
        var review = confirmation.review(UUID.fromString(request.path("requestId").asString()));
        sql("UPDATE orders SET status='SHIPPED' WHERE id=" + order);
        assertEquals(
                "ORDER_NOT_CANCELLABLE",
                confirmation.confirm(review, "yes").path("code").asString());
        assertEquals("SHIPPED", sql("SELECT status FROM orders WHERE id=" + order));
    }

    @Test
    void changedReasonRequiresAnotherReview() throws Exception {
        var request =
                new MrsOrders(alice)
                        .requestCancellation(order, "Original reason", UUID.randomUUID());
        var confirmation = new Confirmation(alice, executor);
        var review = confirmation.review(UUID.fromString(request.path("requestId").asString()));
        sql(
                "UPDATE cancellation_requests SET reason='Changed reason' WHERE id='"
                        + review.requestId()
                        + "'");
        assertThrows(IllegalStateException.class, () -> confirmation.confirm(review, "yes"));
        assertEquals("PROCESSING", sql("SELECT status FROM orders WHERE id=" + order));
    }

    @Test
    void noDatabaseDriverOrDataSourceInAgentRuntime() {
        assertThrows(ClassNotFoundException.class, () -> Class.forName("com.mysql.cj.jdbc.Driver"));
        assertThrows(
                ClassNotFoundException.class,
                () -> Class.forName("com.zaxxer.hikari.HikariDataSource"));
    }
}
