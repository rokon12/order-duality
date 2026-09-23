package com.bazlur.orders.application;

import module java.base;

/** A rejected order operation. The API layer decides which HTTP status each kind becomes. */
public final class OrderException extends RuntimeException {
    public enum Kind {
        INVALID_REQUEST,   // unusable input: malformed JSON, bad ID, wrong _id
        NOT_FOUND,         // no such order, or none visible in this customer scope
        MISSING_TOKEN,     // write without the _metadata.etag from the read
        STALE,             // the order changed after it was read
        NOT_ALLOWED,       // well-formed but refused by policy: edits beyond status, bad transitions
        TOO_LARGE          // request body above the configured limit
    }

    private final Kind kind;

    public OrderException(Kind kind, String message) {
        super(message);
        this.kind = Objects.requireNonNull(kind);
    }

    public Kind kind() { return kind; }
}
