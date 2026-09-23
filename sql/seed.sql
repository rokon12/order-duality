USE order_demo;
INSERT INTO customers VALUES
  (42, 'Alice Rahman', 'alice@example.com'),
  (43, 'Mateo Silva', 'mateo@example.com'),
  (44, 'Priya Shah', 'priya@example.com');
INSERT INTO products VALUES
  (501, 'KB-001', 'Mechanical Keyboard', 159.00),
  (502, 'MS-002', 'Wireless Mouse', 49.50),
  (503, 'DK-003', 'USB-C Dock', 119.00),
  (504, 'CB-004', 'Braided USB-C Cable', 18.00);
INSERT INTO orders VALUES
  (1001, 42, 'PROCESSING', '2026-09-18 09:30:00', '2026-09-18 10:00:00'),
  (1002, 42, 'SHIPPED', '2026-09-10 14:15:00', '2026-09-11 08:00:00'),
  (1003, 42, 'PENDING', '2026-09-20 11:45:00', '2026-09-20 11:45:00'),
  (1004, 43, 'CANCELLED', '2026-09-17 16:20:00', '2026-09-17 17:00:00'),
  (1005, 43, 'PENDING', '2026-09-21 08:00:00', '2026-09-21 08:00:00');
INSERT INTO order_items VALUES
  (9001, 1001, 501, 2, 149.00),
  (9002, 1001, 502, 1, 49.50),
  (9003, 1002, 503, 1, 109.00),
  (9004, 1003, 504, 3, 18.00),
  (9005, 1004, 501, 1, 149.00);
