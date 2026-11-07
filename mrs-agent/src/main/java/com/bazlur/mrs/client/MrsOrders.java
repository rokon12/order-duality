package com.bazlur.mrs.client;

import module java.base;

import com.bazlur.mrs.domain.OrderAccess;
import com.bazlur.mrs.domain.OrderValidation;
import com.bazlur.mrs.support.Json;
import tools.jackson.databind.JsonNode;

public final class MrsOrders implements OrderAccess {
    private final MrsClient client;

    public MrsOrders(MrsClient client) {
        this.client = client;
    }

    @Override
    public JsonNode listOrders() throws Exception {
        var response = client.orders();
        var summaries = new ArrayList<Map<String, Object>>();
        for (var item : response.path("items")) {
            summaries.add(
                    Map.of(
                            "orderId",
                            item.path("orderId").asLong(),
                            "status",
                            item.path("status").asString(),
                            "createdAt",
                            item.path("createdAt").asString()));
        }
        return Json.tree(
                Map.of("orders", summaries, "hasMore", response.path("hasMore").asBoolean()));
    }

    @Override
    public JsonNode getOrder(long orderId) throws Exception {
        OrderValidation.positiveId(orderId);
        return client.call(MrsClient.Operation.GET_ORDER, Map.of("orderId", orderId));
    }

    @Override
    public JsonNode requestCancellation(long orderId, String reason, UUID requestKey)
            throws Exception {
        OrderValidation.positiveId(orderId);
        if (reason == null || reason.isBlank() || reason.length() > 500)
            throw new IllegalArgumentException("Reason must contain 1–500 characters");
        return client.call(
                MrsClient.Operation.REQUEST,
                Map.of("orderId", orderId, "reason", reason, "requestKey", requestKey.toString()));
    }
}
