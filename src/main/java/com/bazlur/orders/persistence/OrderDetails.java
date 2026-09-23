package com.bazlur.orders.persistence;

import module java.base;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * The order shape returned by the three Java read paths (plain JDBC, JPA and Spring Data JDBC), so they can be
 * compared with each other and with the duality view's document.
 */
public final class OrderDetails {
    private OrderDetails() {}

    public record Customer(long id, String name, String email) {}
    public record Product(long id, String sku, String name) {}
    public record Line(long id, int quantity, BigDecimal unitPrice, Product product) {}

    // Java names the field id; the JSON key matches the duality view's required _id.
    public record Order(@JsonProperty("_id") long id, String status, LocalDateTime createdAt, LocalDateTime updatedAt,
                        Customer customer, List<Line> items) {
        public Order {
            items = List.copyOf(items);
        }
    }
}
