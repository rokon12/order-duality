# Review of the Compose CPU inference run

September 22, 2026. Ollama 0.32.9 in Docker, `llama3.1:8b`, digest `46e0c10c039e019119339687c3c1757cc81b9da49709a3b3924863ba87ca666e`. LangChain4j 1.20.0, a fresh MySQL fixture per case, temperature 0 and seed 42. The traces are unedited.

| Case | Observed behavior | Qualification |
|---|---|---|
| [History](ollama-customer-42.json) | Correct tool, customer, IDs, statuses and descending date order | The list labels SHIPPED as closed, but the final sentence incorrectly calls three orders open, including the shipped order. The extra fourth list entry is also confusing. Only 1003 PENDING and 1001 PROCESSING are open. |
| [Detail](ollama-order-1001.json) | Correct products, quantities, purchase prices and PROCESSING status; no currency invented | Omits the requested “currency unspecified” label. |
| [Empty history](ollama-customer-44.json) | Correctly interprets null history as no orders | Repeats the conclusion. |

All 53 tests passed, including tool selection and selected answer facts. Those checks do not validate every prose statement. The incorrect history conclusion resembles the earlier native-client failure and did not appear in the final native LangChain4j answer. Running the same model and prompt on another runtime does not guarantee identical prose or accuracy.

The Compose wiring and data access work. The model's explanation remains fallible. Compute operational open/closed membership and ordering in Java if those facts drive a support screen; use generated prose as an accompanying explanation. This run is not a benchmark or a general LLM evaluation.

The subsequent [packaged CLI run](agent-cli.txt) used the live demo database, where 1001 had already been shipped. Its IDs/statuses were correct, but it listed 1002 before the newer 1001. This is another observed ordering error, separate from the isolated-fixture history conclusion above.
