package com.bazlur.mrs;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

final class Json {
    static final JsonMapper MAPPER = JsonMapper.builder().build();
    static String encode(Object value) { return MAPPER.writeValueAsString(value); }
    static JsonNode parse(String value) { return MAPPER.readTree(value); }
    private Json() {}
}
