# Foojay submission review — November 7, 2026

The local article is ready for submission after the corrections below. This review covers the merged implementation and publicly accessible references; it does not publish or submit the article.

## Revisions reviewed

- Merged `main`: `15882fc6db7ba01cbc6a2ea97e05a3310871c5dd`.
- Published tag: `foojay-mrs-article`, tag object `383318678abbb757a8de5bcd668ef4127799aa78`, pointing to commit `2b5f4bbc62d2b9f6a77adfa081a9bc5ccf5f7736`.
- The implementation, scripts, Compose configuration, and acceptance evidence at the published tag match `main`. The article's links retain that tested public revision.
- The local tag was found pointing to `3b4b64015d806d0376f00f6dc463417c5a028724`, unlike the published tag. Verification therefore used an independent fetch into a temporary bare repository. The local tag was left unchanged. This difference explains the initial guide-content mismatch; it is not a broken public link or an implementation difference.

## Corrections made locally

1. **Provider privileges:** explicitly state that the captured MRS data-provider grants include table-level `INSERT`, `UPDATE`, and `DELETE` on `orders` and `customer_principals`. The narrower REST surface must not be confused with a minimally privileged provider SQL account. The routine owner's narrower grants are a separate fact.
2. **Authentication:** scope the 15-minute bearer lifetime to the tested deployment; describe re-reading environment credentials without suggesting the Java process contains no password; limit the special restart path to a renewed sign-in rejected with HTTP 401, as implemented.
3. **Memory and retries:** describe four messages as the simple one-tool case, not a fixed cost per turn. Clarify that the 20-message window is not a token budget, and restoring memory does not undo API operations that already succeeded. Direct readers to `/requests` before retrying a failed cancellation turn.
4. **Tool semantics:** a ninth dispatched call cannot reach the order service; a completed tool call can still return an `ok:false` business denial. Scope the LangChain4j template behavior and error-handler defaults to the tested version and synchronous service.
5. **Model limitations:** distinguish unsupported promises of later status updates from the real SQL paths that reject expired, stale, or ineligible requests. The previous wording that the CLI had “no denial path” was too broad.
6. **Reproduction:** explicitly export `OLLAMA_MODEL=llama3.1:8b` after pulling the model so an existing environment override cannot silently select a different model.

The companion guide received matching provider-privilege and authentication clarifications. Runtime source, SQL, test code, and historical acceptance captures were not changed.

## Technical and evidence checks

- Five Java excerpts match the main implementation: four directly after whitespace normalization, and one as two ordered statements with intervening bookkeeping omitted. The article already labels its excerpts as trimmed.
- Three working SQL excerpts match `mrs/workflow.sql` and `mrs/endpoints.sql`. The deliberately unsupported wrapper and error 6504 agree with the compatibility probe and captured boundary result.
- All four shell blocks pass `bash -n`; the JSON example parses successfully.
- The cancellation transcript is a verbatim excerpt of `demo-session.txt`. Its displayed UUID, reason, expiry, and outcome agree with the capture. The shortened database readback preserves the order status, version, request state, reason, and audit counts from `demo-readback.txt`.
- The authenticated-principal example matches `forged-identity-example.json`. Endpoint methods, role inheritance, ownership injection, HTTP/business-denial distinction, cancellation expiry, lock ordering, version checks, replay behavior, and executor trust match the implementation.
- The token-renewal evidence explicitly records a 930-second wait. The article preserves the disclosure that terminal approval was scripted through `expect`.
- Recorded results remain **52 Java unit tests, 10 live Java/MRS integration tests, and 10 REST/SQL boundary tests**, with no failures or skipped Java tests. Counts and assertions were checked against the published captures and corresponding test source. These runtime suites and the real-model runs were **not rerun during this documentation review**.
- The installed MySQL Shell reports version 26.10.0. Its MRS SDK directory contains `python`, `swift`, and `typescript`, supporting the scoped SDK-language statement.
- Both PNG diagrams were visually inspected and agree with their Mermaid sources and Java/SQL control flow. Pandoc conversion preserves three tables, two embedded PNGs with alt text, and eighteen code/output blocks; heading IDs are unique. `git diff --check` passes.

## Public link checks

All **22 unique GitHub URLs** passed unauthenticated HTTP checks: nineteen file links, two directory links, and the clone URL. Raw file contents were compared with the independently fetched published tag and with merged `main`; all nineteen match those published revisions. The per-URL results are in [submission-link-check.json](submission-link-check.json).

The seven non-GitHub references also opened successfully: the earlier Foojay article, Oracle's component setup guide, both MRS reference pages, and the LangChain4j tools, chat-memory, and human-in-the-loop documentation. Their relevant sections support the claims or explicitly identified alternative design. The component guide confirms MySQL Server and Shell 26.10.0 for the in-server deployment.

## Submission handoff

Use the corrected local `docs/foojay-mrs-agent-article.md`, with both `docs/mrs-architecture.png` and `docs/mrs-cancellation-sequence.png`. Upload those figures and retain their alt text when importing the Markdown into Foojay. Actual Foojay editor layout remains a submission-stage check; the older private Page copy was not updated.

The corrections and this review record are uncommitted. No branch or tag was moved, and nothing was pushed or submitted during this review.
