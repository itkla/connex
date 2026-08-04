package ooo.klae.connex.backend.observability;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.ObjectMapper;

import ooo.klae.connex.backend.beans.JobRun;
import ooo.klae.connex.backend.mappers.JobRunMapper;

/**
 * Records bounded metadata-only job outcomes in an isolated transaction without
 * allowing observability failures to affect scheduled work.
 */
@Service
public class JobRunRecorder {
    public static final String NOTIFICATION_RECONCILIATION = "notification_reconciliation";
    public static final String REPORT_DELIVERY = "report_delivery";
    public static final String RULE_SCHEDULER = "rule_scheduler";
    public static final String CAMPAIGN_SEND = "campaign_send";
    public static final String BUSINESS_CARD_IMPORT_CLEANUP = "business_card_import_cleanup";
    public static final String PROVIDER_CAPTURE = "provider_capture";
    public static final String OBJECT_DELETION_RETRY = "object_deletion_retry";
    public static final String WORKFLOW_RUNTIME = "workflow_runtime";

    private static final Logger log = LoggerFactory.getLogger(JobRunRecorder.class);
    /**
     * Rows retained per {@code (job_name, workspace_id, status)} partition.
     *
     * <p>The partition deliberately includes {@code status}. Retaining the newest rows per
     * job and workspace alone lets a high-frequency job — {@code provider_capture} polls every
     * five seconds — evict its own last failure within minutes, which makes the last-failure
     * readout structurally useless and reports a broken job as healthy. Including {@code status}
     * bounds the table at {@code KEEP_COUNT} x 3 statuses per job per workspace and keeps
     * last-success and last-failure independent of run volume. Do not simplify this back.
     */
    private static final int KEEP_COUNT = 50;
    private static final int MAX_METADATA_KEYS = 8;
    private static final int MAX_DETAIL_LENGTH = 1024;
    private static final int MAX_STRING_LENGTH = 64;
    private static final Set<String> JOB_NAMES = Set.of(
        NOTIFICATION_RECONCILIATION,
        REPORT_DELIVERY,
        RULE_SCHEDULER,
        CAMPAIGN_SEND,
        BUSINESS_CARD_IMPORT_CLEANUP,
        PROVIDER_CAPTURE,
        OBJECT_DELETION_RETRY,
        WORKFLOW_RUNTIME);
    private static final Set<String> METADATA_KEYS = Set.of(
        "phase",
        "purgedCount",
        "completedCadences",
        "failedCadences",
        "deletedCount",
        "dueCount",
        "attemptedCount",
        "claimedCount",
        "failedCount",
        "scheduleId",
        "snapshotId",
        "recipientCount");

    private final JobRunMapper mapper;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final TransactionTemplate transaction;

    public JobRunRecorder(
            JobRunMapper mapper,
            ObjectMapper objectMapper,
            Clock clock,
            PlatformTransactionManager transactionManager) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.transaction = new TransactionTemplate(
            Objects.requireNonNull(transactionManager, "transactionManager"));
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Persists one stable job outcome and suppresses every runtime recording failure.
     *
     * @param jobName stable scheduler name
     * @param workspaceId tenant scope, or {@code null} for an instance phase
     * @param status scheduler-boundary outcome
     * @param detail start time and metadata candidates
     */
    public void record(
            String jobName,
            Integer workspaceId,
            JobRunStatus status,
            JobRunDetail detail) {
        String loggedJobName = JOB_NAMES.contains(jobName) ? jobName : "unknown";
        try {
            requireJobName(jobName);
            Objects.requireNonNull(status, "status");
            LocalDateTime finishedAt = now();
            LocalDateTime startedAt = detail == null || detail.startedAt() == null
                ? finishedAt
                : detail.startedAt();
            if (startedAt.isAfter(finishedAt)) {
                startedAt = finishedAt;
            }
            JobRun jobRun = new JobRun();
            jobRun.setJobName(jobName);
            jobRun.setWorkspaceId(workspaceId);
            jobRun.setStatus(status.token());
            jobRun.setStartedAt(startedAt);
            jobRun.setFinishedAt(finishedAt);
            jobRun.setDetail(sanitizeDetail(workspaceId, detail));
            transaction.executeWithoutResult(transactionStatus -> {
                mapper.insert(jobRun);
                mapper.deleteBeyondRetention(jobName, workspaceId, status.token(), KEEP_COUNT);
            });
        } catch (RuntimeException exception) {
            log.warn(
                "Job run recording failed jobName={} status={} exceptionClass={}",
                loggedJobName,
                status == null ? "unknown" : status.name(),
                exception.getClass().getSimpleName());
        }
    }

