package com.bazlur.orders.persistence.jpa;

import module java.base;

import com.bazlur.orders.persistence.OrderDetails.Customer;
import com.bazlur.orders.persistence.OrderDetails.Line;
import com.bazlur.orders.persistence.OrderDetails.Order;
import com.bazlur.orders.persistence.OrderDetails.Product;
import org.springframework.transaction.annotation.Transactional;

/** The ORM path: Hibernate loads the entity graph, Java maps it to the shared order records. */
public class JpaOrderReader {
    private final OrderEntityRepository repository;

    public JpaOrderReader(OrderEntityRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public Optional<Order> getOrder(long id) {
        return repository.findWithDetails(id).map(order -> new Order(
                order.getId(), order.getStatus(), order.getCreatedAt(), order.getUpdatedAt(),
                new Customer(order.getCustomer().getId(), order.getCustomer().getName(), order.getCustomer().getEmail()),
                order.getItems().stream().map(item -> new Line(item.getId(), item.getQuantity(), item.getUnitPrice(),
                        new Product(item.getProduct().getId(), item.getProduct().getSku(), item.getProduct().getName())))
                        .toList()));
    }
}
