"""Resident macmini Qwen3-ASR worker used by the device component."""

from __future__ import annotations

import logging
import os
import queue
import unicodedata
import shutil
import tempfile
import threading
import time
import uuid
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Callable


LOG = logging.getLogger("qiguang-device.asr")
MAX_CONTEXT_CHARS = 400
SUPPORTED_SUFFIXES = {".m4a", ".mp3", ".wav", ".webm", ".ogg", ".flac"}
# User-maintained recognition vocabulary; preserve canonical spelling and case.
PROJECT_VOCABULARY_CONTEXT = "固定词表：Merge、MergeDemo、棋子、生成器、棋子链、物品链、活动、笑脸订单、订单、等级、GH、Gossip Harbor、Tasty Travels。"
STREAM_SAMPLE_RATE = 16_000
MAX_STREAMS = 4
MAX_STREAM_SECONDS = 120
STREAM_IDLE_SECONDS = 30


class AsrError(RuntimeError):
    """Base error for the local ASR worker."""


class AsrUnavailable(AsrError):
    """The resident macmini ASR model is not ready."""


class AsrInvalidResponse(AsrError):
    """The local ASR runtime returned an invalid result."""


@dataclass
class _WorkerRequest:
    action: str
    payload: tuple[Any, ...]
    result_queue: queue.Queue[Any]


@dataclass
class _Stream:
    state: Any
    samples: int
    touched_at: float
    pcm16le: bytearray


