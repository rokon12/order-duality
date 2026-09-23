package com.bazlur.orders.persistence.jpa;

import module java.base;

import jakarta.persistence.*;
import org.hibernate.annotations.Immutable;

/** Read-only mapping of the orders table; this demo writes only through the duality view. */
@Entity
@Immutable
@Table(name = "orders")
public class OrderEntity {
    @Id private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_id")
    private CustomerEntity customer;

    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @OneToMany(mappedBy = "order")
    @OrderBy("id")
    private List<OrderItemEntity> items = new ArrayList<>();

    protected OrderEntity() {}

    public Long getId() { return id; }
    public CustomerEntity getCustomer() { return customer; }
    public String getStatus() { return status; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public List<OrderItemEntity> getItems() { return items; }
}
