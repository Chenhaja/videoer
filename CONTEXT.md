# Video Evidence Context

This glossary defines the canonical terms for the video's multimodal evidence and Agent analysis domain.

## Evidence and Time

**Video window**:
A 60-second context range used to process and present a portion of a video. It is a context boundary, not proof that the supporting statement occurred at the window start.
_Avoid_: minute evidence, atomic window

**Precision evidence**:
A server-side ASR sentence or OCR keyframe record with a real timestamp inside a video window.
_Avoid_: guessed timestamp, inferred evidence

**Anchor text**:
The first verbatim ASR sentence or OCR text selected as the direct support for an Agent claim. It is a locator for matching precision evidence, not a replacement for the full supporting content.
_Avoid_: timestamp text, evidence ID

**Timestamp precision**:
The confidence class of the final evidence time: `SECOND` means a real ASR/OCR timestamp was matched; `MINUTE` means the result uses the containing video window start as a fallback.
_Avoid_: timestamp accuracy, exact minute

## Sources

**ASR**:
Automatic speech recognition evidence from spoken content, optionally represented by sentence-level start and end times.
_Avoid_: audio evidence

**OCR**:
Text recognized from a video keyframe at the frame's video timestamp.
_Avoid_: screen text, visual transcript

**ASR+OCR**:
A single backward-compatible Evidence source that combines spoken and on-screen text from the same video window and retains one window-level timestamp field; it is not split or upgraded by the standalone ASR/OCR precision locator.
_Avoid_: multi-timestamp evidence, split evidence

**VideoContext**:
The Agent-facing multimodal context made of time-windowed transcript, OCR text, and evidence frame references. Precision evidence is stored separately and is not expanded into this prompt by default.
_Avoid_: raw atom list, transcript index
