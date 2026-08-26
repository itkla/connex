package ooo.klae.connex.backend.services;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import ooo.klae.connex.backend.ai.AiProviderReadiness;
import ooo.klae.connex.backend.beans.JobRun;
import ooo.klae.connex.backend.capability.Capability;
import ooo.klae.connex.backend.capability.CapabilityRegistry;
import ooo.klae.connex.backend.config.DeploymentProperties;
import ooo.klae.connex.backend.delivery.DeliveryChannel;
import ooo.klae.connex.backend.delivery.DeliveryProviderReadiness;
import ooo.klae.connex.backend.dto.ProviderCaptureDiagnosticsRow;
import ooo.klae.connex.backend.dto.SecretStoreDiagnosticsDto;
import ooo.klae.connex.backend.dto.TenantDiagnosticsDto;
import ooo.klae.connex.backend.dto.TenantDiagnosticsDto.Ai;
import ooo.klae.connex.backend.dto.TenantDiagnosticsDto.CapabilityState;
import ooo.klae.connex.backend.dto.TenantDiagnosticsDto.Capture;
import ooo.klae.connex.backend.dto.TenantDiagnosticsDto.Delivery;
import ooo.klae.connex.backend.dto.TenantDiagnosticsDto.Deployment;
import ooo.klae.connex.backend.dto.TenantDiagnosticsDto.Finding;
import ooo.klae.connex.backend.dto.TenantDiagnosticsDto.Job;
import ooo.klae.connex.backend.dto.TenantDiagnosticsDto.Mail;
import ooo.klae.connex.backend.dto.TenantDiagnosticsDto.Ocr;
import ooo.klae.connex.backend.dto.TenantDiagnosticsDto.Providers;
import ooo.klae.connex.backend.dto.TenantDiagnosticsDto.Scope;
import ooo.klae.connex.backend.dto.TenantDiagnosticsDto.SectionFault;
import ooo.klae.connex.backend.dto.TenantDiagnosticsDto.WorkspaceProviders;
import ooo.klae.connex.backend.mail.MailConfigResolver;
import ooo.klae.connex.backend.mail.ResolvedMailConfig;
import ooo.klae.connex.backend.mappers.JobRunMapper;
import ooo.klae.connex.backend.mappers.ProviderCaptureMapper;
import ooo.klae.connex.backend.observability.JobRunRecorder;
import ooo.klae.connex.backend.secrets.SecretStoreLifecycleService;
import ooo.klae.connex.backend.services.OrganizationWorkspaceScopeControlOperations.WorkspaceScope;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Assembles redacted tenant diagnostics exclusively from saved configuration and metadata.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TenantDiagnosticsService {
    private static final TypeReference<Map<String, Object>> DETAIL_TYPE = new TypeReference<>() {
    };
    private static final List<String> JOB_NAMES = List.of(
            JobRunRecorder.NOTIFICATION_RECONCILIATION,
            JobRunRecorder.REPORT_DELIVERY,
            JobRunRecorder.RULE_SCHEDULER,
            JobRunRecorder.CAMPAIGN_SEND,
            JobRunRecorder.BUSINESS_CARD_IMPORT_CLEANUP,
            JobRunRecorder.PROVIDER_CAPTURE,
            JobRunRecorder.OBJECT_DELETION_RETRY,
            JobRunRecorder.APPROVAL_RECONCILIATION,
            JobRunRecorder.RELATIONSHIP_SIGNAL_RECONCILIATION,
            JobRunRecorder.LEAD_RESPONSE_SLA);
    private static final Set<String> JOB_DETAIL_KEYS = Set.of(
            "phase",
            "purgedCount",
            "completedCadences",
            "failedCadences",
            "deletedCount",
            "dueCount",
            "attemptedCount",
            "failedCount",
            "scheduleId",
            "snapshotId",
            "recipientCount");

    private final DeploymentProperties deploymentProperties;
    private final CapabilityRegistry capabilityRegistry;
    private final AiProviderReadiness aiProviderReadiness;
    private final MailConfigResolver mailConfigResolver;
    private final DeliveryProviderReadiness deliveryProviderReadiness;
    private final BusinessCardService businessCardService;
    private final ProviderCaptureMapper providerCaptureMapper;
    private final JobRunMapper jobRunMapper;
    private final OrganizationWorkspaceScopeControlAccess workspaceScopeControlAccess;
    private final TenantDiagnosticsTenantAccess tenantAccess;
    private final SecretStoreLifecycleService secretStoreLifecycleService;
    private final ObjectMapper objectMapper;

    /**
     * Builds diagnostics for one authorized workspace without exposing sibling workspace state.
     *
     * @param workspaceId authorized workspace
     * @param actorId authorized actor
     * @return metadata-only diagnostics
     */
    public TenantDiagnosticsDto forWorkspace(int workspaceId, int actorId) {
        WorkspaceScope organizationScope = workspaceScopeControlAccess.getForWorkspace(workspaceId);
        WorkspaceScope workspaceScope = new WorkspaceScope(
                organizationScope.orgId(), List.of(workspaceId), "[" + workspaceId + "]");
        List<SectionFault> faults = new ArrayList<>();
        TenantSections sections = guarded(
                "jobs_providers", faults,
                () -> tenantAccess.inWorkspace(
                        workspaceId,
                        organizationScope.orgId(),
                        actorId,
                        () -> tenantSections(workspaceScope)),
                TenantSections.empty());
        SecretStoreDiagnosticsDto secretStore = guarded(
                "secret_store", faults,
                () -> secretStoreLifecycleService.diagnosticsForWorkspace(workspaceId), null);
        return assemble(
                new Scope("workspace", workspaceId),
                organizationScope.orgId(),
                sections,
                secretStore,
                faults);
    }

    /**
     * Builds diagnostics for an authorized organization administrator.
     *
     * @param orgId authorized organization
     * @param actorId authorized organization administrator
     * @return metadata-only diagnostics
     */
    public TenantDiagnosticsDto forOrganization(int orgId, int actorId) {
        List<SectionFault> faults = new ArrayList<>();
        WorkspaceScope scope = workspaceScopeControlAccess.getForOrg(orgId);
        TenantSections sections = scope.workspaceIds().isEmpty()
                ? TenantSections.empty()
                : guarded(
                        "jobs_providers", faults,
                        () -> tenantAccess.inOrganization(scope, actorId, () -> tenantSections(scope)),
                        TenantSections.empty());
        SecretStoreDiagnosticsDto secretStore = guarded(
                "secret_store", faults,
                () -> secretStoreLifecycleService.diagnosticsForOrg(orgId), null);
        return assemble(new Scope("organization", orgId), orgId, sections, secretStore, faults);
    }

    private <T> T guarded(
            String section, List<SectionFault> faults, Supplier<T> source, T unavailable) {
        try {
            return source.get();
        } catch (RuntimeException exception) {
            log.warn("Diagnostics section {} unavailable exceptionClass={}",
                    section, exception.getClass().getSimpleName());
            faults.add(new SectionFault(section, "source_unavailable"));
            return unavailable;
        }
    }

    private TenantDiagnosticsDto assemble(
            Scope scope,
            int orgId,
            TenantSections sections,
            SecretStoreDiagnosticsDto secretStore,
            List<SectionFault> faults) {
        Deployment deployment = guarded(
                "deployment", faults, this::deployment, new Deployment(null, false, List.of()));
        Ai ai = guarded(
                "ai", faults,
                () -> new Ai(
                        aiProviderReadiness.isReadyForOrg(orgId),
                        aiProviderReadiness.isImageInputReadyForOrg(orgId)),
                new Ai(false, false));
        Ocr ocr = guarded(
                "ocr", faults,
                () -> new Ocr(
                        businessCardService.isAvailableCached(),
                        businessCardService.isImportAvailableCached()),
                new Ocr(false, false));
        List<Finding> findings = guarded(
                "findings", faults, () -> findings(ai, sections.workspaces()), List.of());
        return new TenantDiagnosticsDto(
                scope,
                deployment,
                new Providers(ai, ocr, sections.workspaces()),
                sections.jobs(),
                findings,
                secretStore,
                List.copyOf(faults));
    }

    private Deployment deployment() {
        String profile = deploymentProperties.isConfigured()
                ? deploymentProperties.getProfile()
                : null;
        List<CapabilityState> capabilities = new ArrayList<>();
        for (Capability capability : Capability.values()) {
            capabilities.add(new CapabilityState(
                    capability.name().toLowerCase(Locale.ROOT),
                    CapabilityRegistry.isAllowedForProfile(capability, profile),
                    capabilityRegistry.isAvailableWithoutProbing(capability)));
        }
        return new Deployment(profile, deploymentProperties.isConfigured(), capabilities);
    }

    private TenantSections tenantSections(WorkspaceScope scope) {
        int anchorWorkspaceId = scope.workspaceIds().getFirst();
        List<ProviderCaptureDiagnosticsRow> captureRows =
                providerCaptureMapper.findDiagnosticsAggregates(
                        anchorWorkspaceId, scope.workspaceIdsJson());
        Map<Integer, List<Capture>> captureByWorkspace = captureByWorkspace(captureRows);
        List<WorkspaceProviders> workspaces = scope.workspaceIds().stream()
                .map(workspaceId -> workspaceProviders(
                        workspaceId,
                        captureByWorkspace.getOrDefault(workspaceId, List.of())))
                .toList();
        List<JobRun> last = jobRunMapper.findLatestVisible(
                anchorWorkspaceId, scope.workspaceIdsJson(), null);
        List<JobRun> lastSuccess = jobRunMapper.findLatestVisible(
                anchorWorkspaceId, scope.workspaceIdsJson(), "succeeded");
        List<JobRun> lastFailure = jobRunMapper.findLatestVisible(
                anchorWorkspaceId, scope.workspaceIdsJson(), "failed");
        return new TenantSections(workspaces, jobs(last, lastSuccess, lastFailure));
    }

    private WorkspaceProviders workspaceProviders(int workspaceId, List<Capture> capture) {
        ResolvedMailConfig mailConfig = resolveMail(workspaceId);
        boolean mailConfigured = mailConfig != null && mailConfig.usable();
        Mail mail = new Mail(mailConfigResolver.effectiveMode(mailConfig), mailConfigured);
        List<Delivery> delivery = new ArrayList<>();
        for (DeliveryChannel channel : DeliveryChannel.values()) {
            boolean implemented = channel == DeliveryChannel.EMAIL || channel == DeliveryChannel.SMS;
            boolean ready = implemented && deliveryReady(workspaceId, channel);
            delivery.add(new Delivery(channel.token(), implemented, ready));
        }
        return new WorkspaceProviders(workspaceId, mail, delivery, capture);
    }

    private ResolvedMailConfig resolveMail(int workspaceId) {
        try {
            return mailConfigResolver.resolveForWorkspace(workspaceId);
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private boolean deliveryReady(int workspaceId, DeliveryChannel channel) {
        try {
            return deliveryProviderReadiness.isReady(workspaceId, channel);
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private Map<Integer, List<Capture>> captureByWorkspace(
            List<ProviderCaptureDiagnosticsRow> rows) {
        Map<CaptureKey, List<ProviderCaptureDiagnosticsRow>> grouped = new LinkedHashMap<>();
        for (ProviderCaptureDiagnosticsRow row : rows) {
            CaptureKey key = new CaptureKey(
                    row.workspaceId(), row.provider(), row.stream(), row.status());
            grouped.computeIfAbsent(key, ignored -> new ArrayList<>()).add(row);
        }
        Map<Integer, List<Capture>> byWorkspace = new LinkedHashMap<>();
        for (Map.Entry<CaptureKey, List<ProviderCaptureDiagnosticsRow>> entry : grouped.entrySet()) {
            CaptureKey key = entry.getKey();
            List<ProviderCaptureDiagnosticsRow> values = entry.getValue();
            Set<String> errorCodes = new LinkedHashSet<>();
            for (ProviderCaptureDiagnosticsRow value : values) {
                if (value.errorCode() != null && !value.errorCode().isBlank()) {
                    errorCodes.add(value.errorCode());
                }
            }
            Capture capture = new Capture(
                    key.provider(),
                    key.stream(),
                    key.status(),
                    sum(values, ProviderCaptureDiagnosticsRow::stateCount),
                    sum(values, ProviderCaptureDiagnosticsRow::stableCursorCount),
                    sum(values, ProviderCaptureDiagnosticsRow::pageCursorCount),
                    sum(values, ProviderCaptureDiagnosticsRow::processedItems),
                    sum(values, ProviderCaptureDiagnosticsRow::estimatedItems),
                    maximum(values, ProviderCaptureDiagnosticsRow::lastAttemptAt),
                    maximum(values, ProviderCaptureDiagnosticsRow::lastSuccessAt),
                    maximum(values, ProviderCaptureDiagnosticsRow::nextAttemptAt),
                    errorCodes.stream().sorted().toList());
            byWorkspace.computeIfAbsent(key.workspaceId(), ignored -> new ArrayList<>()).add(capture);
        }
        for (List<Capture> captures : byWorkspace.values()) {
            captures.sort(Comparator.comparing(Capture::provider)
                    .thenComparing(Capture::stream)
                    .thenComparing(Capture::status));
        }
        return byWorkspace;
    }

    private static long sum(
            List<ProviderCaptureDiagnosticsRow> rows,
            java.util.function.ToLongFunction<ProviderCaptureDiagnosticsRow> value) {
        return rows.stream().mapToLong(value).sum();
    }

    private static LocalDateTime maximum(
            List<ProviderCaptureDiagnosticsRow> rows,
            Function<ProviderCaptureDiagnosticsRow, LocalDateTime> value) {
        return rows.stream()
                .map(value)
                .filter(Objects::nonNull)
                .max(LocalDateTime::compareTo)
                .orElse(null);
    }

    private List<Job> jobs(
            List<JobRun> last,
            List<JobRun> lastSuccess,
            List<JobRun> lastFailure) {
        Map<String, JobRun> lastByName = byJobName(last);
        Map<String, JobRun> successByName = byJobName(lastSuccess);
        Map<String, JobRun> failureByName = byJobName(lastFailure);
        Map<String, Integer> failingByName = failingScopesByJobName(last);
        List<Job> jobs = new ArrayList<>();
        for (String jobName : JOB_NAMES) {
            jobs.add(new Job(
                    jobName,
                    jobRun(lastByName.get(jobName)),
                    jobRun(successByName.get(jobName)),
                    jobRun(failureByName.get(jobName)),
                    failingByName.getOrDefault(jobName, 0)));
        }
        return jobs;
    }

    private static Map<String, Integer> failingScopesByJobName(List<JobRun> latestPerScope) {
        Map<String, Integer> failing = new LinkedHashMap<>();
        for (JobRun run : latestPerScope) {
            if ("failed".equals(run.getStatus())) {
                failing.merge(run.getJobName(), 1, Integer::sum);
            }
        }
        return failing;
    }

    private static Map<String, JobRun> byJobName(List<JobRun> runs) {
        Map<String, JobRun> byName = new LinkedHashMap<>();
        for (JobRun run : runs) {
            JobRun current = byName.get(run.getJobName());
            if (current != null && !run.getStartedAt().isAfter(current.getStartedAt())) {
                continue;
            }
            byName.put(run.getJobName(), run);
        }
        return byName;
    }

    private TenantDiagnosticsDto.JobRun jobRun(JobRun run) {
        if (run == null) {
            return null;
        }
        return new TenantDiagnosticsDto.JobRun(
                run.getWorkspaceId(),
                run.getStatus(),
                run.getStartedAt(),
                run.getFinishedAt(),
                detail(run.getDetail(), run.getWorkspaceId()));
    }

    private Map<String, Object> detail(String json, Integer workspaceId) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            Map<String, Object> parsed = objectMapper.readValue(json, DETAIL_TYPE);
            Map<String, Object> primitives = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : parsed.entrySet()) {
                if (!JOB_DETAIL_KEYS.contains(entry.getKey())
                        || workspaceId == null && !"phase".equals(entry.getKey())) {
                    continue;
                }
                Object value = entry.getValue();
                if (value instanceof String stringValue
                        && "phase".equals(entry.getKey())
                        && stringValue.matches("[a-z][a-z0-9_]{0,63}")) {
                    primitives.put(entry.getKey(), value);
                } else if (value instanceof Number || value instanceof Boolean) {
                    primitives.put(entry.getKey(), value);
                }
            }
            return primitives.isEmpty() ? null : primitives;
        } catch (JacksonException exception) {
            return null;
        }
    }

    private List<Finding> findings(Ai ai, List<WorkspaceProviders> workspaces) {
        List<Finding> findings = new ArrayList<>();
        if (!ai.ready() || !ai.imageInputReady()) {
            findings.add(new Finding(
                    "ai_provider_incomplete", "warning", null, null, "ai", null, null));
        }
        for (WorkspaceProviders workspace : workspaces) {
            if (!workspace.mail().configured()) {
                findings.add(new Finding(
                        "mail_unconfigured",
                        "warning",
                        workspace.workspaceId(),
                        null,
                        null,
                        null,
                        null));
            }
            for (Delivery delivery : workspace.delivery()) {
                if (delivery.implemented() && !delivery.ready()) {
                    findings.add(new Finding(
                            "delivery_provider_missing",
                            "warning",
                            workspace.workspaceId(),
                            null,
                            null,
                            delivery.channel(),
                            null));
                }
            }
            for (Capture capture : workspace.capture()) {
                if ("paused".equals(capture.status())) {
                    findings.add(new Finding(
                            "capture_paused",
                            "warning",
                            workspace.workspaceId(),
                            null,
                            capture.provider(),
                            null,
                            capture.stream()));
                }
            }
        }
        return findings;
    }

    private record CaptureKey(int workspaceId, String provider, String stream, String status) {
    }

    private record TenantSections(List<WorkspaceProviders> workspaces, List<Job> jobs) {
        private TenantSections {
            workspaces = List.copyOf(workspaces);
            jobs = List.copyOf(jobs);
        }

        private static TenantSections empty() {
            return new TenantSections(List.of(), List.of());
        }
    }
}
