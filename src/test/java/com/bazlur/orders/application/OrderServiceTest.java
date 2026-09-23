package com.bazlur.orders.application;

import com.bazlur.orders.json.Json;
import org.junit.jupiter.api.Test;
import static com.bazlur.orders.application.OrderStatus.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.bazlur.orders.application.OrderException.Kind.*;

class OrderServiceTest {
    @Test void shippedOrdersCannotReturnToProcessing() {
        assertEquals(NOT_ALLOWED, assertThrows(OrderException.class,
                () -> OrderService.validateTransition(SHIPPED, PROCESSING)).kind());
        assertDoesNotThrow(() -> OrderService.validateTransition(PROCESSING, SHIPPED));
        assertDoesNotThrow(() -> OrderService.validateTransition(SHIPPED, SHIPPED));
    }

    @Test void writesRequireTheReadTokenAndMatchingIdentity() throws Exception {
        var document = Json.parse("{\"_id\":1001,\"status\":\"SHIPPED\"}");
        assertEquals(MISSING_TOKEN, assertThrows(OrderException.class, () -> OrderService.validateEnvelope(1001, document)).kind());
        assertEquals(INVALID_REQUEST, assertThrows(OrderException.class, () -> OrderService.validateEnvelope(1002, document)).kind());
        var unknown = Json.parse("{\"_id\":1001,\"status\":\"DELIVERED\",\"_metadata\":{\"etag\":\"a5dd9751812e07b5d33222d768ee2aec\"}}");
        assertEquals(NOT_ALLOWED, assertThrows(OrderException.class, () -> OrderService.validateEnvelope(1001, unknown)).kind());
        var numericEtag = Json.parse("{\"_id\":1001,\"status\":\"SHIPPED\",\"_metadata\":{\"etag\":12345}}");
        assertEquals(MISSING_TOKEN, assertThrows(OrderException.class, () -> OrderService.validateEnvelope(1001, numericEtag)).kind());
    }

}
