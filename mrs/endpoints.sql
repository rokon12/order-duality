USE REST SERVICE /orders;
USE REST SCHEMA /shop;
CREATE OR REPLACE REST VIEW /orders AS order_demo.order_summaries {
  orderId: id @KEY @SORTABLE,
  owner: principal_id @ROWOWNERSHIP,
  status: status,
  createdAt: created_at @SORTABLE
} AUTHENTICATION REQUIRED ITEMS PER PAGE 10;
CREATE REST VIEW /requests AS order_demo.cancellation_requests {
  requestId: id @KEY,
  owner: requester_id @ROWOWNERSHIP,
  orderId: order_id,
  reason: reason,
  state: state,
  expiresAt: expires_at @SORTABLE
} AUTHENTICATION REQUIRED ITEMS PER PAGE 10;
CREATE REST PROCEDURE /whoami AS order_demo.whoami
PARAMETERS { userId: p_user_id @IN @ROWOWNERSHIP }
RESULT { result: result @DATATYPE("json") } AUTHENTICATION REQUIRED;
CREATE OR REPLACE REST PROCEDURE /getOrder AS order_demo.get_order
PARAMETERS { userId: p_user_id @IN @ROWOWNERSHIP, orderId: p_order_id @IN }
RESULT { result: result @DATATYPE("json") } AUTHENTICATION REQUIRED;
CREATE REST PROCEDURE /requestCancellation AS order_demo.request_cancellation
PARAMETERS {
  userId: p_user_id @IN @ROWOWNERSHIP,
  orderId: p_order_id @IN,
  reason: p_reason @IN,
  requestKey: p_request_key @IN
} RESULT { result: result @DATATYPE("json") } AUTHENTICATION REQUIRED;
CREATE REST PROCEDURE /confirmCancellation AS order_demo.confirm_cancellation
PARAMETERS {
  executorId: p_executor_id @IN @ROWOWNERSHIP,
  requestId: p_request_id @IN,
  ownerHex: p_owner_hex @IN
} RESULT { result: result @DATATYPE("json") } AUTHENTICATION REQUIRED;

CREATE REST ROLE order_reader;
GRANT REST READ ON SCHEMA /shop OBJECT /orders TO order_reader;
GRANT REST READ ON SCHEMA /shop OBJECT /requests TO order_reader;
-- MRS uses POST/CREATE authorization for a procedure, including a read-only procedure.
GRANT REST CREATE ON SCHEMA /shop OBJECT /whoami TO order_reader;
GRANT REST CREATE ON SCHEMA /shop OBJECT /getOrder TO order_reader;
CREATE REST ROLE order_requester EXTENDS order_reader;
GRANT REST CREATE ON SCHEMA /shop OBJECT /requestCancellation TO order_requester;
CREATE REST ROLE order_confirmer;
GRANT REST CREATE ON SCHEMA /shop OBJECT /confirmCancellation TO order_confirmer;
GRANT REST ROLE order_requester TO `alice` @ `OrderMRS`;
GRANT REST ROLE order_requester TO `bob` @ `OrderMRS`;
GRANT REST ROLE order_reader TO `reader` @ `OrderMRS`;
GRANT REST ROLE order_confirmer TO `executor` @ `OrderMRS`;
