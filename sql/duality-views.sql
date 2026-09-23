USE order_demo;

-- Each object projects one table. Shared customer/product rows are read-only.
-- A line's unitPrice is the price agreed at purchase, not today's catalog price.
CREATE OR REPLACE JSON DUALITY VIEW orders_dv AS
SELECT JSON_DUALITY_OBJECT(WITH(INSERT, UPDATE, DELETE)
  '_id': o.id,
  'status': o.status,
  'createdAt': o.created_at,
  'updatedAt': o.updated_at,
  'customer': (
    SELECT JSON_DUALITY_OBJECT(
      'id': c.id,
      'name': c.name,
      'email': c.email
    ) FROM customers c WHERE c.id = o.customer_id
  ),
  'items': (
    SELECT JSON_ARRAYAGG(JSON_DUALITY_OBJECT(WITH(INSERT, UPDATE, DELETE)
      'id': i.id,
      'quantity': i.quantity,
      'unitPrice': i.unit_price,
      'product': (
        SELECT JSON_DUALITY_OBJECT(
          'id': p.id,
          'sku': p.sku,
          'name': p.name
        ) FROM products p WHERE p.id = i.product_id
      )
    )) FROM order_items i WHERE i.order_id = o.id
  )
) FROM orders o;

-- A deliberately smaller read-only document for the support tool.
CREATE OR REPLACE JSON DUALITY VIEW customer_orders_dv AS
SELECT JSON_DUALITY_OBJECT(
  '_id': c.id,
  'name': c.name,
  'orders': (
    SELECT JSON_ARRAYAGG(JSON_DUALITY_OBJECT(
      'id': o.id,
      'status': o.status,
      'createdAt': o.created_at
    )) FROM orders o WHERE o.customer_id = c.id
  )
) FROM customers c;
