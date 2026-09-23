package com.bazlur.orders.ai;

import com.bazlur.orders.application.OrderStatus;
import com.bazlur.orders.json.Json;
import module java.base;

public final class MockAgent {
    public static String summarize(String document) {
        var customer = Json.parse(document);
        var orders = OrderHistory.newestFirst(customer);
        var out = new StringBuilder(customer.path("name").asString()).append(" — recent orders:\n");
        if (orders.isEmpty()) out.append("No orders.\n");
        orders.stream().limit(5).forEach(o -> {
            // Exhaustive over the enum: a new status will not compile until it is described here.
            var reason = OrderStatus.parse(o.path("status").asString()).map(status -> switch (status) {
                case PENDING -> "pending: not yet being processed";
                case PROCESSING -> "still open: being prepared, not shipped";
                case SHIPPED -> "shipped; delivery is not confirmed by this data";
                case CANCELLED -> "cancelled";
            }).orElse("unknown status");
            out.append("#").append(o.path("id").asLong()).append(": ").append(reason).append('\n');
        });
        return out.toString();
    }
}
