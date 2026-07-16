from typing import Literal


StartupReason = Literal[
    "unsupported_cpu_architecture",
    "cpu_capabilities_unreadable",
    "avx_unavailable",
    "models_unavailable",
    "runtime_dependency_unavailable",
    "invalid_configuration",
    "engine_initialization_failed",
]


class StartupFailure(RuntimeError):
    def __init__(self, reason: StartupReason) -> None:
        super().__init__(reason)
        self.reason = reason
