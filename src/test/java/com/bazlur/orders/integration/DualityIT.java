package com.bazlur.orders.integration;

import com.bazlur.orders.ai.MockAgent;
import com.bazlur.orders.ai.AgentOrderReader;
import com.bazlur.orders.ai.CustomerOrderTools;
import dev.langchain4j.invocation.InvocationParameters;
import com.bazlur.orders.ai.OllamaAgent;
import com.bazlur.orders.config.OllamaProperties;
import com.bazlur.orders.OrdersApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import com.bazlur.orders.application.OrderException;
import com.bazlur.orders.json.Json;
import com.bazlur.orders.persistence.ConventionalOrderRepository;
import com.bazlur.orders.persistence.OrderDocumentRepository;
import module java.base;
import module java.sql;
import module java.net.http;
import java.time.Duration;

import com.mysql.cj.jdbc.MysqlDataSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.bazlur.orders.application.OrderException.Kind.*;

@TestMethodOrder(MethodOrderer.MethodName.class)
class DualityIT {
    // Tests connect without a pool so each step sees exactly one connection per open().
    private record Login(String url, String user, String password) {
        DataSource dataSource() {
            var source = new MysqlDataSource();
            source.setURL(url);
            source.setUser(user);
            source.setPassword(password);
            return source;
        }
    }

    private static Login root, api, agent;
    private static DataSource db;
    private static DataSource apiDb;
    private static DataSource agentDb;
    private static OrderDocumentRepository repository;
    private static String schema;
    private static String version;
    private static final List<String> evidence = new ArrayList<>();

    @RegisterExtension static final TestWatcher RESULTS = new TestWatcher() {
        @Override public void testSuccessful(ExtensionContext context) { evidence.add("PASS " + context.getDisplayName()); }
        @Override public void testFailed(ExtensionContext context, Throwable cause) { evidence.add("FAIL " + context.getDisplayName() + ": " + cause); }
    };

    @BeforeAll static void initialize() throws Exception {
        schema = "order_duality_it_" + UUID.randomUUID().toString().replace("-", "");
        root = new Login(env("TEST_DB_URL", "jdbc:mysql://127.0.0.1:3307/?sslMode=DISABLED&allowPublicKeyRetrieval=true&connectionTimeZone=UTC"),
                env("TEST_DB_USER", "root"), env("TEST_DB_PASSWORD", "local-root-only"));
        db = root.dataSource();
        try (var c = db.getConnection()) {
            try (var s = c.createStatement(); var rs = s.executeQuery("SELECT VERSION(), @@version_comment, @@sql_mode, @@transaction_isolation")) {
                rs.next();
                version = rs.getString(1) + " | " + rs.getString(2) + " | " + rs.getString(3) + " | " + rs.getString(4);
                assertEquals("9.7.2", rs.getString(1), "This evidence suite targets the pinned server version");
            }
            script(c, "sql/schema.sql");
            script(c, "sql/duality-views.sql");
        }
        // The URL can contain a catalog, so select the isolated schema through the URL properties.
        String url = root.url();
        int q = url.indexOf('?');
        String base = q < 0 ? url : url.substring(0, q);
        String properties = q < 0 ? "" : url.substring(q);
        root = new Login(base.substring(0, base.lastIndexOf('/') + 1) + schema + properties, root.user(), root.password());
        db = root.dataSource();
        repository = new OrderDocumentRepository(db);
        try (var c = db.getConnection(); var s = c.createStatement()) {
            // Use the same least-privilege grants as the real application, in an isolated account.
            String grants = Files.readString(Path.of("sql/users.sql")).replace("order_demo", schema)
                    .replace("order_api", schema.substring(0, 30)).replaceAll("(?m)^\\s*--.*$", "");
            for (String part : grants.split(";")) if (!part.isBlank()) s.execute(part);
        }
        api = new Login(root.url(), schema.substring(0, 30), "local-api-only");
        apiDb = api.dataSource();
        try (var c = db.getConnection(); var s = c.createStatement()) {
            String grants = Files.readString(Path.of("sql/agent-user.sql")).replace("order_demo", schema)
                    .replace("order_agent", schema.substring(0, 29) + "_a").replaceAll("(?m)^\\s*--.*$", "");
            for (String part : grants.split(";")) if (!part.isBlank()) s.execute(part);
        }
        agent = new Login(root.url(), schema.substring(0, 29) + "_a", "local-agent-only");
        agentDb = agent.dataSource();
    }

    @BeforeEach void seed() throws Exception {
        try (var c = db.getConnection(); var s = c.createStatement()) {
            s.executeUpdate("DELETE FROM order_items");
            s.executeUpdate("DELETE FROM orders");
            s.executeUpdate("DELETE FROM products");
            s.executeUpdate("DELETE FROM customers");
            script(c, "sql/seed.sql");
        }
    }

