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


## Configurable recognition vocabulary

Settings → 语音输入 → 识别词表 stores a device-wide vocabulary in App preferences.
The former server vocabulary is the editable App default. Save changes before
starting the next recording; saving an empty value disables vocabulary hints.
Commas, enumeration commas and line breaks can separate terms. The input is
limited to 395 characters; oversized edits cannot be saved.

At recording start the App snapshots the saved vocabulary and draft. The existing
`context` request field contains `固定词表：<terms>` on its first line, followed by
an optional newline and the tail of the draft. Multiline terms are joined with
`、`; vocabulary has priority within the 400-character request limit. Empty
vocabulary leaves only the bounded draft. Both direct VPS speech and legacy
node-forwarded speech carry this field unchanged.

The ASR worker validates the supplied context without injecting defaults. Each
stream retains its own context for partial recognition, final recognition and
prompt-echo filtering; one request cannot replace another stream's vocabulary.
Echo filtering uses only the labeled first-line vocabulary and preserves a
single configured word when it is spoken without the label.

Deploy the updated ASR adapter (`scripts/asr/mac_asr.py`) together with the App to
remove the old server defaults. Updating the App alone cannot disable a previous
ASR worker's hardcoded vocabulary. Older Apps remain protocol-compatible but do
not send vocabulary hints once the server default is removed. The recognition
model and its dependencies do not change.
