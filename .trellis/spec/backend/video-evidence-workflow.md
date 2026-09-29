# Video Evidence Workflow

## Ownership and Time Units

The video-analysis path deliberately uses different time windows for different jobs. Do not collapse them into one DTO field or change only one consumer.

| Unit | Owner | Current purpose |
|---|---|---|
| 60-second audio window | `SegmentedTranscriptionService` | Bound each FFmpeg/ASR request and build the initial transcript ranges |
| Scene-change or 30-second fallback frame | `VideoContextService` | Preserve the actual presentation timestamp for OCR evidence |
| 60-second `VideoSegment` | `VideoContextService.merge` | Give the Agent a bounded multimodal context window |
| 5-minute `VideoChunk` | `VideoChunkingService` | Summarize/embed long-video candidates for retrieval |
| `AnalysisResult.Evidence.timestampMs` | `DeepSeekUtils`, `EvidenceVerificationService` | Identify a user-visible, verifiable point in the original video |

`SegmentedTranscriptionService` runs FFmpeg with `-segment_time 60` and `-reset_timestamps 1`; this is an ASR transport boundary, not a guarantee that all downstream evidence must stay minute-granular. `VideoContextService` already parses `showinfo` PTS values for frame timestamps, so frame array position must never be treated as the source of truth.

References: `server/src/main/java/com/example/server/service/SegmentedTranscriptionService.java`, `server/src/main/java/com/example/server/service/VideoContextService.java`, and `server/src/main/java/com/example/server/dto/VideoContext.java`.

## Pipeline Contract

The evidence-bearing data path is:

```text
FFmpeg/ASR + FFmpeg/OCR
  -> TranscriptSegment / FramePart
  -> VideoContext.VideoSegment
  -> VideoChunk and Qdrant candidate scores
  -> LongVideoContextService selected context
  -> Planner / Executor / Critic
  -> AnalysisResult.Evidence
  -> EvidenceVerificationService
  -> checkpoint + Markdown timestamp link + video seek
```

When changing timestamp precision, response fields, or evidence matching, inspect every edge above. In particular, `AgentCheckpointService` serializes `VideoContext`, `VideoChunk`, and `AnalysisResult` through `AgentCheckpointRepository`; a DTO change must remain readable from old checkpoints or explicitly invalidate/rebuild them.

The frontend consumes the final timestamp through the Markdown `#video-t=<seconds>` link contract in `client/src/markdown.js` and seeks in `client/src/App.vue`. Keep backend timestamps in milliseconds until that rendering boundary.

## Retrieval and Agent Boundaries

`VideoEvidenceRetrievalService` first selects 5-minute chunks using vector and keyword scores, then scores their raw `VideoSegment` values by transcript and OCR terms. `LongVideoContextService` applies its character budget only after retrieval and keeps segments ordered by `startMs`.

The 5-minute summary is a retrieval aid. The Agent receives selected source segments, and any final conclusion must bind an `AnalysisResult.Evidence` record whose `claim` exactly matches a conclusion. `DeepSeekUtils.execute` supplies this contract to the model; `EvidenceVerificationService` verifies the timestamp is covered and that `content` occurs in the ASR/OCR source for that time range.

Do not make a controller, the UI, or an LLM-generated value the authority for a timestamp. Precision or fallback resolution belongs in the service layer that owns the original transcript/frame index, before `EvidenceVerificationService` and checkpoint persistence run.

References: `server/src/main/java/com/example/server/service/VideoEvidenceRetrievalService.java`, `server/src/main/java/com/example/server/service/LongVideoContextService.java`, `server/src/main/java/com/example/server/service/AgentLoopService.java`, `server/src/main/java/com/example/server/service/EvidenceVerificationService.java`, and `server/src/main/java/com/example/server/utils/DeepSeekUtils.java`.

## Failure and Compatibility Rules

- ASR and OCR are independent branches. `VideoContextService.finishContext` permits one successful branch, but fails only when both branches fail.
- Vector-store and embedding failures are degradations: `VideoEvidenceRetrievalService` and `VideoChunkingService` record telemetry and keep local keyword/vector fallback paths alive.
- Keep timestamp ranges valid: records such as `TranscriptSegment` and `VideoContext.VideoSegment` reject negative starts and non-positive ranges.
- When provider output lacks a finer-grained timestamp, retain the existing 60-second range as an explicit fallback. Do not fabricate a second-level value from text position.
- Any change to a checkpointed record requires tests for round-trip serialization and old/null field normalization; follow `AgentCheckpointServiceTest`.

