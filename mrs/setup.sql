CONFIGURE REST METADATA;
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
