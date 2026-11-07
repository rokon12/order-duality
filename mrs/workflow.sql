USE order_demo;

-- No login is possible as the routine owner. Its grants are narrower than root.
CREATE USER 'order_workflow'@'localhost' ACCOUNT LOCK;
GRANT SELECT ON order_demo.orders TO 'order_workflow'@'localhost';
GRANT SELECT ON order_demo.orders_dv TO 'order_workflow'@'localhost';
GRANT SELECT ON order_demo.customer_principals TO 'order_workflow'@'localhost';
GRANT UPDATE(status) ON order_demo.orders TO 'order_workflow'@'localhost';

ALTER TABLE orders ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
DROP TRIGGER orders_touch;
CREATE TRIGGER orders_touch BEFORE UPDATE ON orders FOR EACH ROW
SET NEW.updated_at=CURRENT_TIMESTAMP(6), NEW.version=OLD.version+1;

CREATE TABLE cancellation_requests (
  id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
  requester_id BINARY(16) NOT NULL,
  order_id BIGINT NOT NULL,
  request_key CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  reason VARCHAR(500) NOT NULL,
  observed_version BIGINT NOT NULL,
  state VARCHAR(20) NOT NULL DEFAULT 'PENDING',
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  expires_at DATETIME(6) NOT NULL,
  confirmed_at DATETIME(6),
  UNIQUE KEY request_retry (requester_id, request_key),
  FOREIGN KEY (requester_id) REFERENCES customer_principals(principal_id),
  FOREIGN KEY (order_id) REFERENCES orders(id),
  CHECK (state IN ('PENDING','CONFIRMED','EXPIRED','STALE','REJECTED'))
);
CREATE TABLE confirmation_principals (principal_id BINARY(16) PRIMARY KEY);
INSERT INTO confirmation_principals
SELECT id FROM mysql_rest_service_metadata.mrs_user WHERE name='executor';
CREATE TABLE action_audit (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  actor_id BINARY(16) NOT NULL,
  event VARCHAR(24) NOT NULL,
  occurred_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  UNIQUE KEY one_event (request_id, event),
  FOREIGN KEY (request_id) REFERENCES cancellation_requests(id)
);
GRANT SELECT, INSERT, UPDATE ON order_demo.cancellation_requests TO 'order_workflow'@'localhost';
GRANT SELECT ON order_demo.confirmation_principals TO 'order_workflow'@'localhost';
GRANT INSERT ON order_demo.action_audit TO 'order_workflow'@'localhost';

-- An invoker-security projection needs only these provider columns. MRS still enforces row ownership.
GRANT SELECT(id,customer_id,status,created_at) ON order_demo.orders TO 'mysql_rest_service_data_provider';
GRANT SELECT(principal_id,customer_id) ON order_demo.customer_principals TO 'mysql_rest_service_data_provider';
CREATE OR REPLACE DEFINER='order_workflow'@'localhost' SQL SECURITY INVOKER VIEW order_summaries AS
SELECT o.id, p.principal_id, o.status, o.created_at
FROM orders o JOIN customer_principals p ON p.customer_id=o.customer_id;

DELIMITER $$
CREATE DEFINER='order_workflow'@'localhost' PROCEDURE whoami(IN p_user_id BINARY(16))
SQL SECURITY DEFINER READS SQL DATA
BEGIN
  SELECT JSON_OBJECT('ok', TRUE, 'principalId', HEX(p_user_id)) AS result;
END$$

DROP PROCEDURE IF EXISTS get_order$$
CREATE DEFINER='order_workflow'@'localhost' PROCEDURE get_order(IN p_user_id BINARY(16), IN p_order_id BIGINT)
SQL SECURITY DEFINER READS SQL DATA
BEGIN
  DECLARE v_document JSON DEFAULT NULL;
  -- Scope and document are evaluated in one statement/snapshot. No caller-supplied customer ID.
  SELECT d.data INTO v_document FROM orders_dv d
  WHERE d.data->'$._id'=p_order_id
    AND d.data->'$.customer.id'=(SELECT customer_id FROM customer_principals WHERE principal_id=p_user_id);
  SELECT IF(v_document IS NULL,
    JSON_OBJECT('ok', FALSE, 'code', 'ORDER_UNAVAILABLE'),
    JSON_OBJECT('ok', TRUE, 'document', v_document)) AS result;