    @AfterAll static void finish() throws Exception {
        Files.createDirectories(Path.of("target/evidence"));
        Files.writeString(Path.of("target/evidence/experiments.txt"),
                "Run: " + java.time.Instant.now() + "\nServer: " + version + "\nJava: " + System.getProperty("java.version")
                + "\n\n" + String.join("\n", evidence) + "\n");
        if (db != null && schema != null) try (var c = db.getConnection(); var s = c.createStatement()) {
            s.execute("DROP USER IF EXISTS '" + schema.substring(0, 30) + "'@'%'");
            s.execute("DROP USER IF EXISTS '" + schema.substring(0, 29) + "_a'@'%'");
            s.execute("DROP DATABASE IF EXISTS " + schema);
        }
    }

    private static String env(String name, String fallback) { return System.getenv().getOrDefault(name, fallback); }

    private static void script(Connection c, String file) throws Exception {
        String sql = Files.readString(Path.of(file)).replace("order_demo", schema).replaceAll("(?m)^\\s*--.*$", "");
        try (var s = c.createStatement()) {
            for (String part : sql.split(";")) if (!part.isBlank()) s.execute(part);
        }
    }

    private ObjectNode order(long id) throws Exception { return (ObjectNode) Json.parse(repository.getOrder(id).orElseThrow()); }
    private int replace(long id, JsonNode document) throws Exception {
        try (var c = db.getConnection()) { return repository.replace(c, id, document.toString()); }
    }
    private void sql(String sql) throws Exception { try (var c = db.getConnection(); var s = c.createStatement()) { s.execute(sql); } }
    private String scalar(String sql) throws Exception {
        try (var c = db.getConnection(); var s = c.createStatement(); var rs = s.executeQuery(sql)) { assertTrue(rs.next()); return rs.getString(1); }
    }
    private SQLException rejected(String label, org.junit.jupiter.api.function.Executable action) {
        SQLException e = assertThrows(SQLException.class, action);
        evidence.add(label + " -> " + e.getErrorCode() + " / " + e.getSQLState() + " / " + e.getMessage());
        return e;
    }
    private void save(String file, JsonNode json) throws Exception {
        Files.createDirectories(Path.of("target/evidence"));
        Files.writeString(Path.of("target/evidence", file), Json.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(json) + "\n");
    }

    @Test void readOrderWithCustomerAndMultipleItems() throws Exception {
        var o = order(1001);
        assertEquals(1001, o.path("_id").asLong());
        assertEquals("Alice Rahman", o.at("/customer/name").asString());
        assertEquals("alice@example.com", o.at("/customer/email").asString());
        assertEquals(2, o.path("items").size());
        assertEquals(32, o.at("/_metadata/etag").asString().length());
        assertTrue(o.path("items").findValuesAsString("sku").contains("KB-001"));
        assertEquals(0, Integer.parseInt(scalar("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND data_type='json' AND table_name IN ('customers','orders','order_items','products')")));
        save("order-1001.json", o);
    }

    @Test void conventionalMappingHasTheSameBusinessData() throws Exception {
        var expected = order(1001);
        expected.remove("_metadata");
        var actual = Json.MAPPER.valueToTree(new ConventionalOrderRepository(db).getOrder(1001).orElseThrow());
        assertTrue(Json.sameValue(canonical(expected), canonical(actual)), "Both paths must return the same business values");
        assertTrue(new ConventionalOrderRepository(db).getOrder(9999).isEmpty());
    }

    private JsonNode canonical(JsonNode input) {
        ObjectNode copy = (ObjectNode) input.deepCopy();
        for (String field : List.of("createdAt", "updatedAt")) {
            copy.put(field, java.time.LocalDateTime.parse(copy.path(field).asString().replace(' ', 'T')).toString());
        }
        var items = new ArrayList<JsonNode>();
        copy.path("items").forEach(items::add);
        items.sort(Comparator.comparingLong(i -> i.path("id").asLong()));
        copy.set("items", Json.MAPPER.valueToTree(items));
        return copy;
    }

    @Test void emptyCollectionsAndMissingRoots() throws Exception {
        var empty = order(1005).path("items");
        evidence.add("Empty order items: " + empty);
        assertTrue(empty.isNull());
        assertTrue(repository.getOrder(9999).isEmpty());
        var customer = Json.parse(repository.getCustomerOrders(44).orElseThrow());
        assertTrue(customer.path("orders").isNull());
    }

    @Test void statusUpdateChangesTheRelationalRow() throws Exception {
        var original = order(1001);
        var edited = original.deepCopy().put("status", "SHIPPED");
        replace(1001, edited);
        var after = order(1001);
        assertEquals("SHIPPED", scalar("SELECT status FROM orders WHERE id=1001"));
        assertEquals(original.path("customer"), after.path("customer"));
        assertEquals(original.path("items"), after.path("items"));
        assertNotEquals(original.at("/_metadata/etag"), after.at("/_metadata/etag"));
        assertNotEquals(original.path("updatedAt"), after.path("updatedAt"));
        evidence.add("updatedAt after status update: " + after.path("updatedAt"));
        save("order-1001-shipped.json", after);
    }

