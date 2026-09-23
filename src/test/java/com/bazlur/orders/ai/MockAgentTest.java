package com.bazlur.orders.ai;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MockAgentTest {
    @Test void mockDistinguishesPendingFromShippedAndSortsRecentFirst() throws Exception {
        var answer = MockAgent.summarize("""
                {"name":"Alice","orders":[
                  {"id":1,"status":"SHIPPED","createdAt":"2026-09-01"},
                  {"id":2,"status":"PENDING","createdAt":"2026-09-20"}]}
                """);
        assertTrue(answer.indexOf("#2") < answer.indexOf("#1"));
        assertTrue(answer.contains("delivery is not confirmed"));
    }
}
