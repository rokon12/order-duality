# Lab notes: Order Duality

These notes were collected while building the application, before drafting the article. The final assertions live in [DualityIT](../src/test/java/com/bazlur/orders/integration/DualityIT.java). The [captured run](evidence/experiments.txt) records server errors and plans; [test-summary.txt](evidence/test-summary.txt) records the counts. This is a functional investigation on a small fixture, not a benchmark.

## Environment

- Investigation date: September 22, 2026.
- `SELECT VERSION(), @@version_comment`: **9.7.2 | MySQL Community Server - GPL**.
- Official Docker image: `mysql:9.7.2`, digest `sha256:30a0abfa7b502a496e12339b54cd07aaa70363396dc4b8e8a72a92804a505cd6`.
- Tested architecture: Linux ARM64 container on macOS ARM64. AMD64 was not tested locally.
- Java: Temurin 25.0.1; Maven 3.9.4; Connector/J 9.7.0; Jackson 2.20.1; JUnit 6.0.3.
- InnoDB; default `REPEATABLE-READ`; strict SQL mode, recorded verbatim in the evidence.
- 20 unit tests + 33 integration tests; no failures, errors, or skipped tests in the captured run.

The initially chosen `mysql:9.7.3` tag was absent from Docker Hub, even though release notes existed. The demo uses the available 9.7.2 image and pins its digest. It does not infer the server version from a tag: the suite asserts `VERSION()`.

## What worked

| Experiment | Observed result | Test method |
|---|---|---|
| Order read | One JSON `data` column, nested customer, two lines, nested product objects, metadata etag | `readOrderWithCustomerAndMultipleItems` |
| Conventional read | One joined query and Java records match the business values after normalizing timestamp notation and JSON numeric node types | `conventionalMappingHasTheSameBusinessData` |
| Status replacement | `orders.status` changes; customer and items stay the same; etag changes | `statusUpdateChangesTheRelationalRow` |
| Change, add, remove lines in one replacement | Quantity changes; omitted line is deleted; new line is inserted; join columns are inferred; shared products survive | `oneReplacementUpdatesInsertsAndDeletesLines` |
| Insert order with lines | Existing customer/product references are reused; foreign keys are populated | `insertAndDeleteAnOrderPreserveSharedEntities` |
| Delete order | Root and owned lines disappear; customers and products remain | same test |
| Invalid quantity plus valid root edit | Error 3819; root status, lines, timestamp and etag remain unchanged | `failedMultiTableUpdateIsAtomic` |
| Invalid product FK during document insert | Error 1452 through a scalar-FK probe view; neither root nor line remains | `lineForeignKeyFailureRollsBackTheInsertedOrder` |
| Explicit rollback | A successful view update is undone by JDBC transaction rollback | `explicitTransactionRollbackUndoesDocumentWrite` |
| Two simultaneous writes using one etag | Exactly one commits; the other reports 6494 in this run | `simultaneousWritersCannotBothWin` |
| Relational edits between document read/write | Changes to root status, line quantity, shared customer name and projected product name all invalidate the old token | `staleDocumentDetectsRelationalAndSharedChanges` |
| Unprojected catalog price edit | Order JSON/etag unchanged; purchase price stays 149.00 | `unprojectedCatalogPriceDoesNotInvalidateTheOrderEtag` |
| `WITH(UPDATE)` alone | Creation and update work; insert fails 6490; delete fails 6498 | `updateOnlyAnnotationIsAcceptedAndEnforced` |
| Real HTTP server and restricted account | GET, PUT, 409 conflict, malformed input, missing token, protected-field rejection and both comparison endpoints pass | `httpReadUpdateValidationAndConflict` |
| Database privilege boundary | API account cannot update base tables or insert into the view (1142) | `applicationAccountCannotWriteBaseTablesOrInsertOrders` |
| Agent boundary | Customer summary document supports a deterministic recent-order explanation without SQL generation | `toolBoundaryAndMockUseActualViewData` |

## Surprises and resulting changes