    /** Stable scheduler-boundary outcomes. */
    public enum JobRunStatus {
        SUCCEEDED("succeeded"),
        FAILED("failed"),
        SKIPPED("skipped");

        private final String token;

        JobRunStatus(String token) {
            this.token = token;
        }

        public String token() {
            return token;
        }
    }

    /**
     * Captures the scheduler boundary start and untrusted metadata candidates.
     */
    public record JobRunDetail(LocalDateTime startedAt, Map<String, ?> metadata) {
        public JobRunDetail {
            metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        }

        /** Creates an empty detail starting at the supplied UTC instant. */
        public static JobRunDetail started(Clock clock) {
            return new JobRunDetail(
                LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC), Map.of());
        }

        /** Creates a detail starting at the supplied UTC instant. */
        public static JobRunDetail started(Clock clock, Map<String, ?> metadata) {
            return new JobRunDetail(
                LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC), metadata);
        }

        /** Creates an empty detail starting at the current UTC instant. */
        public static JobRunDetail startedUtc() {
            return started(Clock.systemUTC());
        }

        /** Creates a detail starting at the current UTC instant. */
        public static JobRunDetail startedUtc(Map<String, ?> metadata) {
            return started(Clock.systemUTC(), metadata);
        }
    }

    private String sanitizeDetail(Integer workspaceId, JobRunDetail detail) {
        if (detail == null || detail.metadata().isEmpty()) {
            return null;
        }
        Map<String, Object> sanitized = new LinkedHashMap<>();
        for (Map.Entry<String, ?> entry : detail.metadata().entrySet()) {
            if (sanitized.size() >= MAX_METADATA_KEYS) {
                break;
            }
            String key = entry.getKey();
            if (!METADATA_KEYS.contains(key)) {
                continue;
            }
            if (workspaceId == null && !"phase".equals(key)) {
                continue;
            }
            Object value = sanitizePrimitive(key, entry.getValue());
            if (value == null) {
                continue;
            }
            sanitized.put(key, value);
            String json = objectMapper.writeValueAsString(sanitized);
            if (json.length() > MAX_DETAIL_LENGTH) {
                sanitized.remove(key);
                break;
            }
        }
        return sanitized.isEmpty() ? null : objectMapper.writeValueAsString(sanitized);
    }

    private Object sanitizePrimitive(String key, Object value) {
        if (value instanceof String stringValue) {
            if (!"phase".equals(key)) {
                return null;
            }
            String normalized = stringValue.trim();
            if (normalized.isEmpty()
                    || normalized.length() > MAX_STRING_LENGTH
                    || !normalized.matches("[a-z][a-z0-9_]*")) {
                return null;
            }
            return normalized;
        }
        if (value instanceof Byte || value instanceof Short || value instanceof Integer
                || value instanceof Long || value instanceof Float || value instanceof Double) {
            return value;
        }
        if (value instanceof Boolean) {
            return value;
        }
        return null;
    }

    private static void requireJobName(String jobName) {
        if (!JOB_NAMES.contains(jobName)) {
            throw new IllegalArgumentException("Unknown job name");
        }
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }
}
