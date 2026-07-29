package ooo.klae.connex.backend.controllers;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.services.HealthService;
import ooo.klae.connex.backend.services.HealthService.Readiness;
import ooo.klae.connex.backend.services.HealthService.Status;

/**
 * Public instance liveness and readiness endpoints.
 */
@RestController
@RequestMapping("/api/health")
@RequiredArgsConstructor
public class HealthController {
    private final HealthService healthService;

    /**
     * Returns process liveness without touching dependencies.
     *
     * @return the liveness status
     */
    @GetMapping
    public HealthResponse health() {
        return new HealthResponse(Status.UP);
    }

    /**
     * Returns independently reduced database, migration and startup readiness statuses.
     *
     * @return readiness with 200 when all checks pass or 503 otherwise
     */
    @GetMapping("/ready")
    public ResponseEntity<ReadinessResponse> ready() {
        Readiness readiness = healthService.readiness();
        ReadinessResponse response = new ReadinessResponse(
                readiness.isUp() ? Status.UP : Status.DOWN,
                new Checks(
                        readiness.db(),
                        readiness.migrations(),
                        readiness.startup(),
                        readiness.auditGuard()));
        return ResponseEntity.status(readiness.isUp() ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE)
                .body(response);
    }

    /**
     * Liveness response.
     *
     * @param status process status
     */
    public record HealthResponse(Status status) {
    }

    /**
     * Readiness response.
     *
     * @param status overall readiness status
     * @param checks dependency readiness statuses
     */
    public record ReadinessResponse(Status status, Checks checks) {
    }

    /**
     * Individual readiness checks.
     *
     * @param db database connectivity status
     * @param migrations migration status
     * @param startup startup-runner completion status
     * @param auditGuard append-only audit-log guard status, reported but not gating
     */
    public record Checks(Status db, Status migrations, Status startup, Status auditGuard) {
    }
}
