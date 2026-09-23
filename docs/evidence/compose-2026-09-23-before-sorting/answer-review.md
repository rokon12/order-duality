# Review of the Compose CPU inference run

September 23, 2026, after the HikariCP and Jackson 3 changes. Ollama 0.32.9 in Docker, `llama3.1:8b`, digest `46e0c10c039e019119339687c3c1757cc81b9da49709a3b3924863ba87ca666e`. LangChain4j 1.20.0, a fresh MySQL fixture per case, temperature 0 and seed 42. The traces are unedited.

| Case | Observed behavior | Qualification |
|---|---|---|
| [History](ollama-customer-42.json) | Correct tool, customer, IDs, statuses and descending date order; correctly says only 1003 and 1001 are open | The fourth list entry ("No other orders.") is unnecessary. It does not explain the difference between PENDING and PROCESSING. |
| [Detail](ollama-order-1001.json) | Correct products, quantities, purchase prices and PROCESSING status; no currency invented | Omits the requested “currency unspecified” label. |
| [Empty history](ollama-customer-44.json) | Correctly interprets null history as no orders | Repeats the conclusion. |

All 54 tests passed, including tool selection and selected answer facts. Those checks do not validate every prose statement.

## What changed since the September 22 Compose run

The [September 22 run](../compose-2026-09-22/answer-review.md) used the same model, prompt, temperature and seed, and its history answer wrongly called the shipped order open. This run's history answer is correct. The only difference in the model's input was the **order of the `orders` array**: the duality view returned `[1002, 1001, 1003]` on September 22 and `[1001, 1002, 1003]` here. The view does not promise an order, and the application does not sort the array before handing it to the model.

So the earlier mistake is not fixed; it depends on input order that nobody controls. This is the strongest argument in the evidence for computing open/closed membership and ordering in Java and using the model only to explain those facts.

## Packaged CLI run

The [CLI run](agent-cli.txt) used the live demo database, where 1001 had already been shipped by the smoke test. Statuses and the conclusion (only 1003 pending) are correct, but it lists 1002 before the newer 1001. The September 22 CLI run made the same ordering error.

This run is not a benchmark or a general LLM evaluation.
