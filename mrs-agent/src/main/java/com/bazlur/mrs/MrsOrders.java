package com.bazlur.mrs;

import module java.base;
import tools.jackson.databind.JsonNode;

public final class MrsOrders implements OrderAccess {
    private final MrsClient client;
    public MrsOrders(MrsClient client) { this.client = client; }

    @Override public JsonNode listOrders() throws Exception {
        var response = client.orders();
        var summaries = new ArrayList<Map<String, Object>>();
        for (var item : response.path("items")) {
            summaries.add(Map.of("orderId", item.path("orderId").asLong(),
                    "status", item.path("status").asString(), "createdAt", item.path("createdAt").asString()));
        }
        return Json.MAPPER.valueToTree(Map.of("orders", summaries, "hasMore", response.path("hasMore").asBoolean()));
    }

    @Override public JsonNode getOrder(long orderId) throws Exception {
        positive(orderId);
        return client.call(MrsClient.Operation.GET_ORDER, Map.of("orderId", orderId));
    }

    @Override public JsonNode requestCancellation(long orderId, String reason, UUID requestKey) throws Exception {
        positive(orderId);
        if (reason == null || reason.isBlank() || reason.length() > 500) throw new IllegalArgumentException("Reason must contain 1–500 characters");
        return client.call(MrsClient.Operation.REQUEST, Map.of("orderId", orderId, "reason", reason, "requestKey", requestKey.toString()));
    }

    static void positive(long id) { if (id <= 0) throw new IllegalArgumentException("Order ID must be positive"); }
}