## Review Checklist

- Is the timestamp sourced from the ASR/OCR output rather than the slice index or UI position?
- Does every changed field survive context creation, retrieval, Agent validation, checkpointing, and result rendering?
- Does degraded ASR, OCR, embedding, or vector-store behavior still match the current fallback policy?
- Do tests cover the start/end boundary and a missing-precision fallback?

## Precision Evidence Contract

### 1. Scope / Trigger

This contract applies when changing ASR/OCR timestamps, Agent evidence output,
checkpointed video context, or user-facing evidence search. It preserves the
60-second transport/context window while allowing a server-owned ASR sentence
or OCR keyframe to refine a final timestamp.

### 2. Signatures

- `SegmentedTranscriptionService.transcribeWithEvidence(...)` returns the existing window transcripts plus optional `PrecisionEvidence` records.
- `VideoContextService.buildWithPrecision(...)` returns `ContextBuildResult(VideoContext, PrecisionEvidenceIndex)`.
- `AgentCheckpointService.loadPrecisionEvidence(mediaId)` and `savePrecisionEvidence(mediaId, index)` use the media checkpoint and Redis cache path.
- `EvidencePrecisionService.enrich(context, index, result)` resolves Agent `anchorText` before Critic and result persistence.

### 3. Contracts

- `AnalysisResult.Evidence` retains `timestampMs`, `source`, `content`, and `claim`, and adds optional `anchorText` and server-owned `timestampPrecision`.
- `timestampMs` from the Agent is a 60-second `VideoSegment.startMs`, not a guessed sentence time.
- `SECOND` is valid only when `source` is exactly `ASR` or `OCR` and `anchorText` matches a real sidecar record at the final timestamp.
- `MINUTE` uses the containing window start. Legacy payloads without the new fields normalize to empty anchor and `MINUTE`.
- `ASR+OCR` remains one window-level Evidence and is never split or refined by the standalone ASR/OCR resolver.
- `VideoEvidenceHit.timestampPrecision` is optional for clients; search hits use a sidecar timestamp only on a strict query/text match and otherwise retain the window range.

### 4. Validation & Error Matrix

| Condition | Required behavior |
|---|---|
| Provider returns valid sentence spans | Add the local 60-second offset and index global ASR start/end. |
| Provider omits or malforms spans | Keep the text-only transcript; create no ASR precision record. |
| OCR frame has a real `showinfo` PTS | Index the PTS and OCR text. |
| OCR timestamp is a fallback frame position | Keep the OCR context but do not claim `SECOND`. |
| Anchor is blank, unmatched, source-mismatched, or index is absent | Use the containing window start and `MINUTE`. |
| Existing `SECOND` payload is reprocessed | Keep it only after matching context, source, anchor, timestamp, and sidecar; otherwise re-resolve or downgrade. |
| `source=ASR+OCR` | Keep the existing single Evidence and window-level timestamp. |

### 5. Good / Base / Bad Cases

- Good: ASR anchor matches a sentence at `186400ms`; result is `SECOND` at `186400`.
- Base: OCR text or ASR text has no precise record; result remains `MINUTE` at the containing window start.
- Bad: copying a model-supplied `timestampPrecision=SECOND`, using the first sentence in a window, or converting a frame array index into a timestamp.

### 6. Tests Required

- ASR span parsing and 60-second offset: assert global `startMs`/`endMs`.
- OCR PTS versus fallback timestamp: assert only real PTS creates precision evidence.
- Evidence resolver: assert ASR/OCR match, strict normalization, source filtering, window boundaries, earliest match, mixed-source fallback, missing index, window-start fallback, idempotent verified `SECOND`, and rejection of forged `SECOND`.
- Checkpoint compatibility: assert sidecar round-trip, media cleanup, content reuse, and old `AnalysisResult` JSON defaults.
- Retrieval: assert a matching sidecar hit returns `SECOND` and a non-matching query returns the original window interval.
- Frontend: assert unknown or missing `timestampPrecision` does not change the existing `#video-t=<seconds>` seeking contract.

### 7. Wrong vs Correct

#### Wrong

```java
new AnalysisResult.Evidence(modelTimestamp, "ASR", content, claim,
        anchor, TimestampPrecision.SECOND);
```

#### Correct

```java
evidencePrecisionService.enrich(context, checkpointService.loadPrecisionEvidence(mediaId), result);
```

The service, not the model or UI, is the authority for refined timestamps.
