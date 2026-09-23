package com.bazlur.orders.json;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class JsonTest {
    @Test void ambiguousJsonIsRejected() {
        assertThrows(Exception.class, () -> Json.parse("{\"status\":\"PENDING\",\"status\":\"SHIPPED\"}"));
        assertThrows(Exception.class, () -> Json.parse("{} {}"));
    }

    @Test void decimalsStayExactAndFormattingDoesNotCountAsAChange() {
        var price = Json.parse("{\"unitPrice\":0.1}").path("unitPrice");
        assertEquals(new java.math.BigDecimal("0.1"), price.decimalValue());
        assertEquals("{\"unitPrice\":149}", Json.encode(Json.parse("{\"unitPrice\":149.00}")));
        assertTrue(Json.sameValue(Json.parse("{\"p\":49.50}"), Json.parse("{\"p\":49.5}")));
        assertFalse(Json.sameValue(Json.parse("{\"p\":49.50}"), Json.parse("{\"p\":49.51}")));
    }
}
