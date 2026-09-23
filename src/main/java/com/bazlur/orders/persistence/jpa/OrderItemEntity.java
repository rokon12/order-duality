package com.bazlur.orders.persistence.jpa;

import module java.base;

import jakarta.persistence.*;
import org.hibernate.annotations.Immutable;

@Entity
@Immutable
@Table(name = "order_items")
public class OrderItemEntity {
    @Id private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id")
    private OrderEntity order;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id")
    private ProductEntity product;

    private int quantity;
    private BigDecimal unitPrice;

    protected OrderItemEntity() {}

    public Long getId() { return id; }
    public ProductEntity getProduct() { return product; }
    public int getQuantity() { return quantity; }
    public BigDecimal getUnitPrice() { return unitPrice; }
}
