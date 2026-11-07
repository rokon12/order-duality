package com.bazlur.mrs.cli;

import module java.base;

import com.bazlur.mrs.agent.OrderAgent;
import com.bazlur.mrs.workflow.Confirmation;
import tools.jackson.databind.JsonNode;

/**
 * Terminal text. Model output and stored business data are untrusted, so they are cleaned here
 * before they reach the screen.
 */
final class Screens {
    static final String HELP =
            """
            Ask about your orders in plain language, for example: cancel order 1003.

            Commands:
              /requests       List your cancellation requests
              /review [ID]    Show a request and its order
              /confirm [ID]   Review a request, then approve or decline it
              /clear          Forget this conversation
              /help           Show this help
              /version        Show the version
              /quit           Leave (also /exit)

            Without an ID, /review and /confirm use the latest request from this session.""";

    private static final String REQUEST_ROW = "%-12s %-10s %-24s %s";
    private static final String FIELD = "  %-16s%s%n";

    /** The label separates the model's words from the application's recorded facts. */
    static String answer(OrderAgent.Answer answer) {
        return "Assistant: " + answer.explanation().replaceAll("[\\p{Cc}\\p{Cf}&&[^\\n\\t]]", "");
    }

    static String recorded(JsonNode request) {
        long order = request.path("orderId").asLong();
        String state = oneLine(request.path("state").asString());
        String headline =
                state.equals("PENDING")
                        ? "Request recorded. Order " + order + " is not cancelled yet."
                        : "Request for order " + order + " is " + state + ".";
        return headline
                + "\n  Request ID: "
                + oneLine(request.path("requestId").asString())
                + "\n  Status:     "
                + state;
    }

    static String review(Confirmation.Review review) {
        var screen = new StringBuilder();
        screen.append("Cancellation request for order ").append(review.orderId());
        screen.append(System.lineSeparator());
        String status = oneLine(review.order().path("status").asString());
        screen.append(FIELD.formatted("Order status:", status.isEmpty() ? "unknown" : status));
        appendItems(screen, review.order().path("items"));
        screen.append(
                FIELD.formatted(
                        "Reason:",
                        "\"" + oneLine(review.reason()) + "\" (written by the assistant)"));
        screen.append(FIELD.formatted("Request status:", oneLine(review.state())));
        screen.append(FIELD.formatted("Expires:", timestamp(review.expiresAt())));
        screen.append(FIELD.formatted("Request ID:", review.requestId()));
        return screen.toString().stripTrailing();
    }

    private static void appendItems(StringBuilder screen, JsonNode items) {
        if (items.size() == 0) {
            screen.append(FIELD.formatted("Items:", "No items"));
            return;
        }
        String label = "Items:";
        for (JsonNode item : items) {
            // The source data has no currency code, so prices stay plain numbers.
            String line =
                    String.format(
                            Locale.ROOT,
                            "%d x %s (%s) at %.2f each",
                            item.path("quantity").asInt(),
                            oneLine(item.at("/product/name").asString()),
                            oneLine(item.at("/product/sku").asString()),
                            item.path("unitPrice").asDouble());
            screen.append(FIELD.formatted(label, line));
            label = "";
        }
    }

    static String requests(JsonNode response) {
        JsonNode items = response.path("items");
        if (items.size() == 0) {
            return "You have no cancellation requests.";
        }
        var rows = new ArrayList<String>();
        rows.add(REQUEST_ROW.formatted("Order", "Status", "Expires", "Request ID"));
        for (JsonNode item : items) {
            rows.add(
                    REQUEST_ROW
                            .formatted(
                                    item.path("orderId").asLong(),
                                    oneLine(item.path("state").asString()),
                                    timestamp(item.path("expiresAt").asString()),
                                    oneLine(item.path("requestId").asString()))
                            .stripTrailing());
        }
        if (response.path("hasMore").asBoolean()) {
            rows.add("Showing the newest 10 requests.");
        }
        return String.join(System.lineSeparator(), rows);
    }

    /** Only ok plus CONFIRMED means the order changed; HTTP success alone never does. */
    static String outcome(JsonNode result) {
        if (result.path("ok").asBoolean() && result.path("state").asString().equals("CONFIRMED")) {
            return "Order " + result.path("orderId").asLong() + " cancelled.";
        }
        String code = oneLine(result.path("code").asString());
        return switch (code) {
            case "REQUEST_EXPIRED" ->
                    "This request expired, so the order was not cancelled. Ask again to create a new request.";
            case "ORDER_CHANGED", "REQUEST_STALE" ->
                    "The order changed after this request was made, so it was not cancelled. Ask again if you still want to cancel it.";
            case "ORDER_NOT_CANCELLABLE", "REQUEST_REJECTED" ->
                    "This order can no longer be cancelled, so it was not changed. It may already have shipped.";
            case "REQUEST_UNAVAILABLE" ->
                    "That request is not available, so nothing was cancelled.";
            case "FORBIDDEN" ->
                    "This account is not allowed to approve cancellations, so nothing was cancelled.";
            default -> "The order was not cancelled (" + code + ").";
        };
    }

    /** Keeps a field on one line, so untrusted text cannot pass for a screen label. */
    static String oneLine(String text) {
        return text.replaceAll("[\\n\\r\\t]+", " ").replaceAll("[\\p{Cc}\\p{Cf}]", "");
    }

    private static String timestamp(String value) {
        String clean = oneLine(value).replaceFirst("\\.\\d+$", "");
        return clean.isEmpty() ? "unknown" : clean + " UTC";
    }

    private Screens() {}
}
