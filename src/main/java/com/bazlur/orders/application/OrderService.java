package com.bazlur.orders.application;

import com.bazlur.orders.json.Json;
import com.bazlur.orders.persistence.Database;
import com.bazlur.orders.persistence.OrderDocumentRepository;
import module java.base;
import module java.sql;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import static com.bazlur.orders.application.OrderException.Kind.*;

public final class OrderService {
    private static final Pattern ETAG = Pattern.compile("[0-9a-fA-F]{32}");

    private final Database database;
    private final OrderDocumentRepository repository;

    public OrderService(Database database, OrderDocumentRepository repository) {
        this.database = database;
        this.repository = repository;
    }

    // The demo API permits status edits only. Direct SQL experiments exercise the wider DML surface.
    public String changeStatus(long id, String body) throws SQLException {
        JsonNode incoming;
        try {
            incoming = Json.parse(body);
        } catch (JacksonException _) {
            throw new OrderException(INVALID_REQUEST, "Malformed JSON");
        }
        var requested = validateEnvelope(id, incoming);
        try (var connection = database.open()) {
            connection.setAutoCommit(false);
            try {
                var current = Json.parse(repository.getOrder(connection, id)
                        .orElseThrow(() -> new OrderException(NOT_FOUND, "Order not found")));
                // Improve the HTTP error, but MySQL's own etag check is still the final race guard.
                if (!current.at("/_metadata/etag").equals(incoming.at("/_metadata/etag")))
                    throw new OrderException(STALE, "Order changed; fetch it again before editing");
                if (!Json.sameValue(withoutStatus(current), withoutStatus(incoming)))
                    throw new OrderException(NOT_ALLOWED, "This endpoint accepts status changes only; preserve the rest of the document");
                var stored = OrderStatus.parse(current.path("status").stringValue(null))
                        .orElseThrow(() -> new IllegalStateException("Unknown stored status for order " + id));
                validateTransition(stored, requested);
                if (repository.replace(connection, id, body) == 0)
                    throw new OrderException(NOT_FOUND, "Order not found");
                var result = repository.getOrder(connection, id).orElseThrow();
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException e) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackFailure) {
                    e.addSuppressed(rollbackFailure);
                }
                throw e;
            }
        }
    }

    /** Checks identity, the read token and the status vocabulary; returns the requested status. */
    static OrderStatus validateEnvelope(long id, JsonNode document) {
        if (!(document instanceof ObjectNode order)) throw new OrderException(INVALID_REQUEST, "Expected a JSON object");
        var documentId = order.path("_id");
        if (!documentId.isIntegralNumber() || !documentId.canConvertToLong() || documentId.longValue() != id)
            throw new OrderException(INVALID_REQUEST, "Document _id must match the URL");
        var etag = order.at("/_metadata/etag").stringValue(null);
        if (etag == null || !ETAG.matcher(etag).matches())
            throw new OrderException(MISSING_TOKEN, "Preserve _metadata.etag from GET");
        return OrderStatus.parse(order.path("status").stringValue(null))
                .orElseThrow(() -> new OrderException(NOT_ALLOWED, "Unknown order status"));
    }

    static void validateTransition(OrderStatus from, OrderStatus to) {
        if (!from.canMoveTo(to)) throw new OrderException(NOT_ALLOWED, "Order status transition is not allowed");
    }

    private static JsonNode withoutStatus(JsonNode document) {
        if (!(document.deepCopy() instanceof ObjectNode copy)) return document;
        copy.remove("status");
        return copy;
    }
}
