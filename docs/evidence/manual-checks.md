# Packaged-demo checks

Performed September 22, 2026. The final stack is Java 25, Spring Boot 4.1.1, LangChain4j 1.20.0, MySQL 9.7.2 Community and native Ollama 0.32.9.

## Final framework-based application

- `mvn -B -ntp clean verify -Pintegration,ollama` passed 20 unit tests and 33 integration tests, with no failures, errors or skips. Three cases made real local model calls. See [test-summary.txt](test-summary.txt) and the [answer review](ollama-answer-review.md).
- Launched `PORT=0 java -jar target/order-duality.jar`. Embedded Tomcat selected loopback port 56704, and startup reported MySQL 9.7.2 Community.
- Ran `API_URL=http://127.0.0.1:56704 ./scripts/smoke.sh`. Output: `PASS: GET, PUT, stale PUT (409), relational endpoint, customer tool projection`. The temporary server was stopped afterward.
- The first packaged agent run exposed a CLI option collision: `--trace` also enabled Spring Boot TRACE logging. Renamed the file option to `--trace-file`, rebuilt the jar with `mvn -B -ntp package` (all 20 unit tests passed), and verified the final command against the real model again. The database/view code and model prompt were unchanged by this final option rename.
- Ran `java -jar target/order-duality.jar --agent --customer-id=42 --question="What is in my order 1001? Include item quantities and unit prices." --trace-file=target/ollama-cli-final.json`. It invoked `getOrder(1001)`, returned both products/quantities/purchase prices and the live SHIPPED status, wrote the [trace](ollama-cli.json), and exited. No web server or TRACE-level logging started. The isolated integration fixture uses PROCESSING; the live demo has already been changed by the smoke script.
- Ran `java -jar target/order-duality.jar --mock-agent --customer-id=44`. It printed Priya Shah's empty history, made no model call and exited without a web server.
- Rendered the updated architecture SVG to a 2160 × 1800 PNG and visually inspected the Spring Boot, LangChain4j, local Ollama and database boundaries.
- Validated local Markdown links, JSON evidence, shell script syntax, POM XML and SVG XML.

## Database walkthroughs from the initial investigation

These SQL files were unchanged by the framework migration.

- Recreated this project's demo volume and ran the digest-pinned `docker compose up -d --wait`. Schema, seed, views and API grants initialized successfully. Later added the agent account, whose grants are also part of initialization and the current integration tests.
- Executed `sql/document-operations.sql` through the container's MySQL client. Status update read back as SHIPPED; original lines were preserved; inserted order 1010 referenced customer 42; deleting it left zero owned lines and four shared products. Walkthrough transactions rolled back.
- Executed `sql/json-column-comparison.sql`. Snapshot name stayed `Alice Rahman`, the live view name became `Alice R.`, and the temporary JSON document accepted nonexistent customer ID 9999. The relational change rolled back.

The historical native-client traces and failures are retained separately. No remote repository was created and nothing was published or submitted to Oracle ACE.

## Complete Docker Compose setup

- Built with digest-pinned Maven/Java images and started the entire stack with `API_PORT=8088 docker compose up --build -d --wait`.
- Ollama 0.32.9 downloaded `llama3.1:8b` into a new named volume. Its model ID matches the earlier native run: `46e0c10c039e`. The init container exited successfully; the API started afterward.
- The API, MySQL and Ollama became healthy. Ollama has no published host port; native Ollama can keep port 11434. The API was tested on host loopback port 8088.
- `API_URL=http://127.0.0.1:8088 ./scripts/smoke.sh` passed GET, PUT, stale PUT (409), conventional mapping and customer-history reads.
- Confirmed the runtime reports Temurin 25.0.4+7 and the application runs as UID/GID 10001. Ollama reports an 8,192-token context using 100% CPU.
- Ran `API_PORT=8088 docker compose run --rm --build tests`: all 20 unit tests and 33 integration tests passed, none skipped. The test container used Maven 3.9.11 and Java 25.0.1. See [the captured summary](compose/test-summary.txt).
- Reviewed all three CPU model answers. The history answer's final open-order conclusion is wrong despite correct IDs/statuses, as recorded in [the review](compose-2026-09-22/answer-review.md). The tests check selected facts, not every sentence.
- Ran the documented `docker compose run --rm app --agent --customer-id=42` command with the recent-orders question. It reused the installed model, called `getCustomerOrders(42)` through LangChain4j on Java 25.0.4, printed an answer and exited successfully. See [the CLI output](compose/agent-cli.txt). The live database has order 1001 SHIPPED after the smoke test.

## September 23, 2026: regenerated after the HikariCP and Jackson 3 changes

