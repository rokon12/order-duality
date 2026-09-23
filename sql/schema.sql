CREATE DATABASE IF NOT EXISTS order_demo CHARACTER SET utf8mb4;
USE order_demo;

CREATE TABLE customers (
  id BIGINT PRIMARY KEY,
  name VARCHAR(120) NOT NULL,
  email VARCHAR(254) NOT NULL UNIQUE
) ENGINE=InnoDB;


CREATE TABLE products (
  id BIGINT PRIMARY KEY,
  sku VARCHAR(40) NOT NULL UNIQUE,
  name VARCHAR(160) NOT NULL,
  price DECIMAL(10,2) NOT NULL,
  CONSTRAINT product_price_nonnegative CHECK (price >= 0)
) ENGINE=InnoDB;

CREATE TABLE orders (
  id BIGINT PRIMARY KEY,
  customer_id BIGINT NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  CONSTRAINT orders_customer FOREIGN KEY (customer_id) REFERENCES customers(id),
  CONSTRAINT order_status CHECK (status IN ('PENDING','PROCESSING','SHIPPED','CANCELLED')),
  INDEX customer_recent (customer_id, created_at, id)
) ENGINE=InnoDB;

CREATE TABLE order_items (
  id BIGINT PRIMARY KEY,
  order_id BIGINT NOT NULL,
  product_id BIGINT NOT NULL,
  quantity INT NOT NULL,
  unit_price DECIMAL(10,2) NOT NULL,
  CONSTRAINT items_order FOREIGN KEY (order_id) REFERENCES orders(id),
  CONSTRAINT items_product FOREIGN KEY (product_id) REFERENCES products(id),
  CONSTRAINT item_quantity_positive CHECK (quantity > 0),
  CONSTRAINT item_price_nonnegative CHECK (unit_price >= 0)
) ENGINE=InnoDB;

-- Full document replacement supplies updated_at explicitly, defeating ON UPDATE.
-- A trigger keeps timestamp ownership in MySQL for relational and document writers.
CREATE TRIGGER orders_touch BEFORE UPDATE ON orders
FOR EACH ROW SET NEW.updated_at = CURRENT_TIMESTAMP(6);
