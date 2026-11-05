package com.bazlur.mrs;

import module java.base;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real HTTPS/SCRAM/MRS/SQL integration. No mock REST service or skipped fallback. */
class MrsWorkflowIT {
    static final Path ROOT = Path.of("..").toAbsolutePath().normalize();
    static final URI URL = URI.create(System.getenv().getOrDefault("MRS_URL", "https://localhost:8443/orders"));
    static final Path CA = ROOT.resolve(".mrs/tls/server.crt");
    MrsClient alice, bob, executor;
    long order;

    static MrsClient login(String name) throws Exception {
        return new MrsClient(URL, CA).login(name, (name + "-Local!26").toCharArray());
    }
    static String sql(String statement) throws Exception {
        var process = new ProcessBuilder("docker", "compose", "-f", "compose.mrs.yml", "exec", "-T",
                "-e", "MYSQL_PWD=local-mrs-root-only", "mysql", "mysql", "-uroot", "-Dorder_demo", "-Nse", statement)
                .directory(ROOT.toFile()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.waitFor() != 0) throw new IllegalStateException(output);
        return output.strip();
    }
    @BeforeEach void setup() throws Exception {
        assertEquals("1", sql("SELECT version FROM mrs_demo_setup"));
        alice=login("alice"); bob=login("bob"); executor=login("executor");
        order=10_000_000_000L + new SecureRandom().nextInt(1_000_000_000);
        sql("INSERT INTO orders(id,customer_id,status) VALUES ("+order+",42,'PROCESSING')");
    }
    @AfterEach void cleanup() throws Exception {
        sql("DELETE a FROM action_audit a JOIN cancellation_requests r ON r.id=a.request_id WHERE r.order_id="+order
                +"; DELETE FROM cancellation_requests WHERE order_id="+order+"; DELETE FROM orders WHERE id="+order);
    }

    @Test void javaReadsNativeDocumentAndCannotReadAnotherCustomer() throws Exception {
        var read = new MrsOrders(alice).getOrder(order);
        assertTrue(read.path("ok").asBoolean());
        assertEquals(order, read.at("/document/_id").asLong());
        assertEquals("ORDER_UNAVAILABLE", new MrsOrders(bob).getOrder(order).path("code").asString());
        assertFalse(new MrsOrders(alice).listOrders().path("orders").isEmpty());
    }

    @Test void confirmationRequiresExactHumanInputAndOwnRequest() throws Exception {
        var request = new MrsOrders(alice).requestCancellation(order, "Duplicate order", UUID.randomUUID());
        UUID id = UUID.fromString(request.path("requestId").asString());
        var confirmation = new Confirmation(alice, executor);
        var review = confirmation.review(id);
        assertEquals(order, review.orderId());
        assertThrows(IllegalArgumentException.class, () -> confirmation.confirm(review, "true"));
        assertEquals("PROCESSING", sql("SELECT status FROM orders WHERE id="+order));
        var denied = assertThrows(MrsClient.Failure.class, () -> new Confirmation(bob, executor).review(id));
        assertEquals(404, denied.status());
        assertEquals("CONFIRMED", confirmation.confirm(review, "CONFIRM "+id).path("state").asString());
        assertEquals("CANCELLED", sql("SELECT status FROM orders WHERE id="+order));
        assertEquals("1", sql("SELECT COUNT(*) FROM action_audit WHERE event='CANCELLED' AND request_id='"+id+"'"));
    }

    @Test void javaCredentialCannotBypassTheConfirmationRole() throws Exception {
        var request = new MrsOrders(alice).requestCancellation(order, "Test", UUID.randomUUID());
        var denied = assertThrows(MrsClient.Failure.class, () -> alice.call(MrsClient.Operation.CONFIRM,
                Map.of("requestId", request.path("requestId").asString(), "ownerHex", alice.principalId())));
        assertEquals(403, denied.status());
        assertEquals("PROCESSING", sql("SELECT status FROM orders WHERE id="+order));
    }

    @Test void stateChangeBetweenReviewAndConfirmationIsRejected() throws Exception {
        var request = new MrsOrders(alice).requestCancellation(order, "Test", UUID.randomUUID());
        var confirmation = new Confirmation(alice, executor);
        var review = confirmation.review(UUID.fromString(request.path("requestId").asString()));
        sql("UPDATE orders SET status='SHIPPED' WHERE id="+order);
        assertEquals("ORDER_NOT_CANCELLABLE", confirmation.confirm(review, "CONFIRM "+review.requestId()).path("code").asString());
        assertEquals("SHIPPED", sql("SELECT status FROM orders WHERE id="+order));
    }

    @Test void noDatabaseDriverOrDataSourceInAgentRuntime() {
        assertThrows(ClassNotFoundException.class, () -> Class.forName("com.mysql.cj.jdbc.Driver"));
        assertThrows(ClassNotFoundException.class, () -> Class.forName("com.zaxxer.hikari.HikariDataSource"));
    }
}
