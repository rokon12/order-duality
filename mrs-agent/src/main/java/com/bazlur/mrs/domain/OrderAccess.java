package com.bazlur.mrs.domain;

import java.util.UUID;
import tools.jackson.databind.JsonNode;

/** The complete authority available to the model's tools. Confirmation is intentionally absent. */
public interface OrderAccess {
    JsonNode listOrders() throws Exception;

    JsonNode getOrder(long orderId) throws Exception;

    JsonNode requestCancellation(long orderId, String reason, UUID requestKey) throws Exception;
}
