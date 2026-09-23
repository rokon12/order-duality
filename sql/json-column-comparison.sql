USE order_demo;
-- This scratch table is an experiment, not part of the application's data model.
CREATE TEMPORARY TABLE order_snapshot (id BIGINT PRIMARY KEY, document JSON NOT NULL);
INSERT INTO order_snapshot SELECT 1001, data FROM orders_dv WHERE data->'$._id'=1001;
START TRANSACTION;
UPDATE customers SET name='Alice R.' WHERE id=42;
SELECT document->>'$.customer.name' AS snapshot_name FROM order_snapshot;
SELECT data->>'$.customer.name' AS live_name FROM orders_dv WHERE data->'$._id'=1001;
-- JSON validity alone does not create a relationship to customers.id.
UPDATE order_snapshot SET document=JSON_SET(document,'$.customer.id',9999) WHERE id=1001;
SELECT document->>'$.customer.id' AS accepted_nonexistent_id FROM order_snapshot;
ROLLBACK;
DROP TEMPORARY TABLE order_snapshot;
