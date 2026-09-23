package com.bazlur.orders.persistence;

import module java.base;
import module java.sql;

import com.bazlur.orders.persistence.OrderDetails.Customer;
import com.bazlur.orders.persistence.OrderDetails.Line;
import com.bazlur.orders.persistence.OrderDetails.Order;
import com.bazlur.orders.persistence.OrderDetails.Product;

// One query, not N+1 queries. The baseline deserves the same care as the new path.
public final class ConventionalOrderRepository {
    private final DataSource dataSource;

    public ConventionalOrderRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public Optional<Order> getOrder(long id) throws SQLException {
        var sql = """
                SELECT o.id, o.status, o.created_at, o.updated_at,
                       c.id AS customer_id, c.name AS customer_name, c.email,
                       i.id AS item_id, i.quantity, i.unit_price,
                       p.id AS product_id, p.sku, p.name AS product_name
                FROM orders o JOIN customers c ON c.id = o.customer_id
                LEFT JOIN order_items i ON i.order_id = o.id
                LEFT JOIN products p ON p.id = i.product_id
                WHERE o.id = ? ORDER BY i.id
                """;
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(sql)) {
            statement.setLong(1, id);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) return Optional.empty();
                var customer = new Customer(rows.getLong("customer_id"), rows.getString("customer_name"), rows.getString("email"));
                var lines = new ArrayList<Line>();
                var orderId = rows.getLong("id");
                var status = rows.getString("status");
                var createdAt = rows.getObject("created_at", LocalDateTime.class);
                var updatedAt = rows.getObject("updated_at", LocalDateTime.class);
                do {
                    if (rows.getObject("item_id") != null) {
                        var product = new Product(rows.getLong("product_id"), rows.getString("sku"), rows.getString("product_name"));
                        lines.add(new Line(rows.getLong("item_id"), rows.getInt("quantity"), rows.getBigDecimal("unit_price"), product));
                    }
                } while (rows.next());
                return Optional.of(new Order(orderId, status, createdAt, updatedAt, customer, lines));
            }
        }
    }
}