**No token means no stale-write protection.** Removing `_metadata` allowed an old document to overwrite a later `CANCELLED` status with `SHIPPED`. This is asserted in `missingEtagAllowsAStaleDocumentToOverwriteAChange`. The REST API therefore requires a well-formed token, returns 428 if it is missing, preserves it when writing, and maps MySQL error 6494 to 409. It never replaces the caller's token with a freshly read one. The preflight comparison helps return useful HTTP errors; it does not replace the database's final check.

**Replacement is not PATCH.** Omitting the complete `items` member deleted both owned lines. That successful operation is a hazard if a client thinks an absent field means “leave it alone.” Missing projected scalar `status`, by contrast, failed with 6495. The API accepts a complete document but permits only status edits. Broader DML remains a database experiment, not an unrestricted public write route.

**An empty collection is `null`.** Order 1005 has no lines, and customer 44 has no orders. Both collection values are JSON null. The conventional mapper naturally emits `[]`. The comparison test covers a populated order; it does not hide this empty-case contract difference. GET passes MySQL's document through unchanged.

**Echoing `updatedAt` suppressed automatic timestamp maintenance.** Without the trigger, the status changed but the projected timestamp kept its old value. A replacement supplies that column explicitly. `orders_touch` now assigns the database timestamp on every order UPDATE, for either access path. A test temporarily removes the trigger to reproduce the original behavior, then restores it. Child-only changes need not update the order row; the etag, not `updatedAt`, is the aggregate concurrency token. Even an accepted no-op order update can advance the timestamp.

**Formatting changed Java's node types.** A JSON round trip changed 149.00 into 149; Jackson's strict node equality rejected an otherwise unchanged document. `Json.sameValue` compares numeric values using `BigDecimal.compareTo`, without flattening decimal amounts into floating point. Array order and all other values still matter. The API does not claim to accept reordered item arrays.

**JSON type handling includes coercion.** A quantity string `"3"` became SQL INT 3. Supplying an object for status failed the SQL status CHECK, rather than a dedicated JSON type error. Database validation is useful, but it is not a substitute for a deliberately specified API schema.

**The read plan deserves attention.** `EXPLAIN SELECT` succeeded and showed root scanning/materialization before the JSON-path ID filter. The baseline plan used indexed line access with the root/customer resolved before execution. These are estimated plans over five seeded orders, with estimates affected by prior test mutations; they are not elapsed-time measurements. `EXPLAIN UPDATE` failed with 6482. We have not proved that every alternative predicate or future release behaves the same way.

## Restrictions reproduced

| Attempt | Error | Meaning in this demo |
|---|---:|---|
| Malformed JSON | 3141 | Document cannot be parsed |
| Additional unmapped property | 6489 | Arbitrary extensions are not accepted |
| Missing projected scalar | 6495 | Replacement must supply scalar values |
| Root primary-key change | 6496 | Identity is not an editable scalar |
| Change read-only customer name | 6497 | The shared row cannot be edited through this projection; the root edit rolls back too |
| Replace customer ID with nonexistent ID | 6497 | Rejected at annotation processing; this particular test is **not** evidence of an FK error |
| Root WHERE in definition | 6457 | Filter when reading the view, not in the root definition |
| Calculated line total | 6459 | `quantity * unit_price` cannot be a mapped scalar |
| Nested ORDER BY | 6454 | The view is not a sorted collection contract |
| Flatten line/product with JOIN | 6455 | One table per projected object |
| Omit nested row PK | 6469 | Row identities must remain in the document |
| Composite table PK | 6480 | This tested shape is unsupported |
| Multi-root UPDATE | 6482 | Not a bulk-write abstraction |
| INSERT … SELECT | 6482 | Not an import pipeline |
| INSERT with `(data)` column list | 6482 | Use `INSERT INTO orders_dv VALUES (?)` |

## Documentation checks and differences

Primary sources were the MySQL 9.7 manual, accessed September 22, 2026. These are MySQL findings, not claims imported from Oracle Database.

