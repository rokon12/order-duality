package com.bazlur.mrs.support;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

public final class Json {
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    public static String encode(Object value) {
        return MAPPER.writeValueAsString(value);
    }

    public static JsonNode parse(String value) {
        return MAPPER.readTree(value);
    }

    public static JsonNode tree(Object value) {
        return MAPPER.valueToTree(value);
    }

    private Json() {}
}
