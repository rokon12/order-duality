CREATE REST SERVICE /orders ENABLED PUBLISHED;
USE REST SERVICE /orders;
CREATE REST AUTH APP OrderMRS VENDOR MRS DO NOT ALLOW NEW USERS TO REGISTER;
ALTER REST SERVICE /orders ADD AUTH APP OrderMRS;
CREATE REST USER `alice` @ `OrderMRS` IDENTIFIED BY 'alice-Local!26';
CREATE REST USER `bob` @ `OrderMRS` IDENTIFIED BY 'bob-Local!26';
CREATE REST USER `reader` @ `OrderMRS` IDENTIFIED BY 'reader-Local!26';
CREATE REST USER `executor` @ `OrderMRS` IDENTIFIED BY 'executor-Local!26';
CREATE REST SCHEMA /shop FROM order_demo;
USE REST SCHEMA /shop;

CREATE TABLE order_demo.customer_principals (
  principal_id BINARY(16) PRIMARY KEY,
  customer_id BIGINT NOT NULL,
  FOREIGN KEY (customer_id) REFERENCES order_demo.customers(id)
);
INSERT INTO order_demo.customer_principals
SELECT id, CASE name WHEN 'bob' THEN 43 ELSE 42 END
FROM mysql_rest_service_metadata.mrs_user
WHERE name IN ('alice','bob','reader');

CREATE VIEW order_demo.order_summaries AS
SELECT o.id, p.principal_id, o.status, o.created_at
FROM order_demo.orders o
JOIN order_demo.customer_principals p ON p.customer_id = o.customer_id;

CREATE REST VIEW /orders AS order_demo.order_summaries {
  orderId: id @KEY @SORTABLE,
  owner: principal_id @ROWOWNERSHIP,
  status: status,
  createdAt: created_at @SORTABLE
} AUTHENTICATION REQUIRED;

DELIMITER $$
CREATE PROCEDURE order_demo.who_am_i(IN p_user_id BINARY(16))
SQL SECURITY DEFINER
BEGIN
  SELECT HEX(p_user_id) AS principal, CURRENT_USER() AS sql_user;
END$$
DELIMITER ;
CREATE REST PROCEDURE /identity AS order_demo.who_am_i
PARAMETERS { userId: p_user_id @IN @ROWOWNERSHIP }
RESULT { principal: principal @DATATYPE("varchar(32)"), sqlUser: sql_user @DATATYPE("varchar(255)") }
AUTHENTICATION REQUIRED;

CREATE REST ROLE order_reader;
GRANT REST READ ON SCHEMA /shop OBJECT /orders TO order_reader;
GRANT REST CREATE ON SCHEMA /shop OBJECT /identity TO order_reader;
GRANT REST ROLE order_reader TO `alice` @ `OrderMRS`;
GRANT REST ROLE order_reader TO `bob` @ `OrderMRS`;
GRANT REST ROLE order_reader TO `reader` @ `OrderMRS`;

DELIMITER $$
CREATE PROCEDURE order_demo.get_order(IN p_user_id BINARY(16), IN p_order_id BIGINT)
SQL SECURITY DEFINER
BEGIN
  IF NOT EXISTS (SELECT 1 FROM order_demo.orders o
    JOIN order_demo.customer_principals p ON p.customer_id=o.customer_id
    WHERE o.id=p_order_id AND p.principal_id=p_user_id) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ORDER_UNAVAILABLE';
  END IF;
  SELECT data AS document FROM order_demo.orders_dv WHERE data->'$._id'=p_order_id;
END$$
DELIMITER ;
CREATE REST PROCEDURE /getOrder AS order_demo.get_order
PARAMETERS { userId: p_user_id @IN @ROWOWNERSHIP, orderId: p_order_id @IN }
RESULT { document: document @DATATYPE("json") }
AUTHENTICATION REQUIRED;
GRANT REST CREATE ON SCHEMA /shop OBJECT /getOrder TO order_reader;
