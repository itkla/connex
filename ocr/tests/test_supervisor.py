import io
import http.client
import os
import unittest
from unittest.mock import Mock, patch

from ocr_service.supervisor import HealthState, WorkerResult, _health, _next_backoff, _number, _ready, main, supervise


class FakeProcess:
    def __init__(self, exit_code: int | None = None, wait_code: int = 0) -> None:
        self.exit_code = exit_code
        self.wait_code = wait_code
        self.terminated = False
        self.killed = False

    def poll(self) -> int | None:
        return self.exit_code

    def wait(self, timeout: float | None = None) -> int:
        if timeout is not None and self.exit_code is None:
            self.exit_code = self.wait_code
        return self.wait_code

    def terminate(self) -> None:
        self.terminated = True

    def kill(self) -> None:
        self.killed = True


class HealthResponse(io.BytesIO):
    status = 200

    def __enter__(self) -> "HealthResponse":
        return self

    def __exit__(self, exc_type: object, exc_value: object, traceback: object) -> None:
        self.close()


class SupervisorTest(unittest.TestCase):
    def test_main_rejects_invalid_configuration(self) -> None:
        with (
            patch.dict(os.environ, {"CONNEX_OCR_PORT": "invalid"}),
            patch("ocr_service.supervisor.signal.signal"),
        ):
            self.assertEqual(1, main())

    def test_main_exits_after_worker_fails_before_readiness(self) -> None:
        process = Mock()
        process.poll.return_value = 3
        with (
            patch.dict(os.environ, {}, clear=True),
            patch("ocr_service.supervisor.signal.signal"),
            patch("ocr_service.supervisor.subprocess.Popen", return_value=process),
            patch(
                "ocr_service.supervisor.supervise",
                return_value=WorkerResult(False, 3, 0.0),
            ),
        ):
            self.assertEqual(3, main())

    def test_main_restarts_only_after_worker_reached_readiness(self) -> None:
        process = Mock()
        process.poll.return_value = 1
        with (
            patch.dict(os.environ, {}, clear=True),
            patch("ocr_service.supervisor.signal.signal"),
            patch("ocr_service.supervisor.subprocess.Popen", return_value=process) as popen,
            patch(
                "ocr_service.supervisor.supervise",
                side_effect=[
                    WorkerResult(True, 1, 31.0),
                    WorkerResult(False, 1, 0.0),
                ],
            ),
            patch("ocr_service.supervisor.threading.Event.wait", return_value=False),
        ):
            self.assertEqual(1, main())
            self.assertEqual(2, popen.call_count)

    def test_returns_worker_exit_after_readiness(self) -> None:
        process = FakeProcess(wait_code=7)
        calls = 0

        def health() -> HealthState:
            nonlocal calls
            calls += 1
            if calls > 1:
                process.exit_code = 7
            return HealthState(True, False, None)

        result = supervise(process, 30, 12, health, pause=lambda seconds: None)

        self.assertTrue(result.became_ready)
        self.assertEqual(7, result.exit_code)
        self.assertFalse(process.terminated)

    def test_stops_restart_loop_after_early_worker_failure(self) -> None:
        process = FakeProcess(exit_code=3)

        result = supervise(process, 30, 12, lambda: HealthState(False, False, None))

        self.assertFalse(result.became_ready)
        self.assertEqual(3, result.exit_code)

    def test_terminates_worker_that_never_becomes_ready(self) -> None:
        process = FakeProcess()
        ticks = iter([0.0, 1.1])

        result = supervise(
            process,
            1,
            12,
            lambda: HealthState(False, False, None),
            monotonic=lambda: next(ticks),
            pause=lambda seconds: None,
        )

        self.assertFalse(result.became_ready)
        self.assertEqual(0, result.exit_code)
        self.assertFalse(process.terminated)
        self.assertTrue(process.killed)

    def test_rejects_readiness_observed_after_startup_deadline(self) -> None:
        process = FakeProcess()
        ticks = iter([0.0, 1.1])

        result = supervise(
            process,
            1,
            12,
            lambda: HealthState(True, False, None),
            monotonic=lambda: next(ticks),
            pause=lambda seconds: None,
        )

        self.assertFalse(result.became_ready)
        self.assertTrue(process.killed)

    def test_terminates_active_inference_after_hard_deadline(self) -> None:
        process = FakeProcess()
        ticks = iter([0.0, 0.0, 1.1])

        result = supervise(
            process,
            30,
            1,
            lambda: HealthState(True, True, 1),
            monotonic=lambda: next(ticks),
            pause=lambda seconds: None,
        )

        self.assertTrue(result.became_ready)
        self.assertEqual(1, result.exit_code)
        self.assertTrue(process.killed)

    def test_terminates_unresponsive_worker_after_readiness(self) -> None:
        process = FakeProcess()
        states = iter([HealthState(True, False, None), None, None])
        ticks = iter([0.0, 0.0, 0.1, 1.2])

        result = supervise(
            process,
            30,
            1,
            lambda: next(states),
            monotonic=lambda: next(ticks),
            pause=lambda seconds: None,
        )

        self.assertTrue(result.became_ready)
        self.assertEqual(1, result.exit_code)
        self.assertTrue(process.killed)

    def test_failed_probe_does_not_reset_active_inference_deadline(self) -> None:
        process = FakeProcess()
        states = iter([
            HealthState(True, True, 1),
            None,
            HealthState(True, True, 1),
            HealthState(True, True, 1),
        ])
        ticks = iter([0.0, 0.0, 0.4, 0.8, 1.1])

        result = supervise(
            process,
            30,
            1,
            lambda: next(states),
            monotonic=lambda: next(ticks),
            pause=lambda seconds: None,
        )

        self.assertEqual(1, result.exit_code)
        self.assertTrue(process.killed)

    def test_readiness_loss_preserves_prior_stable_uptime(self) -> None:
        process = FakeProcess()
        calls = 0
        ticks = iter([0.0, 0.0, 31.0])

        def health() -> HealthState | None:
            nonlocal calls
            calls += 1
            if calls == 2:
                process.exit_code = 1
                return None
            return HealthState(True, False, None)

        result = supervise(
            process,
            30,
            60,
            health,
            monotonic=lambda: next(ticks),
            pause=lambda seconds: None,
        )

        self.assertEqual(31.0, result.ready_uptime_seconds)
        self.assertEqual(1.0, _next_backoff(8.0, result))

    def test_resets_deadline_for_back_to_back_inference_generations(self) -> None:
        process = FakeProcess()
        states = iter([
            HealthState(True, True, 1),
            HealthState(True, True, 1),
            HealthState(True, True, 2),
            HealthState(True, True, 2),
            HealthState(True, False, None),
        ])
        ticks = iter([0.0, 0.0, 0.8, 1.1, 1.9, 2.0])
        calls = 0

        def health() -> HealthState:
            nonlocal calls
            calls += 1
            state = next(states)
            if calls == 5:
                process.exit_code = 0
            return state

        result = supervise(
            process,
            30,
            1,
            health,
            monotonic=lambda: next(ticks),
            pause=lambda seconds: None,
        )

        self.assertEqual(0, result.exit_code)
        self.assertFalse(process.killed)

    def test_health_probe_requires_true_readiness(self) -> None:
        with patch(
            "ocr_service.supervisor.urllib.request.urlopen",
            return_value=HealthResponse(b'{"ready":false}'),
        ):
            self.assertFalse(_ready("http://127.0.0.1:8090/health"))

        with patch(
            "ocr_service.supervisor.urllib.request.urlopen",
            return_value=HealthResponse(b'{"ready":true}'),
        ):
            self.assertTrue(_ready("http://127.0.0.1:8090/health"))

    def test_health_probe_reads_active_inference_state(self) -> None:
        with patch(
            "ocr_service.supervisor.urllib.request.urlopen",
            return_value=HealthResponse(b'{"ready":true,"active":true,"generation":7}'),
        ):
            self.assertEqual(
                HealthState(True, True, 7),
                _health("http://127.0.0.1:8090/health"),
            )

    def test_health_probe_rejects_active_state_without_generation(self) -> None:
        with patch(
            "ocr_service.supervisor.urllib.request.urlopen",
            return_value=HealthResponse(b'{"ready":true,"active":true}'),
        ):
            self.assertIsNone(_health("http://127.0.0.1:8090/health"))

    def test_health_probe_treats_protocol_failures_as_unhealthy(self) -> None:
        with patch(
            "ocr_service.supervisor.urllib.request.urlopen",
            side_effect=http.client.IncompleteRead(b"{"),
        ):
            self.assertIsNone(_health("http://127.0.0.1:8090/health"))

    def test_numeric_configuration_rejects_non_finite_values(self) -> None:
        for raw in ("nan", "inf", "-inf"):
            with self.subTest(raw=raw), patch.dict(
                os.environ,
                {"CONNEX_OCR_STARTUP_TIMEOUT_SECONDS": raw},
            ):
                with self.assertRaisesRegex(ValueError, "must be between"):
                    _number("CONNEX_OCR_STARTUP_TIMEOUT_SECONDS", 180.0, 10.0, 600.0)

    def test_restart_backoff_resets_only_after_stable_readiness(self) -> None:
        self.assertEqual(8.0, _next_backoff(4.0, WorkerResult(True, 1, 29.9)))
        self.assertEqual(1.0, _next_backoff(8.0, WorkerResult(True, 1, 30.0)))


if __name__ == "__main__":
    unittest.main()