- Ran `mvn -B -ntp clean verify -Pintegration,ollama` on Temurin 25.0.1: 21 unit tests and 33 integration tests passed, none skipped. See [test-summary.txt](test-summary.txt).
- Replaced `experiments.txt`, the three `ollama-*.json` traces and the files in `docs/examples/` with this run's output. Apart from timestamps, etags and optimizer cost estimates, the results match the September 22 capture. The three model answers are word-for-word identical. The traces now also record `supportsThinking`.
- The view's lookup plan scanned `order_items` again, as in the original capture; [explain-rerun.txt](explain-rerun.txt) keeps the run in which it used the `items_order` index.
- Regenerated the Compose evidence too. The September 22 Compose run moved to `compose-2026-09-22/` unchanged, and its links now point there. `API_PORT=8088 docker compose run --rm --build tests` passed 21 unit and 33 integration tests in the container, the smoke script passed against the rebuilt app, and the runtime image still runs as uid 10001. See [compose/test-summary.txt](compose/test-summary.txt).
- The Compose history answer is correct this time. The only input difference from September 22 is the order of the view's `orders` array (`[1001, 1002, 1003]` instead of `[1002, 1001, 1003]`); see [the review](compose/answer-review.md).

## September 23, 2026: orders sorted in Java, LangChain4j cleanup

- `OllamaAgent` no longer counts tool calls itself (LangChain4j's round limit remains) or measures stage timing; traces keep LangChain4j's token counts. `OllamaRuntime` gets the installed model and digest from LangChain4j's `OllamaModels` and calls Ollama directly only for the version, capabilities and cloud-model checks.
- The customer-orders tool sorts orders newest first in Java (`OrderHistory`). Two unit tests replace the removed call-count test: one feeds the `[1002, 1001, 1003]` order that misled the model, one keeps a null history null.
- Native: 22 unit and 33 integration tests passed on Temurin 25.0.1; evidence in this folder and `docs/examples/` replaced. Compose: the same 55 passed in the container and the smoke script passed; the previous Compose run moved to `compose-2026-09-23-before-sorting/`.
- The Compose CLI was re-run with `--trace-file` to capture its tool input. The model received sorted orders and still listed 1002 before 1001; see [the review](compose/answer-review.md).

- `OllamaRuntime` now uses LangChain4j's `OllamaModels` for the installed-model digest (`availableModels()`) and for the tool capability, parameter size and quantization (`modelCard()`). It always sends `think:false`; a direct `curl` confirmed Ollama 0.32.9 accepts that for `llama3.1:8b`, which has no thinking support. The only remaining direct call is `/api/show` for the cloud-model guard, because `OllamaModelCard` omits `remote_model`/`remote_host`. The Ollama server version is no longer recorded; the model digest identifies the weights.
- Native rerun on Temurin 25.0.1: 22 unit and 33 integration tests passed, and all three model answers were word-for-word identical to the previous native run. The native traces and `experiments.txt` were replaced for the new trace format; the Compose traces in `compose/` were captured just before this change and still include `ollamaVersion`.
- Removed the last direct HTTP call. `OllamaRuntime` no longer uses `HttpClient`: every Ollama request goes through LangChain4j. Because `OllamaModelCard` does not expose `remote_model`/`remote_host`, the cloud guard is now a check on Ollama's cloud tag naming (`…-cloud`, `:cloud`) plus `OLLAMA_NO_CLOUD=1` on the server. A cloud model copied to a local-looking name would pass the name check. With Ollama stopped, the CLI fails with "Cannot check the model at local Ollama …; is ollama serve running?". Full rerun: 22 unit and 33 integration tests passed; traces identical to the committed ones apart from timestamps.
- Refreshed the Compose evidence after the LangChain4j-only change: `API_PORT=8088 docker compose run --rm --build tests` passed 22 unit and 33 integration tests, the smoke script passed, and the three fixture answers matched the previous Compose run word for word. A traced CLI run repeated the earlier 1002-before-1001 mistake; an untraced run five minutes earlier, with the same image, data and question, listed only the pending order correctly. Both are kept in `compose/`.

- Simplified the model setup: `OllamaRuntime` and its preflight check are gone. `OllamaProperties.chatModel()` builds the `OllamaChatModel` (same settings) from the configured URL and model, and `OllamaAgent` builds its explainer AI service once. The loopback-URL and cloud-tag guards went with the class; the README now tells native users to run Ollama with `OLLAMA_NO_CLOUD=1`, as Compose does. Traces now record only the configured model name; `ollama list` still shows digest `46e0c10c039e` for `llama3.1:8b`. Native rerun: 20 unit and 33 integration tests passed and the three answers were word-for-word identical; the native evidence was replaced. The Compose evidence predates this change.
- `OllamaAgent` now builds both AI services once, in its constructor. The customer scope and the per-question output budget travel in LangChain4j's `InvocationParameters`; `CustomerOrderTools` is a single shared instance that reads the customer from those parameters. The generated tool declarations are byte-identical to `docs/examples/tools.json`, so the model never sees the parameters. Full native run: 20 unit and 33 integration tests passed; all three answers were word-for-word identical.

