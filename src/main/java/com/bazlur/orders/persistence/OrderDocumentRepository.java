package com.bazlur.orders.persistence;

import module java.base;
import module java.sql;

public final class OrderDocumentRepository {
    private final Database database;

    public OrderDocumentRepository(Database database) {
        this.database = database;
    }

    public Optional<String> getOrder(long id) throws SQLException {
        try (var connection = database.open()) {
            return getOrder(connection, id);
        }
    }

    public Optional<String> getOrder(Connection connection, long id) throws SQLException {
        try (var statement = connection.prepareStatement(
                "SELECT data FROM orders_dv WHERE data->'$._id' = ?")) {
            statement.setLong(1, id);
            try (var rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(rows.getString(1)) : Optional.empty();
            }
        }
    }

    public Optional<String> getCustomerOrders(long customerId) throws SQLException {
        try (var connection = database.open(); var statement = connection.prepareStatement(
                "SELECT data FROM customer_orders_dv WHERE data->'$._id' = ?")) {
            statement.setLong(1, customerId);
            try (var rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(rows.getString(1)) : Optional.empty();
            }
        }
    }

    // Pass the caller's original _metadata.etag through to MySQL.
    // Re-reading and substituting a fresh token here would defeat concurrency control.
    public int replace(Connection connection, long id, String document) throws SQLException {
        try (var statement = connection.prepareStatement(
                "UPDATE orders_dv SET data = ? WHERE data->'$._id' = ?")) {
            statement.setString(1, document);
            statement.setLong(2, id);
            return statement.executeUpdate();
        }
    }
}
