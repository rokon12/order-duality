package com.bazlur.orders.persistence.springdatajdbc;

import module java.base;

import org.springframework.data.annotation.Id;
import org.springframework.data.jdbc.core.mapping.AggregateReference;
import org.springframework.data.relational.core.mapping.MappedCollection;
import org.springframework.data.relational.core.mapping.Table;

/**
 * An order aggregate: the order owns its lines and is loaded and saved as one unit.
 * The customer and the products are separate aggregates, referenced only by ID.
 */
@Table("orders")
public record OrderAggregate(@Id Long id,
                             AggregateReference<CustomerAggregate, Long> customerId,
                             String status,
                             LocalDateTime createdAt,
                             LocalDateTime updatedAt,
                             @MappedCollection(idColumn = "order_id") Set<Item> items) {

    @Table("order_items")
    public record Item(@Id Long id,
                       AggregateReference<ProductAggregate, Long> productId,
                       int quantity,
                       BigDecimal unitPrice) {}
}
