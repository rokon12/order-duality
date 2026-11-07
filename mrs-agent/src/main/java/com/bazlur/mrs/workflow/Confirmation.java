package com.bazlur.mrs.workflow;

import module java.base;

import com.bazlur.mrs.client.MrsClient;
import tools.jackson.databind.JsonNode;

/** Trusted human interaction path. This object is never registered as an AI tool. */
public final class Confirmation {
    public record Review(
            UUID requestId,
            long orderId,
            String reason,
            String state,
            String expiresAt,
            JsonNode order) {}

    /** The stored request no longer matches what was reviewed. The message is fixed text. */
    public static final class RequestChanged extends IllegalStateException {
        RequestChanged() {
            super(
                    "This request changed after you reviewed it, so nothing was cancelled. Use /confirm to review it again.");
        }
    }

    private final MrsClient customer;
    private final MrsClient executor;
    private final String principal;

    public Confirmation(MrsClient customer, MrsClient executor) throws Exception {
        this.customer = customer;
        this.executor = executor;
        this.principal = customer.principalId();
    }

    public Review review(UUID id) throws Exception {
        return review(customer, id);
    }

    /** Reading a request needs only the customer's credential, so approvals can stay off. */
    public static Review review(MrsClient customer, UUID id) throws Exception {
        // MRS checks ownership even if a caller supplies another customer's request UUID.
        var row = customer.cancellation(id);
        long orderId = row.path("orderId").asLong();
        // The reason is model-written, so the reviewer also needs the order itself.
        var order = customer.call(MrsClient.Operation.GET_ORDER, Map.of("orderId", orderId));
        if (!order.path("ok").asBoolean()) {
            throw new IllegalStateException("Order details are unavailable");
        }
        return new Review(
                id,
                orderId,
                row.path("reason").asString(),
                row.path("state").asString(),
                row.path("expiresAt").asString(),
                order.path("document"));
    }

    public JsonNode confirm(Review review, String humanInput) throws Exception {
        if (humanInput == null
                || !(humanInput.strip().equalsIgnoreCase("yes")
                        || humanInput.strip().equalsIgnoreCase("y"))) {
            throw new IllegalArgumentException("Explicit yes is required for the reviewed request");
        }
        var current = customer.cancellation(review.requestId());
        if (current.path("orderId").asLong() != review.orderId()
                || !current.path("reason").asString().equals(review.reason())) {
            throw new RequestChanged();
        }
        // The owner is derived from the authenticated customer, never from an LLM argument.
        return executor.call(
                MrsClient.Operation.CONFIRM,
                Map.of("requestId", review.requestId().toString(), "ownerHex", principal));
    }
}
