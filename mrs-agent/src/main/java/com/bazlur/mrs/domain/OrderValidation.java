package com.bazlur.mrs.domain;

/** Input invariants shared by model tools and the REST adapter. */
public final class OrderValidation {
    public static void positiveId(long id) {
        if (id <= 0) {
            throw new IllegalArgumentException("Order ID must be positive");
        }
    }

    private OrderValidation() {}
}