END$$

CREATE DEFINER='order_workflow'@'localhost' PROCEDURE request_cancellation(
  IN p_user_id BINARY(16), IN p_order_id BIGINT, IN p_reason VARCHAR(500), IN p_request_key CHAR(36))
SQL SECURITY DEFINER MODIFIES SQL DATA
body: BEGIN
  DECLARE v_customer BIGINT DEFAULT NULL;
  DECLARE v_status VARCHAR(20);
  DECLARE v_version BIGINT;
  DECLARE v_id CHAR(36) DEFAULT NULL;
  DECLARE v_existing_order BIGINT;
  DECLARE v_existing_reason VARCHAR(500);
  DECLARE v_state VARCHAR(20);
  DECLARE EXIT HANDLER FOR SQLEXCEPTION BEGIN ROLLBACK; RESIGNAL; END;
  IF p_reason IS NULL OR CHAR_LENGTH(TRIM(p_reason)) NOT BETWEEN 1 AND 500
    OR p_request_key IS NULL OR NOT IS_UUID(p_request_key) THEN
    SELECT JSON_OBJECT('ok', FALSE, 'code', 'INVALID_REQUEST') AS result;
    LEAVE body;
  END IF;
  START TRANSACTION;
  -- Every mutation locks the order before the request, including confirmation.
  SELECT o.customer_id, o.status, o.version INTO v_customer, v_status, v_version
    FROM orders o WHERE o.id=p_order_id FOR UPDATE;
  IF v_customer IS NULL OR NOT EXISTS (
    SELECT 1 FROM customer_principals WHERE principal_id=p_user_id AND customer_id=v_customer) THEN
    ROLLBACK;
    SELECT JSON_OBJECT('ok', FALSE, 'code', 'ORDER_UNAVAILABLE') AS result;
    LEAVE body;
  END IF;
  SELECT id, order_id, reason, state INTO v_id, v_existing_order, v_existing_reason, v_state
    FROM cancellation_requests WHERE requester_id=p_user_id AND request_key=p_request_key FOR UPDATE;
  IF v_id IS NOT NULL THEN
    COMMIT;
    IF v_existing_order<>p_order_id OR BINARY v_existing_reason<>BINARY p_reason THEN
      SELECT JSON_OBJECT('ok', FALSE, 'code', 'IDEMPOTENCY_CONFLICT') AS result;
    ELSE
      SELECT JSON_OBJECT('ok', TRUE, 'requestId', v_id, 'orderId', p_order_id, 'state', v_state) AS result;
    END IF;
    LEAVE body;
  END IF;
  IF v_status NOT IN ('PENDING','PROCESSING') THEN
    ROLLBACK;
    SELECT JSON_OBJECT('ok', FALSE, 'code', 'ORDER_NOT_CANCELLABLE') AS result;
    LEAVE body;
  END IF;
  SET v_id=UUID();
  INSERT INTO cancellation_requests(id,requester_id,order_id,request_key,reason,observed_version,expires_at)
    VALUES(v_id,p_user_id,p_order_id,p_request_key,p_reason,v_version,UTC_TIMESTAMP(6)+INTERVAL 10 MINUTE);
  INSERT INTO action_audit(request_id,actor_id,event) VALUES(v_id,p_user_id,'REQUESTED');
  COMMIT;
  SELECT JSON_OBJECT('ok', TRUE, 'requestId', v_id, 'orderId', p_order_id, 'state', 'PENDING') AS result;
END$$

CREATE DEFINER='order_workflow'@'localhost' PROCEDURE confirm_cancellation(
  IN p_executor_id BINARY(16), IN p_request_id CHAR(36), IN p_owner_hex CHAR(32))
