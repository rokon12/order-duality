-- Separate from the REST writer: the local model's tools only need these reads.
CREATE USER IF NOT EXISTS 'order_agent'@'%' IDENTIFIED BY 'local-agent-only';
GRANT SELECT ON order_demo.orders_dv TO 'order_agent'@'%';
GRANT SELECT ON order_demo.customer_orders_dv TO 'order_agent'@'%';
