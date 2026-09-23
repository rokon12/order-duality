package com.bazlur.orders.json;

import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.cfg.JsonNodeFeature;
import tools.jackson.databind.json.JsonMapper;

/** Jackson 3 configuration shared by the API, the conventional path and the agent. Its exceptions are unchecked. */
public final class Json {
    public static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(JsonNodeFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            // Stated explicitly: captured evidence shows 149.00 as 149.
            .enable(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES)
            .build();

    private Json() {}

    public static JsonNode parse(String value) {
        return MAPPER.readTree(value);
    }

    public static String encode(Object value) {
        return MAPPER.writeValueAsString(value);
    }

    // JSON has numbers, not Java's IntNode/LongNode/DecimalNode distinctions.
    // A formatter may legitimately write 149.00 as 149.
    public static boolean sameValue(JsonNode first, JsonNode second) {
        return first.equals((a, b) -> a.isNumber() && b.isNumber()
                ? a.decimalValue().compareTo(b.decimalValue()) : (a.equals(b) ? 0 : 1), second);
    }
}