- [9.7.0 release notes](https://dev.mysql.com/doc/relnotes/mysql/9.7/en/news-9-7-0.html): Community DML availability matches our actual read/write tests. No Enterprise server, HeatWave cluster, or cloud account was used.
- [Creation syntax](https://dev.mysql.com/doc/refman/9.7/en/create-json-duality-view.html): the required `_id`, object/table relationship, column projections and statement restrictions informed the probes. The error reference still describes some all-or-nothing annotation combinations; `WITH(UPDATE)` alone actually worked here. The current creation grammar allows independent annotations, which agrees with the experiment.
- [DML requirements](https://dev.mysql.com/doc/refman/9.7/en/jdv-requirements.html): an exception in the update section says a modified sub-object without UPDATE may only receive an existence check. Our changed customer-name case returned 6497 instead. Treat that exception as unresolved for other shapes; this project asserts the observed behavior of its view.
- [Concurrency](https://dev.mysql.com/doc/refman/9.7/en/jdv-concurrency.html): retaining the read token catches the tested conflicts. The practical caveat is token omission; the database accepted it in our test.
- [DML limitations](https://dev.mysql.com/doc/refman/9.7/en/jdv-limitations.html): its EXPLAIN restriction is in a DML section. Our SELECT explain worked, so the article does not generalize it to reads. Multiple child operations in one root replacement also worked; “multiple objects” should not be read as forbidding that tested case.
- [DML overview](https://dev.mysql.com/doc/refman/9.7/en/json-duality-views-updatable.html): the atomicity and trigger descriptions are consistent with our targeted tests. This is not a crash-recovery or replication test.

## Adding real local inference

The first implementation used a deterministic `AgentDemo` (now `MockAgent`), followed by a native Ollama client. The final implementation uses LangChain4j 1.20.0 for chat and tool execution, with Spring Boot 4.1.1 for HTTP, configuration and lifecycle. The mock remains an offline comparison. Both real-model implementations use the same repository and duality views. The early observations below are historical; the framework migration is recorded separately.

Environment: Ollama **0.32.9**, native macOS ARM64; `llama3.1:8b`, **8.0B Q4_K_M**, model digest `46e0c10c039e019119339687c3c1757cc81b9da49709a3b3924863ba87ca666e`. The model was already installed. The server ran with `OLLAMA_NO_CLOUD=1`; all inference requests used `127.0.0.1:11434`. Java checks that the model advertises tool support and does not reference a remote model. No cloud inference or model download was used in these captured runs. Llama remains the default; the other two installed models were diagnostic probes, not a recommendation based on a model benchmark.

- **Tool selection worked.** The customer-history question selected `getCustomerOrders`; the item/price question selected `getOrder`. Java returned the actual MySQL JSON as a tool message, then called the model again for its answer. The empty-history case correctly interpreted SQL/JSON null as no orders.
- **JSON Schema did not enforce argument types.** The first order-detail run proposed a quoted ID. Java rejected it, and the model drew an unsupported conclusion about whether the order existed. The initial test failed. Explicit integer examples and instructions to retry invalid arguments fixed the observed case without weakening validation. See [the failed model trace](evidence/ollama-invalid-argument.json) and [initial test evidence](evidence/ollama-initial-test-run.txt).
- **Numbers did not define currency.** A subsequent detail answer added dollar signs even though there is no currency code in the schema. Two stronger price-format instructions still did not prevent Llama from adding dollar signs. That [failed answer](evidence/ollama-currency-assumption.json) and [failed test run](evidence/ollama-currency-test-run.txt) are retained. The same probe with `mistral-nemo:latest` (12.2B Q4_0, digest `e7e06d107c6c86ed0cf45445f1790720b5092149c4c95f4d965844e9afbfdc89`) also added dollar signs; see [its trace](evidence/ollama-mistral-currency.json) and [test run](evidence/ollama-mistral-test-run.txt). The final flow requests an answer without further tools after a successful batch and repeats the data constraints then. Llama returned numeric prices with “currency unspecified” in the revised case. The detail test continues to check the constraint; it was not removed to obtain a passing run.
- **A prompt is not a sort implementation.** The [first manual run](evidence/ollama-first-run.json) put 1002 before 1001 despite their creation dates. Both were SHIPPED in that reused demo database; 1001 had been changed by the earlier HTTP smoke test. Its pending classification was correct. Integration tests seed their own database and keep 1001 PROCESSING. Do not confuse those two fixtures.
- **The installed Qwen model was not a useful fallback in this run.** A probe with `qwen3-vl:8b`, `think: false`, temperature 0 and an 8,192-token context spent several minutes without returning a usable tool result. I stopped the test and removed its isolated database/accounts. The [interrupted build log](evidence/ollama-qwen-aborted.txt) is retained. This is an incomplete probe, not a completed quality result or a general performance comparison.
- **Access controls are Java/SQL behavior.** `CustomerOrderTools` enforces positive IDs and customer scope; LangChain4j registers only its two annotated methods. An isolated SELECT-only account reads both views successfully; MySQL rejects a direct table read and a document update with 1142. Reading another customer's order fails before the result reaches Ollama. The command-line customer ID is a demo scope, not authenticated identity.
- **The tests are narrow.** Stub-server tests exercise the actual HTTP client, conversation history, correction loop, budgets, unsupported tools, local-model checks and failure responses. Three opt-in live-model tests check selected facts, not every statement or model quality in general. There is no built-in claim verifier, and the default question's chronological ordering is not enforced by Java.

Historical native-client traces: [history](evidence/native-ollama-customer-42.json), [details](evidence/native-ollama-order-1001.json), [empty history](evidence/native-ollama-customer-44.json). Each captures model/server identity, tool input/output, answer and Ollama-reported token/duration fields. Those counters are evidence of the request, not a token-efficiency or latency comparison.

**Manual review found an error in the native-client run.** The history answer lists the correct statuses but incorrectly concludes that all three orders are open, including the SHIPPED order. The three model tests check selected facts; they do not validate every prose claim. See the [historical answer review](evidence/native-ollama-answer-review.md). This remains a documented model-quality limitation, with deterministic application rendering recommended for operational facts.

Protocol reference: Ollama's official [tool-calling guide](https://docs.ollama.com/capabilities/tool-calling) and [chat API](https://docs.ollama.com/api/chat), checked September 22, 2026. Model capabilities, version and digest were also read from the running server's `/api/show`, `/api/version` and `/api/tags` responses.

## Spring Boot and LangChain4j migration

The application packages now separate `api`, `application`, `persistence`, `ai`, `cli`, `config` and `json` under `com.bazlur.orders`. Tests mirror those responsibilities; the database suite lives in `integration`. Spring MVC replaces manual route dispatch, and an `ApplicationRunner` handles the non-web agent command. Boot configuration records bind the database and model settings. SQL and transaction boundaries remain explicit JDBC.

- **A CLI name collided with Boot.** `--trace` enabled framework TRACE logging as well as selecting a model-output file. Packaged execution caught it; the final option is `--trace-file`, verified with a real local inference run.
- **HTTP behavior survived the migration.** The test starts an embedded Tomcat server on an ephemeral loopback port, reads the unquoted JSON response, writes status changes, and verifies HTTP errors and relational rows. Spring handles method and content-type errors; its Allow header includes HEAD as well as the supported application methods.
- **The tool schema now has one owner.** `@Tool` and `@P` on `CustomerOrderTools` define it. A stub-server test captures the declarations generated by LangChain4j into `target/evidence/langchain4j-tools.json`. No runtime JSON schema resource or custom dispatcher remains.
- **Argument conversion changed.** LangChain4j accepts a numeric string for a `long` and ignores unknown extra properties. Missing, null, fractional, nonnumeric and out-of-range values fail before repository access. A value above JavaScript's exact-integer range remains an exact Java `long`. These are asserted behaviors, not assumptions based on the generated schema. Positive-ID and ownership checks remain in Java.
- **The framework owns invocation.** The reader AI service registers tools with immediate return; the explainer is a separate service with no tools. LangChain4j limits tool-calling rounds; an after-execution callback caps the output passed to the explainer. (An earlier per-call counter was removed as redundant for two read-only tools.) The customer-orders tool sorts orders newest first in Java, because answer quality varied with the view's unspecified array order. Stub tests exercise actual LangChain4j HTTP requests, argument correction, unknown tools, failure propagation and local-model preflight checks.
- **Currency assumptions returned.** The first LangChain4j detail run again added dollar signs, failing the unchanged price assertion. Its [trace](evidence/langchain4j-currency-assumption.json) and [test log](evidence/langchain4j-first-test-run.txt) are preserved. The item-answer instruction now gives a concrete field format without adding currency facts to the data. The test remains a narrow guard, not a complete answer verifier.

Traces are the serialized `OllamaAgent.Run` record (earlier traces also carried `implementation: LangChain4j AiServices + @Tool`). Their usage entries contain LangChain4j token counts for the fetch/explain stages (traces captured before September 23 also include Java-measured elapsed milliseconds), not the earlier native API duration fields. Neither is a performance benchmark. See the [current answer review](evidence/ollama-answer-review.md) and [test summary](evidence/test-summary.txt).

Official references: [LangChain4j tools](https://docs.langchain4j.dev/tutorials/tools/), [Ollama integration](https://docs.langchain4j.dev/integrations/language-models/ollama/), and [Spring Boot system requirements](https://docs.spring.io/spring-boot/system-requirements.html), checked September 22, 2026. Versions are pinned in Maven; Boot 4.1.1 supports Java 25. The application does not use a LangChain4j Spring starter or Spring Data.

## Running the full application in Compose

Compose now owns MySQL, Ollama, model initialization and the Spring Boot application. A multi-stage Dockerfile builds with Maven 3.9.11 / Java 25 and runs the jar as UID 10001 on Temurin 25.0.4. An opt-in `tests` service uses the build stage to run JUnit against container services. Host Java, Maven and Ollama are optional.

The network change required an application change: a container's loopback address refers to itself. `OllamaRuntime` now accepts the exact service hostname `ollama` alongside loopback, with tests retaining rejection of other origins and cloud-model metadata. Compose supplies `mysql:3306` and `ollama:11434`; the latter has no published host port. The API listens on its container interface, while its host port remains bound to loopback.

`ollama-init` checks whether the configured model exists, pulls it only when missing, and exits. Its successful completion gates the app. The model volume survives ordinary `compose down`; `down --volumes` removes both data and weights. Existing model tags are reused rather than refreshed automatically. Official [startup-order documentation](https://docs.docker.com/compose/how-tos/startup-order/) explains the health and successful-completion dependency conditions.

The Docker CPU path uses a 300-second per-request model timeout, configurable with `OLLAMA_TIMEOUT_SECONDS`; native development defaults to 120 seconds. Docker Desktop on macOS has no Ollama GPU passthrough, as documented in [Ollama's FAQ](https://docs.ollama.com/faq#how-do-i-use-ollama-with-gpu-acceleration-in-docker). This is a reproducible CPU configuration, not a GPU or latency comparison. All 20 unit tests and 33 integration cases passed inside the test container. The HTTP smoke script and non-root runtime check passed too. Captures are in [the Compose evidence](evidence/compose-2026-09-22/test-summary.txt). Manual review found a wrong open-order conclusion in the history answer despite correct field values; see [the review](evidence/compose-2026-09-22/answer-review.md). The initial native-model investigation and its earlier failures remain separate evidence.

## Deliberately unmeasured

No production throughput/latency comparison, memory or token-count benchmark, fault injection, replication/failover test, complete ORM implementation, auto-increment coverage, broad LLM evaluation or adversarial prompt-injection evaluation was performed. The JPA/Hibernate discussion is an architectural comparison. Large customer histories, composite-key migrations and independent concurrent changes to different lines deserve application-specific evaluation. The mock sorts all returned summaries and then takes five; the real agent stops oversized tool results after reading them. Neither implements database-side pagination.
