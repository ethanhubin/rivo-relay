# Codex voice input

field, with idle, recording, transcribing, unavailable and retry states. It
captures mono 16 kHz PCM16LE in half-second chunks and inserts the transcript at
the current cursor. Appending to existing text inserts the source's newline.
Partial results replace that insertion span rather than adding repeated text.

The authenticated node advertises speech capability through
`/v1/media/capabilities`; discovery is cached per `RelayApi` client. An
unconfigured or incompatible node displays only a greyed-out, disabled microphone,
without a visible status message. Its reason remains available to screen readers.
No speech model is downloaded or started automatically. VPS-connected nodes discover the shared speech service through their existing
gateway credentials. The VPS chooses the ASR backend; nodes do not install models
or configure backend secrets. Standalone nodes without gateway credentials may
set `RIVO_SPEECH_BACKEND_URL` and `RIVO_SPEECH_BACKEND_TOKEN`.
See [shared ASR](shared-asr.md) for deployment and update behavior.

The recording permission dialog requires a fresh tap after permission is granted.
Leaving the screen, changing node/chat, locking the composer, or backgrounding
the app cancels capture and its stream. Manual text edits retire the transcript's
generation, so late partial/final replies cannot overwrite another draft.
Cancellation retains already inserted text. Empty final replies also retain the
recognized partial and show a retry message.

Audio buffering is limited to eight half-second chunks. A slow upload stops
capture with an error rather than silently dropping audio or growing memory.
Stopping flushes the last partial chunk, including recordings shorter than half
a second; cancelling discards that unsubmitted tail. Capture stops at 120 seconds.
Every stream operation has a 15-second deadline; cleanup has a separate bounded
cancel request. A failed audio write is never replayed.

Pressing send while recording stops capture, flushes the tail, and waits for the
final transcript. The send runs only after the final text reaches the parent
draft and the same target remains enabled. A final reply cannot send a manually
edited or switched draft. Empty/failed transcription never triggers automatic
send.

Local JVM tests cover cursor insertion, partial replacement, final delivery,
draft/target fencing, bounded backlog, timeout cleanup, stop/cancel PCM framing,
capability caching, authenticated raw PCM transport, and expired-stream errors.
These tests use fake speech transports and an isolated HTTP fixture. They do not
record the microphone or contact a real speech backend.


VPS-paired Android clients obtain a short-lived speech-only grant from the node
and send audio directly to the pinned VPS speech endpoint. Only grant issuance
uses the chat route; chunks, finish and cancel do not traverse the chat node.
See [shared ASR](shared-asr.md) for the 180-second revocation window and upgrade requirements.