class SpeechTranscriptionService:
    """Keep one MLX Session and all GPU work on its owning worker thread."""

    def __init__(
        self,
        config: Any,
        *,
        session_factory: Callable[[str], Any] | None = None,
    ) -> None:
        self.model = str(Path(config.local_asr_model).expanduser())
        self.temp_root = Path(config.local_asr_temp).expanduser()
        self.temp_root.mkdir(parents=True, exist_ok=True)
        self.session_factory = session_factory or self._load_session
        self.requests: queue.Queue[_WorkerRequest | None] = queue.Queue()
        self.ready = threading.Event()
        self.startup_error: BaseException | None = None
        self.worker = threading.Thread(
            target=self._run,
            name="qiguang-asr-mlx",
            daemon=True,
        )
        self.worker.start()

    def transcribe(self, raw: bytes, filename: str, context: str = "") -> dict:
        context = self._context_with_project_vocabulary(context)
        result = self._request("transcribe", raw, filename, context, timeout=120)
        text = getattr(result, "text", None)
        if not isinstance(text, str):
            raise AsrInvalidResponse("macmini Qwen3-ASR 返回无效结果")
        return {
            "ok": True,
            "text": self._clean_transcript(text),
            "language": getattr(result, "language", None),
            "backend": "macmini-mlx",
        }

    def start_stream(self, context: str = "") -> dict:
        context = self._context_with_project_vocabulary(context)
        return self._request("start_stream", context, timeout=120)

    def feed_stream(self, stream_id: str, pcm16le: bytes) -> dict:
        if not pcm16le or len(pcm16le) % 2:
            raise AsrInvalidResponse("PCM 分片必须是非空的 16 位小端采样")
        return self._request("feed_stream", stream_id, pcm16le, timeout=120)

    def finish_stream(self, stream_id: str) -> dict:
        return self._request("finish_stream", stream_id, timeout=120)

    def cancel_stream(self, stream_id: str) -> dict:
        return self._request("cancel_stream", stream_id, timeout=10)

    def _request(self, action: str, *payload: Any, timeout: float) -> Any:
        if not self.ready.wait(timeout=120):
            raise AsrUnavailable("macmini Qwen3-ASR 模型加载超时")
        if self.startup_error is not None:
            raise AsrUnavailable(f"macmini Qwen3-ASR 模型加载失败：{self.startup_error}")
        result_queue: queue.Queue[Any] = queue.Queue(maxsize=1)
        self.requests.put(_WorkerRequest(action, payload, result_queue))
        try:
            result = result_queue.get(timeout=timeout)
        except queue.Empty as error:
            raise AsrUnavailable("macmini Qwen3-ASR 请求超时") from error
        if isinstance(result, AsrError):
            raise result
        if isinstance(result, BaseException):
            raise AsrUnavailable(f"macmini Qwen3-ASR 转写失败：{result}") from result
        return result

    def shutdown(self) -> None:
        if self.worker.is_alive():
            self.requests.put(None)
            self.worker.join(timeout=5)

    def health_snapshot(self) -> dict:
        """Expose model readiness so component health cannot mask a dead ASR worker."""
        if not self.ready.is_set():
            return {
                "ok": False,
                "state": "starting",
                "last_error": "语音识别模型正在加载",
            }
        if self.startup_error is not None:
            return {
                "ok": False,
                "state": "unhealthy",
                "last_error": "语音识别模型加载失败",
            }
        if not self.worker.is_alive():
            return {
                "ok": False,
                "state": "unhealthy",
                "last_error": "语音识别工作线程已停止",
            }
        return {"ok": True, "state": "healthy", "last_error": None}

    def _run(self) -> None:
        try:
            session = self.session_factory(self.model)
        except BaseException as error:  # startup errors must reach the API
            self.startup_error = error
            self.ready.set()
            LOG.exception("could not load resident macmini Qwen3-ASR")
            return
        LOG.info("resident macmini Qwen3-ASR ready: model=%s", self.model)
        self.ready.set()
        streams: dict[str, _Stream] = {}
        while True:
            request = self.requests.get()
            if request is None:
                return
            try:
                now = time.monotonic()
                streams = {
                    key: value
                    for key, value in streams.items()
                    if now - value.touched_at <= STREAM_IDLE_SECONDS
                }
                if request.action == "transcribe":
                    request.result_queue.put(self._transcribe_file(session, *request.payload))
                elif request.action == "start_stream":
                    if len(streams) >= MAX_STREAMS:
                        raise AsrUnavailable("实时语音会话已满")
                    stream_id = uuid.uuid4().hex
                    state = session.init_streaming(
                        context=request.payload[0],
                        language=None,
                        chunk_size_sec=2.0,
                        max_context_sec=30.0,
                        sample_rate=STREAM_SAMPLE_RATE,
                        finalization_mode="accuracy",
                    )
                    streams[stream_id] = _Stream(state, 0, now, bytearray())
                    request.result_queue.put(self._stream_payload(stream_id, state, False))
                elif request.action == "feed_stream":
                    stream_id, raw = request.payload
                    stream = streams.get(stream_id)
                    if stream is None:
                        raise AsrInvalidResponse("实时语音会话不存在或已过期")
                    samples = len(raw) // 2
                    if stream.samples + samples > STREAM_SAMPLE_RATE * MAX_STREAM_SECONDS:
                        streams.pop(stream_id, None)
                        raise AsrInvalidResponse("实时语音不能超过 120 秒")
                    import numpy as np

                    pcm = np.frombuffer(raw, dtype="<i2").astype(np.float32) / 32768.0
                    state = session.feed_audio(pcm, stream.state)
                    stream.pcm16le.extend(raw)
                    streams[stream_id] = _Stream(
                        state, stream.samples + samples, now, stream.pcm16le
                    )
                    request.result_queue.put(self._stream_payload(stream_id, state, False))
                elif request.action == "finish_stream":
                    stream_id = request.payload[0]
                    stream = streams.pop(stream_id, None)
                    if stream is None:
                        raise AsrInvalidResponse("实时语音会话不存在或已过期")
                    import numpy as np

                    full_pcm = (
                        np.frombuffer(stream.pcm16le, dtype="<i2").astype(np.float32)
                        / 32768.0
                    )
                    final_result = session.transcribe(
                        full_pcm,
                        context=getattr(stream.state, "context", ""),
                        language=None,
                        max_new_tokens=None,
                    )
                    final_text = getattr(final_result, "text", None)
                    if not isinstance(final_text, str):
                        raise AsrInvalidResponse("macmini Qwen3-ASR 返回无效最终结果")
                    payload = self._stream_payload(stream_id, stream.state, True)
                    payload["text"] = self._clean_transcript(final_text)
                    payload["stable_text"] = payload["text"]
                    request.result_queue.put(payload)
                elif request.action == "cancel_stream":
                    stream_id = request.payload[0]
                    streams.pop(stream_id, None)
                    request.result_queue.put({"ok": True, "stream_id": stream_id, "canceled": True})
                else:
                    raise AsrInvalidResponse("未知语音识别操作")
            except BaseException as error:
                LOG.exception("resident macmini Qwen3-ASR request failed")
                request.result_queue.put(error)

    def _transcribe_file(self, session: Any, raw: bytes, filename: str, context: str) -> Any:
        request_dir = Path(tempfile.mkdtemp(prefix="transcription-", dir=self.temp_root))
        try:
            suffix = Path(filename or "audio.m4a").suffix.lower() or ".m4a"
            if suffix not in SUPPORTED_SUFFIXES:
                raise ValueError("不支持的音频格式")
            audio_path = request_dir / f"audio{suffix}"
            audio_path.write_bytes(raw)
            return session.transcribe(
                str(audio_path), context=context, language=None, max_new_tokens=None
            )
        finally:
            shutil.rmtree(request_dir, ignore_errors=True)

    @staticmethod
    def _stream_payload(stream_id: str, state: Any, final: bool) -> dict:
        text = getattr(state, "text", None)
        stable_text = getattr(state, "stable_text", None)
        if not isinstance(text, str) or not isinstance(stable_text, str):
            raise AsrInvalidResponse("macmini Qwen3-ASR 返回无效实时结果")
        return {
            "ok": True,
            "stream_id": stream_id,
            "text": SpeechTranscriptionService._clean_transcript(text),
            "stable_text": SpeechTranscriptionService._clean_transcript(stable_text),
            "final": final,
            "backend": "macmini-mlx-streaming",
        }

    @staticmethod
    def _clean_transcript(text: str) -> str:
        """Reject prompt echoes, without deleting vocabulary from real speech."""
        def normalized(value: str) -> str:
            return "".join(c for c in unicodedata.normalize("NFKC", value).casefold()
                           if c.isalnum())
        value = normalized(text)
        vocabulary = normalized(PROJECT_VOCABULARY_CONTEXT.split("：", 1)[-1])
        # Only reject the known prompt, including its labeled streaming prefix.
        # Ordinary speech about a fixed vocabulary must remain intact.
        labeled_echo = (value.startswith("固定词表") and vocabulary
                        and vocabulary.startswith(value[len("固定词表"):]))
        if labeled_echo or (vocabulary and value == vocabulary):
            return ""
        return text

    @staticmethod
    def _load_session(model: str) -> Any:
        # The production ASR model is an explicit local path. Prevent third-party
        # helpers from resolving their own default model through Hugging Face.
        os.environ["HF_HUB_OFFLINE"] = "1"
        os.environ["TRANSFORMERS_OFFLINE"] = "1"
        from mlx_qwen3_asr import Session

        return Session(model=model)

    @staticmethod
    def _context_with_project_vocabulary(request_context: str) -> str:
        """Add product vocabulary while retaining any future caller-provided hint.

        Qwen3-ASR consumes this string as a system-prompt context rather than a
        hard decoder grammar.  The product vocabulary must remain available on
        every request, while the optional request hint is safely bounded by the
        existing API limit.
        """
        request_context = request_context.strip()
        if len(request_context) > MAX_CONTEXT_CHARS:
            raise AsrInvalidResponse("语音上下文不能超过 400 个字符")
        if not PROJECT_VOCABULARY_CONTEXT:
            return request_context
        if not request_context:
            return PROJECT_VOCABULARY_CONTEXT
        remaining = MAX_CONTEXT_CHARS - len(PROJECT_VOCABULARY_CONTEXT) - 1
        if remaining <= 0:
            return PROJECT_VOCABULARY_CONTEXT[:MAX_CONTEXT_CHARS]
        return f"{PROJECT_VOCABULARY_CONTEXT} {request_context[:remaining]}"
