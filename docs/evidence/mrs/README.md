# MRS evidence — November 4, 2026

The initial spike used the `order-duality-mrs` Compose project on port 8443. The clean acceptance run used a fresh `order-mrs-acceptance` volume, HTTPS port 8444, and SQL port 3311. Both used the digest-pinned MySQL 26.10.0 Community ARM64 image in `compose.mrs.yml`.

| Evidence | What was actually run |
|---|---|
| `spike-definition.sql`, `spike-results.jsonl` | Historical, exploratory endpoint configuration and direct HTTPS probes. The diagnostic identity endpoint and root-owned routine shown here were removed from the final deployment. Do not use this SQL as bootstrap. |
| `fresh-bootstrap.txt` | `scripts/mrs-up.sh` against an empty, separately named Docker volume. |
| `fresh-boundary-tests.txt`, `boundary-results.json` | 10 real REST/SQL checks: auth, role separation, forged identity, cross-customer access, native view compatibility, generic-write denial, retry, expiry, stale state, and concurrent operations. |
| `fresh-java-tests.txt` | 6 Java tool-dispatch tests plus 5 real HTTPS/MRS integration tests. No skipped tests. |
| `agent-read.txt` | Actual installed `llama3.1:latest` output. Items and status match order 1001, but the model invented dollar signs. |
| `agent-request.txt` | Actual model-created pending request for order 1003. The model paraphrased the user's reason as “Ordered by mistake.” |
| `demo-readback.txt` | Direct MySQL verification after exercising the interactive Java review/confirmation path: order 1003 is CANCELLED and has exactly one REQUESTED and one CANCELLED audit event. |

The interactive terminal sequence was:

```text
/confirm 246b1e38-d8ab-11f1-8ef1-be545d8d28fc
Review: {"requestId":"246b1e38-d8ab-11f1-8ef1-be545d8d28fc","orderId":1003,"reason":"Ordered by mistake","state":"PENDING"}
Type CONFIRM 246b1e38-d8ab-11f1-8ef1-be545d8d28fc to cancel this order:
CONFIRM 246b1e38-d8ab-11f1-8ef1-be545d8d28fc
{"ok":true,"state":"CONFIRMED","orderId":1003,"requestId":"246b1e38-d8ab-11f1-8ef1-be545d8d28fc"}
```

This is an agent-initiated proposal followed by a separately exercised human-input path. The test operator supplied confirmation; the model never had the confirmation tool or credential. All data is synthetic.

The original application's 20 unit tests also passed before implementation. Its JDBC/MySQL 9.7 integration suite was not rerun: the new module and deployment are separate and the original Java/SQL files were unchanged. No throughput benchmark, production deployment, or broad security certification is claimed.
