# Historical review: native-client local-model run

Run: September 22, 2026. Ollama 0.32.9, `llama3.1:8b`, digest `46e0c10c039e019119339687c3c1757cc81b9da49709a3b3924863ba87ca666e`. Each case used an isolated, freshly seeded MySQL database. The traces are unedited model outputs, not rewritten examples.

| Case | What worked | Remaining issue |
|---|---|---|
| [Customer 42 history](native-ollama-customer-42.json) | Correct `getCustomerOrders(42)` call; all three IDs and statuses match the database; list is in descending date order | The concluding sentence says all three orders are still open. That is wrong: 1002 is SHIPPED. Only 1001 PROCESSING and 1003 PENDING are open under the demo's policy. |
| [Order 1001 detail](native-ollama-order-1001.json) | Correct `getOrder(1001)` call; both products, quantities, unit prices and status match the fixture; no currency invented | No factual error found in this short answer. This is one observation, not a quality guarantee. |
| [Customer 44 history](native-ollama-customer-44.json) | Correct customer ID; interprets null order history as no orders | No factual error found in this short answer. |

The 49 automated checks pass. The three model tests verify tool selection and selected answer values, including the price-format constraint. They do **not** verify every statement in the prose. The incorrect history conclusion was found by reading the captured answer; it is not a successful classification result just because the test is green.

For a deployed support UI, compute open/closed membership and ordering in application code and render those facts directly. The existing deterministic `AgentDemo` shows that policy. Any model-written explanation still needs an evaluation and review strategy appropriate to its use. JSON Duality Views simplify document assembly; they do not validate a model's explanation.

Earlier [argument](ollama-invalid-argument.json), [currency](ollama-currency-assumption.json) and [date-ordering](ollama-first-run.json) failures are preserved separately. The revised request flow fixed the observed argument/currency cases, while this final history conclusion demonstrates the remaining limit.
