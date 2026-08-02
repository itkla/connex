package ooo.klae.connex.backend.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Metadata-only deployment, provider, scheduled-job, and secret-store diagnostics.
 */
public record TenantDiagnosticsDto(
        Scope scope,
        Deployment deployment,
        Providers providers,
        List<Job> jobs,
        List<Finding> findings,
        SecretStoreDiagnosticsDto secretStore,
        List<SectionFault> unavailableSections) {

    /** Defensively copies every diagnostics collection. */
    public TenantDiagnosticsDto {
        jobs = List.copyOf(jobs);
        findings = List.copyOf(findings);
    }

    /** Requested diagnostics scope. */
    public record Scope(String type, int id) {
    }

    /**
     * Names a section whose source failed, so the client can distinguish a degraded diagnostic
     * from a healthy empty one. Without this an aggregation fault and a genuinely idle instance
     * both render as an empty list, which is the failure mode this whole feature exists to avoid.
     *
     * @param section stable section code
     * @param reason stable, non-sensitive reason code
     */
    public record SectionFault(String section, String reason) {
    }

    /** Deployment profile and capability posture. */
    public record Deployment(String profile, boolean configured, List<CapabilityState> capabilities) {
        /** Defensively copies capability posture. */
        public Deployment {
            capabilities = List.copyOf(capabilities);
        }
    }

    /** One instance capability's profile and runtime availability. */
    public record CapabilityState(String capability, boolean profileAllowed, boolean available) {
    }

    /** Provider readiness across the requested tenant scope. */
    public record Providers(Ai ai, Ocr ocr, List<WorkspaceProviders> workspaces) {
        /** Defensively copies workspace provider diagnostics. */
        public Providers {
            workspaces = List.copyOf(workspaces);
        }
    }

    /** Organization AI provider readiness. */
    public record Ai(boolean ready, boolean imageInputReady) {
    }

    /** Instance OCR and reviewed-import readiness. */
    public record Ocr(boolean scanningAvailable, boolean importAvailable) {
    }

    /** Provider readiness for one workspace. */
    public record WorkspaceProviders(
            int workspaceId,
            Mail mail,
            List<Delivery> delivery,
            List<Capture> capture) {
        /** Defensively copies provider diagnostics. */
        public WorkspaceProviders {
            delivery = List.copyOf(delivery);
            capture = List.copyOf(capture);
        }
    }

    /** Effective mail transport mode and configuration state. */
    public record Mail(String mode, boolean configured) {
    }

    /** Delivery readiness for one channel. */
    public record Delivery(String channel, boolean implemented, boolean ready) {
    }

    /** Aggregated metadata-only connected-capture stream state. */
    public record Capture(
            String provider,
            String stream,
            String status,
            long stateCount,
            long stableCursorCount,
            long pageCursorCount,
            long processedItems,
            long estimatedItems,
            LocalDateTime lastAttemptAt,
            LocalDateTime lastSuccessAt,
            LocalDateTime nextAttemptAt,
            List<String> errorCodes) {
        /** Defensively copies stable error codes. */
        public Capture {
            errorCodes = List.copyOf(errorCodes);
        }
    }

    /**
     * Latest visible outcomes for one stable scheduler name.
     *
     * <p>{@code last} is the most recent run anywhere in the requested scope, so on an
     * organization it can be a success from one workspace while another is failing.
     * {@code workspacesFailingLatest} counts the scopes whose own most recent run failed, so a
     * degraded workspace is never hidden behind a healthier sibling.
     */
    public record Job(
            String jobName,
            JobRun last,
            JobRun lastSuccess,
            JobRun lastFailure,
            int workspacesFailingLatest) {
    }

    /** One metadata-only scheduler outcome. */
    public record JobRun(
            Integer workspaceId,
            String status,
            LocalDateTime startedAt,
            LocalDateTime finishedAt,
            Map<String, Object> detail) {
        /** Defensively copies sanitized detail metadata. */
        public JobRun {
            detail = detail == null ? null : Map.copyOf(detail);
        }
    }

    /** Stable actionable tenant diagnostic finding. */
    public record Finding(
            String code,
            String severity,
            Integer workspaceId,
            String capability,
            String provider,
            String channel,
            String stream) {
    }
}
