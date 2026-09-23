package com.bazlur.orders.ai;

import com.bazlur.orders.application.OrderException;
import com.bazlur.orders.persistence.OrderDocumentRepository;
import module java.sql;
import static com.bazlur.orders.application.OrderException.Kind.*;

/** The agent's only data access: two fixed document reads, never arbitrary SQL. Missing documents become NOT_FOUND. */
public class AgentOrderReader {
    private final OrderDocumentRepository repository;

    public AgentOrderReader(OrderDocumentRepository repository) { this.repository = repository; }

    public String getOrder(long orderId) throws SQLException {
        return repository.getOrder(orderId).orElseThrow(() -> new OrderException(NOT_FOUND, "Order not found"));
    }

    public String getCustomerOrders(long customerId) throws SQLException {
        return repository.getCustomerOrders(customerId).orElseThrow(() -> new OrderException(NOT_FOUND, "Customer not found"));
    }
}
