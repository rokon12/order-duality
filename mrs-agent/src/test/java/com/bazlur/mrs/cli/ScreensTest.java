package com.bazlur.mrs.cli;

import static org.junit.jupiter.api.Assertions.*;

import module java.base;

import com.bazlur.mrs.agent.OrderAgent;
import com.bazlur.mrs.support.Json;
import com.bazlur.mrs.workflow.Confirmation;
import org.junit.jupiter.api.Test;

class ScreensTest {
    static final UUID ID = UUID.fromString("066631b6-da7a-11f1-a146-f25ecc3dad45");
    static final String DOCUMENT =
            """
            {"_id":1003,"status":"PENDING","customer":{"name":"Alice Rahman","email":"alice@example.com"},
             "_metadata":{"etag":"df982b11"},
             "items":[{"quantity":3,"unitPrice":18.0,"product":{"sku":"CB-004","name":"Braided USB-C Cable"}},
                      {"quantity":1,"unitPrice":49.5,"product":{"sku":"MS-002","name":"Wireless Mouse"}}]}
            """;

    static Confirmation.Review review(String reason, String document) {
        return new Confirmation.Review(
                ID, 1003, reason, "PENDING", "2026-11-07 07:18:25.342204", Json.parse(document));
    }

    @Test
    void reviewShowsWhatIsBeingCancelledAndWhoWroteTheReason() {
        String screen = Screens.review(review("Ordered by mistake", DOCUMENT));
        assertTrue(screen.contains("order 1003"), screen);
        assertTrue(screen.contains("Order status:   PENDING"), screen);
        assertTrue(screen.contains("3 x Braided USB-C Cable (CB-004) at 18.00 each"), screen);
        assertTrue(screen.contains("1 x Wireless Mouse (MS-002) at 49.50 each"), screen);
        assertTrue(screen.contains("\"Ordered by mistake\" (written by the assistant)"), screen);
        assertTrue(screen.contains("2026-11-07 07:18:25 UTC"), screen);
        assertTrue(screen.contains(ID.toString()), screen);
    }

    @Test
    void reviewLeavesOutPersonalAndTechnicalFields() {
        String screen = Screens.review(review("Mistake", DOCUMENT));
        assertFalse(screen.contains("alice@example.com"), screen);
        assertFalse(screen.contains("df982b11"), screen);
        assertFalse(screen.contains("$"), "Prices have no currency code: " + screen);
    }

    @Test
    void reviewHandlesAnOrderWithoutItems() {
        String screen = Screens.review(review("Mistake", "{\"_id\":1003,\"status\":\"PENDING\"}"));
        assertTrue(screen.contains("No items"), screen);
    }

    @Test
    void untrustedTextCannotForgeLinesOrControlTheTerminal() {
        String hostile = "Fine\n  Items:          nothing to see\u001b[2J‮\u0007";
        String document =
                """
                {"status":"PENDING","items":[{"quantity":1,"unitPrice":1.0,
                 "product":{"sku":"X\\u001b[31m","name":"Cable\\nOrder status: SHIPPED"}}]}
                """;
        String screen = Screens.review(review(hostile, document));
        assertFalse(screen.contains("\u001b"), screen);
        assertFalse(screen.contains("‮"), screen);
        assertFalse(screen.contains("\u0007"), screen);
        // Each field stays on one line, so hostile text cannot pass for a screen label.
        for (String label : List.of("Reason:", "Items:", "Order status:")) {
            assertEquals(
                    1,
                    screen.lines().filter(line -> line.strip().startsWith(label)).count(),
                    screen);
        }
        assertTrue(screen.contains("Order status:   PENDING"), screen);
    }

    @Test
    void answerIsLabelledAndCleaned() {
        var answer = new OrderAgent.Answer("Line one\nLine two\u001b[2J‮", List.of(), 1);
        assertEquals("Assistant: Line one\nLine two[2J", Screens.answer(answer));
    }

    @Test
    void recordedRequestSaysTheOrderIsNotCancelled() {
        String screen =
                Screens.recorded(
                        Json.parse(
                                "{\"ok\":true,\"state\":\"PENDING\",\"orderId\":1003,\"requestId\":\""
                                        + ID
                                        + "\"}"));
        assertTrue(screen.contains("Request recorded. Order 1003 is not cancelled yet."), screen);
        assertTrue(screen.contains(ID.toString()), screen);
        assertTrue(screen.contains("PENDING"), screen);
        assertFalse(screen.contains("{"), screen);
    }

    @Test
    void requestsAreATableWithoutEnvelopeFields() {
        String screen =
                Screens.requests(
                        Json.parse(
                                """
                                {"items":[{"links":[{"rel":"self","href":"/orders/shop/requests/x"}],
                                 "owner":"EfHYqCUf","state":"CONFIRMED","reason":"Mistake","orderId":1003,
                                 "expiresAt":"2026-11-07 07:18:25.342204",
                                 "requestId":"066631b6-da7a-11f1-a146-f25ecc3dad45",
                                 "_metadata":{"etag":"899DB6"}}],
                                 "limit":10,"offset":0,"hasMore":false,"count":1}
                                """));
        assertTrue(screen.contains("1003"), screen);
        assertTrue(screen.contains("CONFIRMED"), screen);
        assertTrue(screen.contains("2026-11-07 07:18:25 UTC"), screen);
        assertTrue(screen.contains(ID.toString()), screen);
        assertFalse(screen.contains("EfHYqCUf"), screen);
        assertFalse(screen.contains("899DB6"), screen);
        assertFalse(screen.contains("Showing"), screen);
    }

    @Test
    void requestsExplainEmptyAndTruncatedLists() {
        assertTrue(
                Screens.requests(Json.parse("{\"items\":[],\"hasMore\":false}"))
                        .contains("no cancellation requests"));
        assertTrue(
                Screens.requests(
                                Json.parse(
                                        "{\"items\":[{\"orderId\":1,\"state\":\"PENDING\"}],\"hasMore\":true}"))
                        .contains("Showing the newest 10"));
    }

    @Test
    void outcomeUsesTheAuthoritativeResult() {
        assertEquals(
                "Order 1003 cancelled.",
                Screens.outcome(
                        Json.parse("{\"ok\":true,\"state\":\"CONFIRMED\",\"orderId\":1003}")));
        // HTTP success alone never means the order changed: only ok plus CONFIRMED does.
        for (String code :
                List.of(
                        "REQUEST_EXPIRED",
                        "ORDER_CHANGED",
                        "REQUEST_STALE",
                        "ORDER_NOT_CANCELLABLE",
                        "REQUEST_REJECTED",
                        "REQUEST_UNAVAILABLE",
                        "FORBIDDEN",
                        "SOME_NEW_CODE")) {
            String screen = Screens.outcome(Json.parse("{\"ok\":false,\"code\":\"" + code + "\"}"));
            assertFalse(screen.isBlank(), code);
            assertFalse(screen.contains("Order 1003 cancelled"), code);
            assertTrue(screen.contains("not"), code + ": " + screen);
        }
        assertTrue(
                Screens.outcome(Json.parse("{\"ok\":false,\"code\":\"SOME_NEW_CODE\"}"))
                        .contains("SOME_NEW_CODE"));
    }

    @Test
    void helpListsEveryCommand() {
        for (String command :
                List.of("/requests", "/review", "/confirm", "/clear", "/help", "/quit")) {
            assertTrue(Screens.HELP.contains(command), command);
        }
    }
}
