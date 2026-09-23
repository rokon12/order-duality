package com.bazlur.orders.application;

import module java.base;

/** The status vocabulary from the orders.order_status CHECK constraint, plus this application's transitions. */
public enum OrderStatus {
    PENDING, PROCESSING, SHIPPED, CANCELLED;

    public static Optional<OrderStatus> parse(String value) {
        return Arrays.stream(values()).filter(status -> status.name().equals(value)).findFirst();
    }

    // A valid SQL status is not necessarily a valid next status.
    public boolean canMoveTo(OrderStatus next) {
        return this == next || switch (this) {
            case PENDING -> next == PROCESSING || next == CANCELLED;
            case PROCESSING -> next == SHIPPED || next == CANCELLED;
            case SHIPPED, CANCELLED -> false;
        };
    }
}
