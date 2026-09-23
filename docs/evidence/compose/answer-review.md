# Review of the Compose CPU inference run (orders sorted in Java)

September 23, 2026, with orders sorted newest first in Java and all Ollama traffic going through LangChain4j (no direct HTTP client). The three fixture answers are word-for-word identical to the previous Compose run, made before the LangChain4j-only change. Ollama 0.32.9 in Docker, `llama3.1:8b`, digest `46e0c10c039e019119339687c3c1757cc81b9da49709a3b3924863ba87ca666e`. LangChain4j 1.20.0, a fresh MySQL fixture per case, temperature 0 and seed 42. The traces are unedited.

| Case | Observed behavior | Qualification |
|---|---|---|
| [History](ollama-customer-42.json) | Received `[1003, 1001, 1002]`. Correct tool, IDs, statuses and order; labels 1002 "closed, not pending" | The closing note ("no other open orders besides these three") blurs the open/closed distinction it just made. |
| [Detail](ollama-order-1001.json) | Correct products, quantities, purchase prices and PROCESSING status; no currency invented | Omits the requested “currency unspecified” label. |
| [Empty history](ollama-customer-44.json) | Correctly interprets null history as no orders | Repeats the conclusion. |

All 55 tests passed. They check tool selection and selected facts, not every sentence.

## Sorting fixed the input, not the model

Earlier runs showed that the answer's correctness depended on the unspecified order of the view's `orders` array ([September 22](../compose-2026-09-22/answer-review.md), [September 23 before sorting](../compose-2026-09-23-before-sorting/answer-review.md)). The tool now sorts that array in Java, so the model always receives the same input for the same data.

The [packaged CLI run](agent-cli.txt) shows the limit of that fix. Its [trace](agent-cli-trace.json) confirms the model received `[1003, 1001, 1002]` from the live database, where 1001 had already been shipped, yet it listed 1002 before the newer 1001. Statuses were correct, and it gave no explanation of which orders are pending.

An [untraced CLI run](agent-cli-untraced.txt) about five minutes earlier used the same image, data and question, and answered differently: it listed only 1003 as pending and made no ordering mistake. Temperature 0 and a fixed seed did not make the CPU runs repeatable, so a single good or bad answer says little on its own.

Sorted input removes one source of variation. It does not make the prose reliable. A support screen should render ordering and open/closed status from Java and treat the model's text as commentary.

This run is not a benchmark or a general LLM evaluation.
