package com.bazlur.orders.ai;

import com.bazlur.orders.json.Json;
import module java.base;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The duality view does not promise array order, and the model's answers changed with it,
 * so Java puts a customer's orders newest first before anyone reads them.
 */
final class OrderHistory {
    // createdAt is "yyyy-MM-dd HH:mm:ss.SSSSSS", so string order is time order; ties go to the higher ID.
    static final Comparator<JsonNode> NEWEST_FIRST =
            Comparator.comparing((JsonNode order) -> order.path("createdAt").asString(""))
                    .thenComparingLong(order -> order.path("id").asLong(0))
                    .reversed();

    private OrderHistory() {}

    static List<JsonNode> newestFirst(JsonNode customer) {
        var orders = new ArrayList<JsonNode>();
        customer.path("orders").forEach(orders::add);
        orders.sort(NEWEST_FIRST);
        return orders;
    }

    /** Returns the customer document with its orders array sorted; a null array stays null. */
    static String sortOrders(String customerDocument) {
        var customer = Json.parse(customerDocument);
        if (customer instanceof ObjectNode document && document.path("orders") instanceof ArrayNode orders) {
            var sorted = newestFirst(document);
            orders.removeAll();
            orders.addAll(sorted);
        }
        return Json.encode(customer);
    }
}