    @Test void oneReplacementUpdatesInsertsAndDeletesLines() throws Exception {
        var o = order(1001).put("status", "SHIPPED");
        ArrayNode items = (ArrayNode) o.path("items");
        ObjectNode kept = ((ObjectNode) items.get(0)).deepCopy().put("quantity", 3);
        ObjectNode added = kept.deepCopy().put("id", 9010).put("quantity", 1).put("unitPrice", 119);
        added.set("product", Json.parse("{\"id\":503,\"sku\":\"DK-003\",\"name\":\"USB-C Dock\"}"));
        items.removeAll().add(kept).add(added);
        replace(1001, o);
        assertEquals("3", scalar("SELECT quantity FROM order_items WHERE id=9001"));
        assertEquals("0", scalar("SELECT COUNT(*) FROM order_items WHERE id=9002"));
        assertEquals("503", scalar("SELECT product_id FROM order_items WHERE id=9010"));
        assertEquals("1001", scalar("SELECT order_id FROM order_items WHERE id=9010"));
        assertEquals("4", scalar("SELECT COUNT(*) FROM products"));
    }

    @Test void insertAndDeleteAnOrderPreserveSharedEntities() throws Exception {
        var o = order(1001).put("_id", 1010);
        o.remove("_metadata");
        ((ObjectNode) o.path("items").get(0)).put("id", 9010);
        ((ObjectNode) o.path("items").get(1)).put("id", 9011);
        try (var c = db.getConnection(); var s = c.prepareStatement("INSERT INTO orders_dv VALUES (?)")) {
            s.setString(1, o.toString()); s.executeUpdate();
        }
        assertEquals("42", scalar("SELECT customer_id FROM orders WHERE id=1010"));
        assertEquals("2", scalar("SELECT COUNT(*) FROM order_items WHERE order_id=1010"));
        sql("DELETE FROM orders_dv WHERE data->'$._id'=1010");
        assertTrue(repository.getOrder(1010).isEmpty());
        assertEquals("0", scalar("SELECT COUNT(*) FROM order_items WHERE order_id=1010"));
        assertEquals("3", scalar("SELECT COUNT(*) FROM customers"));
        assertEquals("4", scalar("SELECT COUNT(*) FROM products"));
    }

    @Test void invalidJsonAndUnknownFieldsAreRejected() throws Exception {
        var before = order(1001);
        rejected("Malformed JSON", () -> { try (var c = db.getConnection()) { repository.replace(c, 1001, "{broken"); } });
        rejected("Unmapped key", () -> replace(1001, before.deepCopy().put("discount", 10)));
        var wrongType = before.deepCopy();
        wrongType.set("status", Json.MAPPER.createObjectNode());
        rejected("Wrong JSON type", () -> replace(1001, wrongType));
        assertEquals(before, order(1001));
    }

    @Test void missingScalarAndChangedRootIdentityAreRejected() throws Exception {
        var before = order(1001);
        var missing = before.deepCopy(); missing.remove("status");
        rejected("Missing projected scalar", () -> replace(1001, missing));
        rejected("Changed root PK", () -> replace(1001, before.deepCopy().put("_id", 8888)));
        assertEquals(before, order(1001));
    }

    @Test void failedMultiTableUpdateIsAtomic() throws Exception {
        var before = order(1001);
        var bad = before.deepCopy().put("status", "SHIPPED");
        ((ObjectNode) bad.path("items").get(0)).put("quantity", 0);
        rejected("CHECK failure after root edit", () -> replace(1001, bad));
        assertEquals(before, order(1001));
        assertEquals("PROCESSING", scalar("SELECT status FROM orders WHERE id=1001"));
        assertEquals("2", scalar("SELECT quantity FROM order_items WHERE id=9001"));
    }

    @Test void invalidStatusAndRelationshipAreRejected() throws Exception {
        var before = order(1001);
        rejected("Status CHECK", () -> replace(1001, before.deepCopy().put("status", "DELIVERED")));
        var bad = before.deepCopy();
        ((ObjectNode) bad.path("customer")).put("id", 9999);
        rejected("Missing shared customer", () -> replace(1001, bad));
        rejected("Direct FK violation", () -> sql("UPDATE orders SET customer_id=9999 WHERE id=1001"));
        assertEquals(before, order(1001));
    }

    @Test void readonlySharedFieldEditIsRejectedAtomically() throws Exception {
        var before = order(1001);
        var edit = before.deepCopy().put("status", "SHIPPED");
        ((ObjectNode) edit.path("customer")).put("name", "Not Alice");
        assertEquals(6497, rejected("Read-only customer field", () -> replace(1001, edit)).getErrorCode());
        assertEquals("Alice Rahman", scalar("SELECT name FROM customers WHERE id=42"));
        assertEquals(before, order(1001));
    }

