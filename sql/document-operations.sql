USE order_demo;
-- Roll back every successful experiment, leaving the seed data intact.
START TRANSACTION;
SELECT data INTO @original FROM orders_dv WHERE data->'$._id' = 1001;
UPDATE orders_dv SET data = JSON_SET(@original, '$.status', 'SHIPPED')
WHERE data->'$._id' = 1001;
SELECT id, status, updated_at FROM orders WHERE id = 1001;
SELECT id, order_id, product_id, quantity, unit_price FROM order_items WHERE order_id = 1001;
ROLLBACK;

-- INSERT has no column list. Existing shared objects may be referenced unchanged.
START TRANSACTION;
SELECT data INTO @original FROM orders_dv WHERE data->'$._id' = 1001;
SET @new = JSON_REMOVE(JSON_SET(@original, '$._id', 1010,
  '$.items[0].id', 9010, '$.items[1].id', 9011), '$._metadata');
INSERT INTO orders_dv VALUES (@new);
SELECT id, customer_id, status FROM orders WHERE id = 1010;
DELETE FROM orders_dv WHERE data->'$._id' = 1010;
SELECT COUNT(*) AS remaining_lines FROM order_items WHERE order_id = 1010;
SELECT COUNT(*) AS preserved_products FROM products;
ROLLBACK;
