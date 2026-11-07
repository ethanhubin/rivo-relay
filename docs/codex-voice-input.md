# Codex voice input

The composer follows QiGuang's microphone placement to the left of the text
field, with idle, recording, transcribing, unavailable and retry states. It
captures mono 16 kHz PCM16LE in half-second chunks and inserts the transcript at
the current cursor. Appending to existing text inserts the source's newline.
Partial results replace that insertion span rather than adding repeated text.

The authenticated node advertises speech capability through
`/v1/media/capabilities`; discovery is cached per `RelayApi` client. An
unconfigured or incompatible node displays a disabled microphone and a reason.
No speech model is downloaded or started automatically. The node owner supplies
`RIVO_SPEECH_BACKEND_URL` and, optionally, `RIVO_SPEECH_BACKEND_TOKEN`.

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
