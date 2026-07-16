import http.client
import json
import math
import os
import signal
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request
from collections.abc import Callable
from dataclasses import dataclass


@dataclass(frozen=True)
class HealthState:
    ready: bool
    active: bool
    generation: int | None


@dataclass(frozen=True)
class WorkerResult:
    became_ready: bool
    exit_code: int
    ready_uptime_seconds: float


STABLE_UPTIME_SECONDS = 30.0


def main() -> int:
    stop = threading.Event()
    current_process: list[subprocess.Popen[bytes] | None] = [None]

    def forward(signum: int, frame: object) -> None:
        stop.set()
        process = current_process[0]
        if process is not None and process.poll() is None:
            process.send_signal(signum)

    signal.signal(signal.SIGTERM, forward)
    signal.signal(signal.SIGINT, forward)
    try:
        startup_timeout = _number("CONNEX_OCR_STARTUP_TIMEOUT_SECONDS", 90.0, 10.0, 600.0)
        inference_timeout = _number("CONNEX_OCR_REQUEST_TIMEOUT_SECONDS", 12.0, 1.0, 120.0)
        port = _integer("CONNEX_OCR_PORT", 8090, 1, 65_535)
    except ValueError as exception:
        print(str(exception), file=sys.stderr)
        stop.wait()
        return 0
    backoff_seconds = 1.0
    while not stop.is_set():
        try:
            process = subprocess.Popen([sys.executable, "-m", "ocr_service"])
        except OSError:
            if stop.wait(backoff_seconds):
                break
            backoff_seconds = min(backoff_seconds * 2, 60.0)
            continue
        current_process[0] = process
        result = supervise(
            process,
            startup_timeout,
            inference_timeout,
            lambda: _health(f"http://127.0.0.1:{port}/health"),
            stopping=stop.is_set,
        )
        current_process[0] = None
        if stop.is_set():
            break
        backoff_seconds = _next_backoff(backoff_seconds, result)
        stop.wait(backoff_seconds)
    return 0


def supervise(
    process: subprocess.Popen[bytes],
    startup_timeout_seconds: float,
    inference_timeout_seconds: float,
    health: Callable[[], HealthState | None],
    monotonic: Callable[[], float] = time.monotonic,
    pause: Callable[[float], None] = time.sleep,
    stopping: Callable[[], bool] = lambda: False,
) -> WorkerResult:
    started_at = monotonic()
    deadline = started_at + startup_timeout_seconds
    last_observed_at = started_at
    became_ready = False
    ready_since: float | None = None
    active_since: float | None = None
    active_generation: int | None = None
    unhealthy_since: float | None = None
    longest_ready_uptime = 0.0
    while True:
        if stopping():
            if process.poll() is None:
                _terminate(process)
            return WorkerResult(
                became_ready,
                0,
                _ready_uptime(ready_since, last_observed_at, longest_ready_uptime),
            )
        exit_code = process.poll()
        if exit_code is not None:
            return WorkerResult(
                became_ready,
                exit_code,
                _ready_uptime(ready_since, last_observed_at, longest_ready_uptime),
            )
        state = health()
        now = monotonic()
        last_observed_at = now
        if not became_ready:
            if now >= deadline:
                _kill(process)
                return WorkerResult(False, 0, 0.0)
            if state is not None and state.ready:
                became_ready = True
                ready_since = now
                active_since = now if state.active else None
                active_generation = state.generation if state.active else None
                unhealthy_since = None
        else:
            if ready_since is not None:
                longest_ready_uptime = max(longest_ready_uptime, now - ready_since)
            if state is None or not state.ready:
                ready_since = None
                if unhealthy_since is None:
                    unhealthy_since = now
                if now - unhealthy_since >= inference_timeout_seconds:
                    _kill(process)
                    return WorkerResult(True, 1, _ready_uptime(ready_since, now, longest_ready_uptime))
            else:
                if ready_since is None:
                    ready_since = now
                unhealthy_since = None
            if state is not None and state.active:
                if active_since is None or state.generation != active_generation:
                    active_since = now
                    active_generation = state.generation
            elif state is not None:
                active_since = None
                active_generation = None
            if active_since is not None and now - active_since >= inference_timeout_seconds:
                _kill(process)
                return WorkerResult(True, 1, _ready_uptime(ready_since, now, longest_ready_uptime))
        pause(0.25)


def _health(url: str) -> HealthState | None:
    try:
        with urllib.request.urlopen(url, timeout=1) as response:
            payload = json.load(response)
            if response.status != 200 or not isinstance(payload, dict):
                return None
            ready = payload.get("ready") is True
            active = payload.get("active") is True
            generation = payload.get("generation")
            if active and (isinstance(generation, bool) or not isinstance(generation, int)
                           or generation <= 0):
                return None
            return HealthState(ready=ready, active=active, generation=generation if active else None)
    except (OSError, ValueError, http.client.HTTPException, urllib.error.URLError):
        return None


def _ready(url: str) -> bool:
    state = _health(url)
    return state is not None and state.ready


def _terminate(process: subprocess.Popen[bytes]) -> None:
    process.terminate()
    try:
        process.wait(timeout=5)
    except subprocess.TimeoutExpired:
        process.kill()
        process.wait(timeout=5)


def _kill(process: subprocess.Popen[bytes]) -> None:
    process.kill()
    process.wait(timeout=5)


def _ready_uptime(ready_since: float | None, ended_at: float, longest: float = 0.0) -> float:
    current = 0.0 if ready_since is None else max(0.0, ended_at - ready_since)
    return max(longest, current)


def _next_backoff(current: float, result: WorkerResult) -> float:
    if result.ready_uptime_seconds >= STABLE_UPTIME_SECONDS:
        return 1.0
    return min(current * 2, 60.0)


def _integer(name: str, default: int, minimum: int, maximum: int) -> int:
    raw = os.environ.get(name, str(default))
    try:
        value = int(raw)
    except ValueError as exception:
        raise ValueError(f"{name} must be an integer") from exception
    if not math.isfinite(value) or value < minimum or value > maximum:
        raise ValueError(f"{name} must be between {minimum} and {maximum}")
    return value


def _number(name: str, default: float, minimum: float, maximum: float) -> float:
    raw = os.environ.get(name, str(default))
    try:
        value = float(raw)
    except ValueError as exception:
        raise ValueError(f"{name} must be numeric") from exception
    if not math.isfinite(value) or value < minimum or value > maximum:
        raise ValueError(f"{name} must be between {minimum} and {maximum}")
    return value


if __name__ == "__main__":
    raise SystemExit(main())
