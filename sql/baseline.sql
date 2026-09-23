USE order_demo;
SELECT o.id, o.status, o.created_at, o.updated_at,
       c.id AS customer_id, c.name AS customer_name, c.email,
       i.id AS item_id, i.quantity, i.unit_price,
       p.id AS product_id, p.sku, p.name AS product_name
FROM orders o
JOIN customers c ON c.id = o.customer_id
LEFT JOIN order_items i ON i.order_id = o.id
LEFT JOIN products p ON p.id = i.product_id
WHERE o.id = 1001
ORDER BY i.id;

-- Relational reporting still uses the very same rows.
SELECT o.status, COUNT(DISTINCT o.id) AS orders,
       COALESCE(SUM(i.quantity * i.unit_price), 0) AS merchandise_value
FROM orders o LEFT JOIN order_items i ON i.order_id = o.id
GROUP BY o.status;
