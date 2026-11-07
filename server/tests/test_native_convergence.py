import threading
import unittest

from rivo_relay.codex.native_convergence import (
    NativeHistoryConverger,
    NativeJournalWatermark,
    NativeProjectionWatermark,
)


class NativeHistoryConvergerTests(unittest.TestCase):
    def test_ready_projection_does_not_reproject(self):
        reprojections = []
        converger = NativeHistoryConverger(
            journal_watermark=lambda _thread: NativeJournalWatermark("turn-2", "completed"),
            projection_watermark=lambda _thread: NativeProjectionWatermark(
                "turn-2", "completed"
            ),
            projection_contains=lambda _thread, turn: turn == "turn-2",
            has_live_writer=lambda _thread: False,
            reproject_projection=lambda thread: reprojections.append(thread) or True,
            clock=lambda: 100,
        )

        result = converger.ensure_consistent("thread-1", expected_turn_id="turn-2")

        self.assertTrue(result.ready)
        self.assertFalse(result.reprojected)
        self.assertEqual([], reprojections)

    def test_idle_lagging_projection_reprojects_once_and_advances_generation(self):
        state = {"projected": "turn-1", "reprojections": 0}

        def reproject(_thread):
            state["reprojections"] += 1
            state["projected"] = "turn-2"
            return True

        converger = NativeHistoryConverger(
            journal_watermark=lambda _thread: NativeJournalWatermark("turn-2", "completed"),
            projection_watermark=lambda _thread: NativeProjectionWatermark(
                state["projected"], "completed"
            ),
            projection_contains=lambda _thread, turn: turn == state["projected"],
            has_live_writer=lambda _thread: False,
            reproject_projection=reproject,
            clock=lambda: 200,
        )

        first = converger.ensure_consistent("thread-1", expected_turn_id="turn-2")
        second = converger.ensure_consistent("thread-1", expected_turn_id="turn-2")

        self.assertTrue(first.ready)
        self.assertTrue(first.reprojected)
        self.assertEqual(1, first.generation)
        self.assertEqual(1, second.generation)
        self.assertEqual(1, state["reprojections"])

    def test_live_writer_never_reprojects_lagging_projection(self):
        reprojections = []
        converger = NativeHistoryConverger(
            journal_watermark=lambda _thread: NativeJournalWatermark("turn-2", "inProgress"),
            projection_watermark=lambda _thread: NativeProjectionWatermark(
                "turn-1", "completed"
            ),
            projection_contains=lambda _thread, _turn: False,
            has_live_writer=lambda _thread: True,
            reproject_projection=lambda thread: reprojections.append(thread) or True,
        )

        result = converger.ensure_consistent("thread-1", expected_turn_id="turn-2")

        self.assertEqual("converging", result.state)
        self.assertEqual([], reprojections)

    def test_terminal_status_mismatch_reprojects_even_when_turn_id_matches(self):
        state = {"status": "interrupted", "reprojections": 0}

        def reproject(_thread):
            state["reprojections"] += 1
            state["status"] = "completed"
            return True

        converger = NativeHistoryConverger(
            journal_watermark=lambda _thread: NativeJournalWatermark("turn-2", "completed"),
            projection_watermark=lambda _thread: NativeProjectionWatermark(
                "turn-2", state["status"]
            ),
            projection_contains=lambda _thread, turn: turn == "turn-2",
            has_live_writer=lambda _thread: False,
            reproject_projection=reproject,
        )

        result = converger.ensure_consistent("thread-1", expected_turn_id="turn-2")

        self.assertTrue(result.ready)
        self.assertEqual(1, state["reprojections"])

    def test_concurrent_requests_share_one_thread_reprojection(self):
        state = {"projected": "turn-1", "reprojections": 0}
        release = threading.Event()

        def reproject(_thread):
            state["reprojections"] += 1
            release.wait(1)
            state["projected"] = "turn-2"
            return True

        converger = NativeHistoryConverger(
            journal_watermark=lambda _thread: NativeJournalWatermark("turn-2", "completed"),
            projection_watermark=lambda _thread: NativeProjectionWatermark(
                state["projected"], "completed"
            ),
            projection_contains=lambda _thread, turn: turn == state["projected"],
            has_live_writer=lambda _thread: False,
            reproject_projection=reproject,
        )
        results = []
        workers = [
            threading.Thread(
                target=lambda: results.append(
                    converger.ensure_consistent("thread-1", expected_turn_id="turn-2")
                )
            )
            for _ in range(2)
        ]
        for worker in workers:
            worker.start()
        release.set()
        for worker in workers:
            worker.join(2)

        self.assertEqual(1, state["reprojections"])
        self.assertEqual([True, True], sorted(result.ready for result in results))


if __name__ == "__main__":
    unittest.main()