    @Test void missingEtagAllowsAStaleDocumentToOverwriteAChange() throws Exception {
        var edit = order(1001).put("status", "SHIPPED"); edit.remove("_metadata");
        sql("UPDATE orders SET status='CANCELLED' WHERE id=1001");
        replace(1001, edit);
        assertEquals("SHIPPED", scalar("SELECT status FROM orders WHERE id=1001"));
        evidence.add("Missing etag: stale document overwrote CANCELLED with SHIPPED");
    }

    @Test void staleDocumentDetectsRelationalAndSharedChanges() throws Exception {
        for (String change : List.of("UPDATE orders SET status='CANCELLED' WHERE id=1001",
                "UPDATE order_items SET quantity=quantity+1 WHERE id=9001",
                "UPDATE customers SET name='Alice R.' WHERE id=42",
                "UPDATE products SET name='Updated keyboard' WHERE id=501")) {
            var stale = order(1001).put("status", "SHIPPED");
            sql(change);
            var afterConcurrentWrite = order(1001);
            rejected("Stale document after " + change, () -> replace(1001, stale));
            assertEquals(afterConcurrentWrite, order(1001));
        }
    }

    @Test void simultaneousWritersCannotBothWin() throws Exception {
        var original = order(1001);
        var gate = new CountDownLatch(1);
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = workers.submit(() -> writeAtGate(original.deepCopy().put("status", "SHIPPED"), gate));
            var second = workers.submit(() -> writeAtGate(original.deepCopy().put("status", "CANCELLED"), gate));
            gate.countDown();
            var outcomes = List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS));
            evidence.add("Concurrent writers: " + outcomes);
            assertEquals(1, outcomes.stream().filter("committed"::equals).count());
        }
    }

    private String writeAtGate(JsonNode document, CountDownLatch gate) throws Exception {
        try (var c = db.getConnection()) {
            gate.await();
            try { repository.replace(c, 1001, document.toString()); return "committed"; }
            catch (SQLException e) { return e.getErrorCode() + " " + e.getMessage(); }
        }
    }

    @Test void unsupportedStatementForms() throws Exception {
        rejected("Multi-root update", () -> sql("UPDATE orders_dv SET data=JSON_SET(data,'$.status','SHIPPED')"));
        rejected("INSERT SELECT", () -> sql("INSERT INTO orders_dv SELECT data FROM orders_dv"));
        rejected("INSERT column list", () -> sql("INSERT INTO orders_dv(data) VALUES ('{\"_id\":1010}')"));
        rejected("Read-only view UPDATE", () -> sql("UPDATE customer_orders_dv SET data=JSON_SET(data,'$.name','X') WHERE data->'$._id'=42"));
    }

    @Test void unsupportedViewShapes() throws Exception {
        rejected("Root WHERE", () -> sql("CREATE JSON DUALITY VIEW bad_filter AS SELECT JSON_DUALITY_OBJECT('_id':id,'status':status) FROM orders WHERE status='PENDING'"));
        rejected("Calculated value", () -> sql("CREATE JSON DUALITY VIEW bad_calc AS SELECT JSON_DUALITY_OBJECT('_id':id,'total':quantity*unit_price) FROM order_items"));
        rejected("Missing item PK", () -> sql("CREATE JSON DUALITY VIEW bad_pk AS SELECT JSON_DUALITY_OBJECT('_id':o.id,'items':(SELECT JSON_ARRAYAGG(JSON_DUALITY_OBJECT('quantity':i.quantity)) FROM order_items i WHERE i.order_id=o.id)) FROM orders o"));
        sql("CREATE TABLE composite_probe (a INT, b INT, PRIMARY KEY(a,b))");
        try { rejected("Composite PK", () -> sql("CREATE JSON DUALITY VIEW bad_composite AS SELECT JSON_DUALITY_OBJECT('_id':a,'b':b) FROM composite_probe")); }
        finally { sql("DROP TABLE composite_probe"); }
    }

    @Test void updateOnlyAnnotationIsAcceptedAndEnforced() throws Exception {
        sql("CREATE JSON DUALITY VIEW status_only_dv AS SELECT JSON_DUALITY_OBJECT(WITH(UPDATE) '_id':id,'status':status) FROM orders");
        try {
            sql("UPDATE status_only_dv SET data=JSON_SET(data,'$.status','SHIPPED') WHERE data->'$._id'=1001");
            assertEquals("SHIPPED", scalar("SELECT status FROM orders WHERE id=1001"));
            rejected("UPDATE-only view INSERT", () -> sql("INSERT INTO status_only_dv VALUES ('{\"_id\":1010,\"status\":\"PENDING\"}')"));
            rejected("UPDATE-only view DELETE", () -> sql("DELETE FROM status_only_dv WHERE data->'$._id'=1001"));
            evidence.add("WITH(UPDATE) alone: creation and update succeeded; insert and delete rejected");
        } finally { sql("DROP VIEW status_only_dv"); }
    }

    @Test void echoedTimestampDefeatsOnUpdateWithoutTheTrigger() throws Exception {
        sql("DROP TRIGGER orders_touch");
        try {
            var before = order(1001);
            replace(1001, before.deepCopy().put("status", "SHIPPED"));
            assertEquals(before.path("updatedAt"), order(1001).path("updatedAt"));
            evidence.add("Without orders_touch trigger: echoed updatedAt suppressed ON UPDATE CURRENT_TIMESTAMP");
        } finally { sql("CREATE TRIGGER orders_touch BEFORE UPDATE ON orders FOR EACH ROW SET NEW.updated_at=CURRENT_TIMESTAMP(6)"); }
    }

    @Test void explicitTransactionRollbackUndoesDocumentWrite() throws Exception {
        var before = order(1001);
        try (var c = db.getConnection()) {
            c.setAutoCommit(false);
            repository.replace(c, 1001, before.deepCopy().put("status", "SHIPPED").toString());
            assertEquals("SHIPPED", Json.parse(repository.getOrder(c, 1001).orElseThrow()).path("status").asString());
            c.rollback();
        }
        assertEquals(before, order(1001));
    }

    @Test void removingTheCollectionDeletesAllItsRows() throws Exception {
        var edit = order(1001); edit.remove("items");
        replace(1001, edit);
        assertEquals("0", scalar("SELECT COUNT(*) FROM order_items WHERE order_id=1001"));
        assertTrue(order(1001).path("items").isNull());
        assertEquals("4", scalar("SELECT COUNT(*) FROM products"));
    }

    @Test void selectExplainWorksButDmlExplainIsRejected() throws Exception {
        for (String statement : List.of("EXPLAIN SELECT data FROM orders_dv WHERE data->'$._id'=1001",
                "EXPLAIN SELECT o.id,c.name,i.quantity,p.sku FROM orders o JOIN customers c ON c.id=o.customer_id LEFT JOIN order_items i ON i.order_id=o.id LEFT JOIN products p ON p.id=i.product_id WHERE o.id=1001")) {
            try (var c = db.getConnection(); var s = c.createStatement(); var rs = s.executeQuery(statement)) {
                var out = new StringBuilder(statement).append('\n');
                while (rs.next()) {
                    for (int i=1;i<=rs.getMetaData().getColumnCount();i++) out.append(rs.getMetaData().getColumnLabel(i)).append('=').append(rs.getString(i)).append(' ');
                    out.append('\n');
                }
                evidence.add(out.toString());
            }
        }
        assertEquals(6482, rejected("EXPLAIN UPDATE", () -> sql("EXPLAIN UPDATE orders_dv SET data=JSON_SET(data,'$.status','SHIPPED') WHERE data->'$._id'=1001")).getErrorCode());
    }

    @Test void numericStringIsCoercedRatherThanRejected() throws Exception {
        var edit = order(1001);
        ((ObjectNode) edit.path("items").get(0)).put("quantity", "3");
        replace(1001, edit);
        assertEquals("3", scalar("SELECT quantity FROM order_items WHERE id=9001"));
        assertTrue(order(1001).path("items").get(0).path("quantity").isIntegralNumber());
        evidence.add("quantity string '3' was converted to SQL INT 3");
    }

    @Test void unprojectedCatalogPriceDoesNotInvalidateTheOrderEtag() throws Exception {
        var before = order(1001);
        sql("UPDATE products SET price=199.00 WHERE id=501");
        assertEquals(before, order(1001));
        assertEquals("149.00", scalar("SELECT unit_price FROM order_items WHERE id=9001"));
    }

    @Test void lineForeignKeyFailureRollsBackTheInsertedOrder() throws Exception {
        // A scalar-FK view isolates actual FK validation from read-only singleton annotations.
        sql("CREATE JSON DUALITY VIEW fk_probe_dv AS SELECT JSON_DUALITY_OBJECT(WITH(INSERT,UPDATE,DELETE) '_id':o.id,'customerId':o.customer_id,'status':o.status,'items':(SELECT JSON_ARRAYAGG(JSON_DUALITY_OBJECT(WITH(INSERT,UPDATE,DELETE) 'id':i.id,'productId':i.product_id,'quantity':i.quantity,'unitPrice':i.unit_price)) FROM order_items i WHERE i.order_id=o.id)) FROM orders o");
        try {
            assertEquals(1452, rejected("FK failure during document insert", () -> sql("INSERT INTO fk_probe_dv VALUES ('{\"_id\":1010,\"customerId\":42,\"status\":\"PENDING\",\"items\":[{\"id\":9010,\"productId\":9999,\"quantity\":1,\"unitPrice\":10}]}')")).getErrorCode());
            assertEquals("0", scalar("SELECT COUNT(*) FROM orders WHERE id=1010"));
            assertEquals("0", scalar("SELECT COUNT(*) FROM order_items WHERE id=9010"));
        } finally { sql("DROP VIEW fk_probe_dv"); }
    }

    @Test void childOrderingAndFlattenedJoinsAreRejected() throws Exception {
        rejected("Child ORDER BY", () -> sql("CREATE JSON DUALITY VIEW bad_ordering AS SELECT JSON_DUALITY_OBJECT('_id':o.id,'items':(SELECT JSON_ARRAYAGG(JSON_DUALITY_OBJECT('id':i.id)) FROM order_items i WHERE i.order_id=o.id ORDER BY i.id)) FROM orders o"));
        rejected("Flattened product JOIN", () -> sql("CREATE JSON DUALITY VIEW bad_flatten AS SELECT JSON_DUALITY_OBJECT('_id':o.id,'items':(SELECT JSON_ARRAYAGG(JSON_DUALITY_OBJECT('id':i.id,'sku':p.sku)) FROM order_items i JOIN products p ON p.id=i.product_id WHERE i.order_id=o.id)) FROM orders o"));
    }

    @Test void applicationAccountCannotWriteBaseTablesOrInsertOrders() throws Exception {
        try (var c = apiDb.getConnection(); var s = c.createStatement()) {
            assertEquals(1142, rejected("API base-table write", () -> s.executeUpdate("UPDATE orders SET status='SHIPPED' WHERE id=1001")).getErrorCode());
            assertEquals(1142, rejected("API view INSERT", () -> s.executeUpdate("INSERT INTO orders_dv VALUES ('{\"_id\":1010}')")).getErrorCode());
        }
    }

    @Test void jsonColumnCopyDoesNotTrackRelationalChanges() throws Exception {
        try (var c = db.getConnection(); var s = c.createStatement()) {
            s.execute("CREATE TEMPORARY TABLE document_copy (id BIGINT PRIMARY KEY, body JSON NOT NULL)");
            s.executeUpdate("INSERT INTO document_copy SELECT 1001,data FROM orders_dv WHERE data->'$._id'=1001");
            s.executeUpdate("UPDATE customers SET name='Alice R.' WHERE id=42");
            try (var rs = s.executeQuery("SELECT body->>'$.customer.name' FROM document_copy")) { rs.next(); assertEquals("Alice Rahman", rs.getString(1)); }
            assertEquals("Alice R.", order(1001).at("/customer/name").asString());
            s.executeUpdate("UPDATE document_copy SET body=JSON_SET(body,'$.customer.id',9999) WHERE id=1001");
            try (var rs = s.executeQuery("SELECT body->>'$.customer.id' FROM document_copy")) { rs.next(); assertEquals("9999", rs.getString(1)); }
        }
    }

    @Test void toolBoundaryAndMockUseActualViewData() throws Exception {
        var reader = new AgentOrderReader(repository);
        String result = reader.getCustomerOrders(42);
        var node = Json.parse(result);
        assertEquals(3, node.path("orders").size());
        assertFalse(node.has("email"));
        var answer = MockAgent.summarize(result);
        assertTrue(answer.contains("#1003: pending"));
        assertTrue(answer.contains("#1001: still open"));
        assertTrue(answer.indexOf("#1003") < answer.indexOf("#1002"));
        save("customer-42-orders.json", node);
        Files.writeString(Path.of("target/evidence/agent-response.txt"), answer);
    }

    @Test void httpReadUpdateValidationAndConflict() throws Exception {
        try (var context = new SpringApplicationBuilder(OrdersApplication.class).run(
                "--server.port=0",
                "--orders.database.url=" + api.url(),
                "--orders.database.user=" + api.user(),
                "--orders.database.password=" + api.password(),
                "--orders.database.agent-user=" + agent.user(),
                "--orders.database.agent-password=" + agent.password());
             var client = HttpClient.newHttpClient()) {
            var base = "http://127.0.0.1:" + context.getEnvironment().getProperty("local.server.port");
            var get = client.send(HttpRequest.newBuilder(URI.create(base + "/orders/1001")).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, get.statusCode());
            assertTrue(get.headers().firstValue("Content-Type").orElseThrow().contains("application/json"));
            assertEquals(order(1001), Json.parse(get.body()));
            ObjectNode edited = (ObjectNode) Json.parse(get.body()); edited.put("status", "SHIPPED");
            var put = HttpRequest.newBuilder(URI.create(base + "/orders/1001")).header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString(edited.toString())).build();
            var response = client.send(put, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode(), response.body());
            assertEquals("SHIPPED", scalar("SELECT status FROM orders WHERE id=1001"));
            var conflict = client.send(put, HttpResponse.BodyHandlers.ofString());
            assertEquals(409, conflict.statusCode());
            assertTrue(conflict.headers().firstValue("Content-Type").orElseThrow().contains("application/problem+json"));
            assertEquals(409, Json.parse(conflict.body()).path("status").asInt());
            var unauthorizedEdit = order(1001);
            ((ObjectNode) unauthorizedEdit.path("customer")).put("name", "Spoofed");
            assertEquals(422, put(client, base, unauthorizedEdit.toString()).statusCode());
            assertEquals(400, put(client, base, "{broken").statusCode());
            assertEquals(428, put(client, base, "{\"_id\":1001,\"status\":\"SHIPPED\"}").statusCode());
            var unsupported = client.send(HttpRequest.newBuilder(URI.create(base + "/orders/1001"))
                    .DELETE().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(405, unsupported.statusCode());
            assertTrue(unsupported.headers().firstValue("Allow").orElseThrow().contains("GET"));
            assertTrue(unsupported.headers().firstValue("Allow").orElseThrow().contains("PUT"));
            assertEquals(415, client.send(HttpRequest.newBuilder(URI.create(base + "/orders/1001"))
                    .PUT(HttpRequest.BodyPublishers.ofString(edited.toString())).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
            assertEquals(413, put(client, base, " ".repeat(64 * 1024 + 1)).statusCode());
            assertEquals(400, client.send(HttpRequest.newBuilder(URI.create(base + "/orders/999999999999999999999"))
                    .build(), HttpResponse.BodyHandlers.ofString()).statusCode());
            assertEquals(404, client.send(HttpRequest.newBuilder(URI.create(base + "/orders/9999")).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
            assertEquals(200, client.send(HttpRequest.newBuilder(URI.create(base + "/orders/1001/relational")).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
            assertEquals(200, client.send(HttpRequest.newBuilder(URI.create(base + "/customers/42/orders")).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
        }
    }

    @Test void orderReadPathsReturnTheSameOrderAndRecordTheirSql() throws Throwable {
        try (var context = new SpringApplicationBuilder(OrdersApplication.class).run(
                "--server.port=0",
                "--orders.database.url=" + api.url(),
                "--orders.database.user=" + api.user(),
                "--orders.database.password=" + api.password(),
                "--orders.database.agent-user=" + agent.user(),
                "--orders.database.agent-password=" + agent.password());
             var client = HttpClient.newHttpClient()) {
            var base = "http://127.0.0.1:" + context.getEnvironment().getProperty("local.server.port") + "/orders/";
            var view = (ObjectNode) Json.parse(get(client, base + "1001"));
            view.remove("_metadata");
            for (var suffix : List.of("/relational", "/jpa", "/spring-data-jdbc")) {
                assertTrue(Json.sameValue(canonical(view), canonical(Json.parse(get(client, base + "1001" + suffix)))),
                        suffix + " must return the same business data as the duality view");
                // Known contract difference: the view returns null for an order without lines, Java lists are empty.
                assertTrue(Json.parse(get(client, base + "1005" + suffix)).path("items").isEmpty(), suffix);
            }
            assertTrue(Json.parse(get(client, base + "1005")).path("items").isNull());

            var counts = new LinkedHashMap<String, Integer>();
            for (var suffix : List.of("", "/relational", "/jpa", "/spring-data-jdbc")) {
                var statements = selectsSentBy(api.user(), () -> get(client, base + "1001" + suffix));
                counts.put(suffix.isEmpty() ? "duality view" : suffix.substring(1), statements.size());
                evidence.add("SQL for GET /orders/1001" + suffix + " (" + statements.size() + " SELECT):");
                statements.forEach(statement -> evidence.add("  " + statement));
            }
            evidence.add("SELECT statements per order read: " + counts);
            assertEquals(1, counts.get("duality view"));
            assertEquals(1, counts.get("relational"));
            assertEquals(1, counts.get("jpa"), "the join fetch must load the whole order in one query");
            assertTrue(counts.get("spring-data-jdbc") > 1, "customer and products live outside the order aggregate");
        }
    }

    private String get(HttpClient client, String url) throws Exception {
        var response = client.send(HttpRequest.newBuilder(URI.create(url)).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), url);
        return response.body();
    }

    // Records the SELECT statements one account sends while the action runs, using MySQL's general query log.
    private List<String> selectsSentBy(String user, org.junit.jupiter.api.function.Executable action) throws Throwable {
        String output = scalar("SELECT @@GLOBAL.log_output");
        sql("SET GLOBAL log_output = 'TABLE'");
        sql("TRUNCATE TABLE mysql.general_log");
        sql("SET GLOBAL general_log = 'ON'");
        try {
            action.execute();
        } finally {
            sql("SET GLOBAL general_log = 'OFF'");
            sql("SET GLOBAL log_output = '" + output + "'");
        }
        var statements = new ArrayList<String>();
        try (var c = db.getConnection(); var s = c.prepareStatement("""
                SELECT CONVERT(argument USING utf8mb4) FROM mysql.general_log
                WHERE user_host LIKE ? AND command_type IN ('Query', 'Execute')
                ORDER BY event_time""")) {
            s.setString(1, user + "[%");
            try (var rs = s.executeQuery()) {
                while (rs.next()) {
                    var statement = rs.getString(1).replaceAll("\\s+", " ").trim();
                    if (statement.toLowerCase(Locale.ROOT).startsWith("select") && !statement.contains("@@")) statements.add(statement);
                }
            }
        }
        return statements;
    }

    private HttpResponse<String> put(HttpClient client, String base, String body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(base + "/orders/1001"))
                .header("Content-Type", "application/json").PUT(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test void agentToolsEnforceCustomerScopeAndReadOnlyDatabaseGrants() throws Exception {
        var tools = new CustomerOrderTools(new AgentOrderReader(new OrderDocumentRepository(agentDb)));
        var alice = InvocationParameters.from("customerId", 42L);
        assertEquals(42, Json.parse(tools.getCustomerOrders(42, alice)).path("_id").asInt());
        assertEquals(1001, Json.parse(tools.getOrder(1001, alice)).path("_id").asInt());
        assertEquals(NOT_FOUND, assertThrows(OrderException.class, () -> tools.getOrder(1004, alice)).kind());
        assertEquals(NOT_FOUND, assertThrows(OrderException.class, () -> tools.getCustomerOrders(43, alice)).kind());
        assertThrows(IllegalStateException.class, () -> tools.getOrder(1001, new InvocationParameters()));
        try (var c = agentDb.getConnection(); var s = c.createStatement()) {
            assertEquals(1142, rejected("Agent account base-table read", () -> s.executeQuery("SELECT * FROM orders")).getErrorCode());
            assertEquals(1142, rejected("Agent account document update", () ->
                    s.executeUpdate("UPDATE orders_dv SET data=JSON_SET(data,'$.status','SHIPPED') WHERE data->'$._id'=1001")).getErrorCode());
        }
        assertEquals("PROCESSING", scalar("SELECT status FROM orders WHERE id=1001"));
    }

    @Test @Tag("ollama") void ollamaSummarizesPendingOrdersUsingARealToolCall() throws Exception {
        var run = askLocalModel(42, "Show me my recent orders and explain which ones are still pending.", "ollama-customer-42.json");
        assertTrue(run.tools().stream().anyMatch(t -> t.successful() && t.name().equals("getCustomerOrders")
                && t.arguments().path("customerId").asInt() == 42));
        String answer = run.answer().toUpperCase(Locale.ROOT);
        for (String expected : List.of("1001", "1002", "1003", "PENDING", "PROCESSING", "SHIPPED")) {
            assertTrue(answer.contains(expected), () -> "Missing " + expected + " in: " + run.answer());
        }
    }

    @Test @Tag("ollama") void ollamaChoosesOrderDetailToolAndUsesPurchasePrices() throws Exception {
        var run = askLocalModel(42, "What is in my order 1001? Include item quantities and unit prices.", "ollama-order-1001.json");
        assertTrue(run.tools().stream().anyMatch(t -> t.successful() && t.name().equals("getOrder")
                && t.arguments().path("orderId").asInt() == 1001));
        String answer = run.answer().toLowerCase(Locale.ROOT);
        for (String expected : List.of("keyboard", "mouse", "149", "49.5")) assertTrue(answer.contains(expected), run.answer());
        assertFalse(answer.contains("$") || answer.contains("usd") || answer.contains("cad"), "The schema does not specify currency");
    }

    @Test @Tag("ollama") void ollamaHandlesNullOrderHistory() throws Exception {
        var run = askLocalModel(44, "Do I have any orders?", "ollama-customer-44.json");
        assertTrue(run.tools().stream().anyMatch(t -> t.successful() && t.result().path("orders").isNull()));
        String answer = run.answer().toLowerCase(Locale.ROOT);
        assertTrue(answer.contains("no orders") || answer.contains("no recent orders")
                || answer.contains("don't have any orders") || answer.contains("do not have any orders"), run.answer());
    }

    private OllamaAgent.Run askLocalModel(long customerId, String question, String trace) throws Exception {
        var ollama = new OllamaProperties(URI.create(env("OLLAMA_BASE_URL", "http://127.0.0.1:11434")),
                env("OLLAMA_MODEL", "llama3.1:8b"), Duration.ofSeconds(Long.parseLong(env("OLLAMA_TIMEOUT_SECONDS", "120"))));
        var agent = new OllamaAgent(ollama.chatModel(), ollama.model(), new AgentOrderReader(new OrderDocumentRepository(agentDb)));
        var run = agent.answer(customerId, question);
        run.writeTrace(Path.of("target/evidence", trace));
        evidence.add("Ollama | " + run.model()
                + " | calls=" + run.tools().stream().map(OllamaAgent.ToolExchange::name).toList() + " | " + trace);
        return run;
    }
}
