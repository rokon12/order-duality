package com.bazlur.orders.persistence.springdatajdbc;

import module java.base;

import com.bazlur.orders.persistence.OrderDetails.Customer;
import com.bazlur.orders.persistence.OrderDetails.Line;
import com.bazlur.orders.persistence.OrderDetails.Order;
import com.bazlur.orders.persistence.OrderDetails.Product;

/**
 * The Spring Data JDBC path. Loading the order aggregate brings its lines; the customer and the products
 * sit outside the aggregate boundary, so showing their names takes separate lookups.
 */
public class SpringDataJdbcOrderReader {
    private final OrderAggregateRepository orders;
    private final CustomerAggregateRepository customers;
    private final ProductAggregateRepository products;

    public SpringDataJdbcOrderReader(OrderAggregateRepository orders, CustomerAggregateRepository customers,
                                     ProductAggregateRepository products) {
        this.orders = orders;
        this.customers = customers;
        this.products = products;
    }

    public Optional<Order> getOrder(long id) {
        return orders.findById(id).map(order -> {
            var customer = customers.findById(order.customerId().getId()).orElseThrow();
            var productIds = order.items().stream().map(item -> item.productId().getId()).collect(Collectors.toSet());
            var productsById = products.findAllById(productIds).stream()
                    .collect(Collectors.toMap(ProductAggregate::id, product -> product));
            var lines = order.items().stream()
                    .sorted(Comparator.comparing(OrderAggregate.Item::id))
                    .map(item -> {
                        var product = productsById.get(item.productId().getId());
                        return new Line(item.id(), item.quantity(), item.unitPrice(),
                                new Product(product.id(), product.sku(), product.name()));
                    })
                    .toList();
            return new Order(order.id(), order.status(), order.createdAt(), order.updatedAt(),
                    new Customer(customer.id(), customer.name(), customer.email()), lines);
        });
    }
}
