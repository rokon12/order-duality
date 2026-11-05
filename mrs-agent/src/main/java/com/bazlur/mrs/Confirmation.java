package com.bazlur.mrs;

import module java.base;
import tools.jackson.databind.JsonNode;

/** Trusted human interaction path. This object is never registered as an AI tool. */
public final class Confirmation {
    public record Review(UUID requestId, long orderId, String reason, String state) {}
    private final MrsClient customer;
    private final MrsClient executor;
    private final String principal;

    public Confirmation(MrsClient customer, MrsClient executor) throws Exception {
        this.customer = customer;
        this.executor = executor;
        this.principal = customer.principalId();
    }

    public Review review(UUID id) throws Exception {
        // MRS checks ownership even if a caller supplies another customer's request UUID.
        var row = customer.cancellation(id);
        return new Review(id, row.path("orderId").asLong(), row.path("reason").asString(), row.path("state").asString());
    }

    public JsonNode confirm(Review review, String humanInput) throws Exception {
        if (!("CONFIRM " + review.requestId()).equals(humanInput)) throw new IllegalArgumentException("Confirmation did not match this request");
        var current = review(review.requestId());
        if (current.orderId() != review.orderId() || !current.reason().equals(review.reason())) throw new IllegalStateException("Request changed; review it again");
        // The owner is derived from the authenticated customer, never from an LLM argument.
        return executor.call(MrsClient.Operation.CONFIRM, Map.of("requestId", review.requestId().toString(), "ownerHex", principal));
    }
}
