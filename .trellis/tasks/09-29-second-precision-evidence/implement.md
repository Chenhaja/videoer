# 秒级视频证据定位实施计划

## Scope Boundary

Expected product code changes are confined to the backend evidence pipeline plus small frontend compatibility handling:

- Backend DTOs/services/utils/tests for ASR timestamp parsing, precision-index persistence, Evidence enrichment, verification, Agent prompt contract, and evaluation.
- Frontend result/demo normalization only if `timestampPrecision` needs display or old data fallback.
- No change to upload byte chunks, 60-second FFmpeg segmentation, five-minute semantic chunking, or public API envelope.

## Ordered Steps

### 1. Establish contracts and fixtures

- Add DTOs/enums for `timestampPrecision` and internal `PrecisionEvidence`.
- Extend `AnalysisResult.Evidence` with `anchorText` and a backward-compatible precision field/default.
- Update raw Agent JSON instructions and Critic instructions so `timestampMs` is a window start and `anchorText` is copied verbatim.
- Add JSON fixtures for ASR sentence spans, OCR frame spans, old results without new fields, and enriched results.

Validation: DTO constructor tests and Jackson round-trip tests prove old and new payloads deserialize as intended.

### 2. Preserve precise extraction beside the current context

- Extend the ASR adapter response from text-only to a normalized response containing optional sentence segments.
- In `SegmentedTranscriptionService`, add the 60-second chunk offset to provider-local ASR timestamps and emit precise evidence records when available.
- In `VideoContextService`, retain the current merged `VideoContext` shape while collecting OCR frame timestamp/text records into the sidecar index.
- Save/load the sidecar with the existing checkpoint repository pattern and copy/delete it with content-level context reuse and media cleanup.

Validation: tests cover chunk offset math, the last short chunk, missing provider timestamps, OCR frame timestamps, and checkpoint fallback.

The provider capability is optional by decision: response-shape parsing must degrade to the current text-only path when `segments` are absent or malformed.

### 3. Enrich Agent evidence before Critic

- Add an evidence precision resolver that searches only the Agent-selected 60-second window for `ASR` or `OCR` Evidence.
- Normalize matching text consistently with existing verification behavior, filter by source, and select the earliest matching ASR/OCR record.
- Return `SECOND` with the real timestamp on match; return `MINUTE` with the original window start on blank/unmatched/missing index.
- Invoke enrichment immediately after Executor output and before Critic/checkpoint persistence so Critic evaluates the final timestamp.
- Keep enrichment idempotent and preserve `content`, `claim`, and source semantics.

Validation: unit tests cover ASR match, OCR match, `ASR+OCR` minute-level compatibility, source mismatch, duplicate text, empty anchor, no index, out-of-window anchor, and repeated enrichment.

Keep `ASR+OCR` as one Evidence with one window-level `timestampMs`; do not introduce `timestamps[]`, split output rows, or add cross-source precision matching.

### 4. Tighten verification, retry, and evaluation

- Update `EvidenceVerificationService` to verify `SECOND` evidence against the precision index and keep minute-level compatibility for `MINUTE`/legacy results.
- Ensure `AgentLoopService` required timestamp refresh uses the enriched timestamp without losing the original window fallback.
- Update `AgentEvaluationService` with a precise-rate metric only if it can be defined without changing existing metric meanings.
- Ensure `VideoEvidenceRetrievalService` and user evidence search can return precise hit times when the hit has a matching anchor; do not replace the five-minute retrieval stage.

Validation: existing evidence, AgentLoop, and evaluation tests remain green; new tests prove false precision is rejected.

### 5. Frontend compatibility

- Keep Markdown `[mm:ss]` rendering and `#video-t=<seconds>` seeking unchanged.
- If `timestampPrecision` is displayed, treat absent values as `MINUTE`/legacy and avoid changing the response rendering path for old results.
- Update demo fixtures only if the new field is required by the UI.

Validation: `client/npm test` and, with dependencies installed, `client/npm run build`.

### 6. Full quality check

- Read backend and frontend spec quality sections before the final pass.
- Run `server/./mvnw test`.
- Run `client/npm test` and `client/npm run build`.
- Search for stale prompt/schema references that still describe evidence as minute-only.
- Check checkpoint cleanup, content-context reuse, and old payload compatibility.

## Review Gates

- Do not start implementation until `prd.md`, `design.md`, and this plan are approved.
- Do not make the Agent output a list of every atom; `anchorText` remains the only new model-facing locator.
- Do not claim `SECOND` unless the server matched a real ASR/OCR timestamp.
- If implementation requires changing the public `VideoContext` JSON shape or adding a database table, return to planning and update `design.md` first.
- If checkpoint sidecar payload measurements exceed the accepted operational limit, return to planning before introducing a dedicated evidence table.

## Rollback Points

- If ASR provider timestamp support is unavailable, ship the sidecar/OCR path with ASR minute fallback.
- If precision checkpoint payload size is too large, stop before schema changes and revise persistence design.
- If Critic behavior regresses, disable enrichment at the AgentLoop boundary while preserving the raw `anchorText` field and minute fallback.