SQL SECURITY DEFINER MODIFIES SQL DATA
body: BEGIN
  DECLARE v_order BIGINT DEFAULT NULL;
  DECLARE v_customer BIGINT;
  DECLARE v_status VARCHAR(20);
  DECLARE v_current_version BIGINT;
  DECLARE v_expected_version BIGINT;
  DECLARE v_owner BINARY(16);
  DECLARE v_state VARCHAR(20);
  DECLARE v_expires DATETIME(6);
  DECLARE v_code VARCHAR(32) DEFAULT NULL;
  DECLARE EXIT HANDLER FOR SQLEXCEPTION BEGIN ROLLBACK; RESIGNAL; END;
  IF NOT EXISTS (SELECT 1 FROM confirmation_principals WHERE principal_id=p_executor_id) THEN
    SELECT JSON_OBJECT('ok', FALSE, 'code', 'FORBIDDEN') AS result;
    LEAVE body;
  END IF;
  IF p_owner_hex IS NULL OR p_owner_hex NOT REGEXP '^[0-9A-Fa-f]{32}$' THEN
    SELECT JSON_OBJECT('ok', FALSE, 'code', 'REQUEST_UNAVAILABLE') AS result;
    LEAVE body;
  END IF;
  SELECT order_id INTO v_order FROM cancellation_requests
    WHERE id=p_request_id AND requester_id=UNHEX(p_owner_hex);
  IF v_order IS NULL THEN
    SELECT JSON_OBJECT('ok', FALSE, 'code', 'REQUEST_UNAVAILABLE') AS result;
    LEAVE body;
  END IF;
  START TRANSACTION;
  SELECT customer_id,status,version INTO v_customer,v_status,v_current_version
    FROM orders WHERE id=v_order FOR UPDATE;
  SELECT requester_id,state,observed_version,expires_at INTO v_owner,v_state,v_expected_version,v_expires
    FROM cancellation_requests WHERE id=p_request_id FOR UPDATE;
  IF v_owner<>UNHEX(p_owner_hex) OR NOT EXISTS (
    SELECT 1 FROM customer_principals WHERE principal_id=v_owner AND customer_id=v_customer) THEN
    ROLLBACK;
    SELECT JSON_OBJECT('ok', FALSE, 'code', 'REQUEST_UNAVAILABLE') AS result;
    LEAVE body;
  END IF;
  IF v_state='CONFIRMED' THEN
    COMMIT;
    SELECT JSON_OBJECT('ok', TRUE, 'requestId', p_request_id, 'orderId', v_order, 'state', 'CONFIRMED') AS result;
    LEAVE body;
  END IF;
  IF v_state<>'PENDING' THEN
    ROLLBACK;
    SELECT JSON_OBJECT('ok', FALSE, 'code', CONCAT('REQUEST_',v_state)) AS result;
    LEAVE body;
  END IF;
  IF v_expires<=UTC_TIMESTAMP(6) THEN
    SET v_state='EXPIRED', v_code='REQUEST_EXPIRED';
  ELSEIF v_status NOT IN ('PENDING','PROCESSING') THEN
    SET v_state='REJECTED', v_code='ORDER_NOT_CANCELLABLE';
  ELSEIF v_current_version<>v_expected_version THEN
    SET v_state='STALE', v_code='ORDER_CHANGED';
  END IF;
  IF v_code IS NOT NULL THEN
    UPDATE cancellation_requests SET state=v_state WHERE id=p_request_id;
    INSERT INTO action_audit(request_id,actor_id,event) VALUES(p_request_id,p_executor_id,v_state);
    COMMIT;
    SELECT JSON_OBJECT('ok', FALSE, 'code', v_code) AS result;
    LEAVE body;
  END IF;
  UPDATE orders SET status='CANCELLED' WHERE id=v_order;
  UPDATE cancellation_requests SET state='CONFIRMED', confirmed_at=UTC_TIMESTAMP(6) WHERE id=p_request_id;
  INSERT INTO action_audit(request_id,actor_id,event) VALUES(p_request_id,p_executor_id,'CANCELLED');
  COMMIT;
  SELECT JSON_OBJECT('ok', TRUE, 'requestId', p_request_id, 'orderId', v_order, 'state', 'CONFIRMED') AS result;
END$$
DELIMITER ;

-- MySQL also checks EXECUTE for the definer when entering its own routine.
GRANT EXECUTE ON PROCEDURE order_demo.whoami TO 'order_workflow'@'localhost';
GRANT EXECUTE ON PROCEDURE order_demo.get_order TO 'order_workflow'@'localhost';
GRANT EXECUTE ON PROCEDURE order_demo.request_cancellation TO 'order_workflow'@'localhost';
GRANT EXECUTE ON PROCEDURE order_demo.confirm_cancellation TO 'order_workflow'@'localhost';
