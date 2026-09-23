# Review of the Spring Boot / LangChain4j model run

Run: September 23, 2026, on Temurin 25.0.1, after the customer-orders tool began sorting orders newest first in Java. Spring Boot 4.1.1, LangChain4j 1.20.0, Ollama 0.32.9 and `llama3.1:8b`, digest `46e0c10c039e019119339687c3c1757cc81b9da49709a3b3924863ba87ca666e`. Each case used a freshly seeded, isolated MySQL database. Traces contain unedited model output.

| Case | What the answer got right | Remaining limitation |
|---|---|---|
| [Customer 42 history](ollama-customer-42.json) | Calls `getCustomerOrders(42)` and receives `[1003, 1001, 1002]`; correct IDs/statuses in descending date order; explains that the SHIPPED order is closed | Adds an unnecessary remark that there are no cancelled orders. It lists PENDING and PROCESSING without explaining their difference as requested. No false order or status was found. |
| [Order 1001 detail](ollama-order-1001.json) | Calls `getOrder(1001)`; correct products, quantities, purchase prices and PROCESSING status; no currency symbol or code invented | Omits the requested “currency unspecified” label. The numeric prices are accurate, but it does not fully follow the output-format instruction. |
| [Customer 44 history](ollama-customer-44.json) | Calls `getCustomerOrders(44)`; correctly interprets null history as no orders | Repeats the same conclusion twice. |

All 54 automated checks pass (20 unit, 34 integration, including the three real-model cases). The three real-model tests check tool selection and selected facts; they do not verify every sentence, instruction or presentation detail. This review found no factual error in these three final answers, but it did find incomplete instruction following. Three fixture questions are not a general quality evaluation.

The [first LangChain4j price answer](langchain4j-currency-assumption.json) invented dollar signs and failed the unchanged price assertion. Its [build log](langchain4j-first-test-run.txt) is retained. A more explicit item format produced the numeric answer above. This is an observed improvement for the tested question, not a guarantee for future requests.

The earlier custom native client also produced a history answer that incorrectly called a shipped order open. That [historical review](native-ollama-answer-review.md) and its linked traces remain available; they are not the final LangChain4j outputs.

## Why the tool now sorts orders

Before sorting was added, a native run on September 23 (no prompt or data change) received Alice's orders as `[1002, 1001, 1003]` instead of `[1001, 1002, 1003]`. Its [answer](ollama-customer-42-reordered-input.json) claimed to sort by `createdAt` descending but listed 1002 (September 10) before 1001 (September 18), and it did not say which orders are open. The test still passed because every expected ID and status appeared. The [September 22 Compose run](compose-2026-09-22/answer-review.md) shows the same pattern: the `[1002, 1001, 1003]` input produced the wrong answer there too, while the [next Compose run](compose-2026-09-23-before-sorting/answer-review.md) got `[1001, 1002, 1003]` and answered correctly. The duality view does not promise array order, so answer quality depended on something nobody controlled. The tool now sorts orders newest first in Java; the run reviewed above is the first native run with sorted input. Sorting removes that variation but does not make the prose reliable: in a [Compose CLI run](compose/answer-review.md) the model received sorted input and still listed 1002 before 1001.

For an operational screen, compute open/closed membership and ordering in Java and render those facts directly. A model-written explanation needs evaluation appropriate to its use. JSON Duality Views remove document assembly work; they do not validate the explanation.
