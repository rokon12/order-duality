# MRS evidence — November 7, 2026

The acceptance run used a clean clone of the code at tag `foojay-mrs-article`, a fresh `order-mrs-article` Compose project and volume, HTTPS port 8445, and SQL port 3312. It ran on Java 25 (Oracle GraalVM 25.0.2) against the digest-pinned MySQL 26.10.0 Community image in `compose.mrs.yml`. That digest is a multi-arch index; the run used ARM64.

| Evidence | What was actually run |
|---|---|
| `fresh-bootstrap.txt` | `scripts/mrs-up.sh` against the empty volume. |
| `fresh-java-tests.txt` | 52 Java unit tests plus 10 real HTTPS/MRS integration tests and the Spotless check. No skipped tests. |
| `fresh-boundary-tests.txt`, `boundary-results.json` | 10 real REST/SQL checks: auth, role separation, forged identity, cross-customer access, native view compatibility, generic-write denial, retry, expiry, stale state, and concurrent operations. |
| `provider-grants.txt` | `SHOW GRANTS` for the MRS data-provider role on that deployment. `mrs/workflow.sql` grants the column reads; the other grants appear when the MRS definitions are applied. |
| `forged-identity-example.json` | Bob's token sends Alice's principal in the body of `/whoami`; MRS returns Bob's principal. |
| `agent-read.txt` | Real `llama3.1:8b` output, with its prompt. The items and status match order 1001. |
| `agent-request-no-reason.txt` | Real model-created pending request, with its prompt. No reason was given and `Not stated` was stored, but the answer calls the `PROCESSING` order `PENDING`. |
| `demo-session.txt` | Real terminal session against the packaged JAR. Its answer promises updates the CLI cannot send. |
| `demo-readback.txt` | Direct MySQL readback after those runs: order 1003 is `CANCELLED` with one `REQUESTED` and one `CANCELLED` event; order 1001 is unchanged with its request still `PENDING`. |
| `token-renewal.txt` | A session that waited out the real 15-minute token lifetime on the local demo stack, then used both clients. |
| `invented-reason.txt` | An earlier interactive session in which the model supplied a reason the customer never gave. It predates the prompt and tool-description change. |
| `agent-read-earlier.txt` | A November 4 read answer from an earlier build. Its items and status are right, but it adds dollar signs to prices that have no currency code. |

An `expect` script drove `demo-session.txt` and `token-renewal.txt` through a pseudo-terminal, so the approval was scripted input rather than a person reading the screen. The model never had the confirmation tool or credential. All data is synthetic. Terminal captures omit the temporary `Thinking...` line, which the CLI overwrites.

`confirmation-memory-review.txt`, `picocli-refactor-review.txt`, `java-cli-commit-review.txt`, and `article-review.txt` are dated records from earlier working states, and their commit IDs predate a rewrite of this branch's author email; `spike-definition.sql` and `spike-results.jsonl` are historical, exploratory endpoint configuration. Do not use the spike SQL as bootstrap.

No throughput benchmark, production deployment, or broad security certification is claimed.
