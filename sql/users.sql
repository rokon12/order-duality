USE order_demo;
-- Local demonstration credentials. The API cannot write base tables.
CREATE USER IF NOT EXISTS 'order_api'@'%' IDENTIFIED BY 'local-api-only';
GRANT SELECT, UPDATE ON order_demo.orders_dv TO 'order_api'@'%';
GRANT SELECT ON order_demo.customer_orders_dv TO 'order_api'@'%';
GRANT SELECT ON order_demo.customers TO 'order_api'@'%';
GRANT SELECT ON order_demo.orders TO 'order_api'@'%';
GRANT SELECT ON order_demo.order_items TO 'order_api'@'%';
GRANT SELECT ON order_demo.products TO 'order_api'@'%';
