package ooo.klae.connex.backend.services;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Validated deployment gate and hard bounds for durable workflow execution. */
@Component
public class WorkflowRuntimeProperties {

    private static final int HARD_MAX_WORKSPACES_PER_SWEEP = 128;
    private static final int HARD_MAX_SCHEDULER_DELAY_MS = 300_000;
    private static final int HARD_MAX_INITIAL_DELAY_MS = 3_600_000;
    private static final int HARD_MAX_WORKSPACE_QUANTUM = 16;
    private static final int HARD_MAX_OUTBOX_LEASES_PER_WORKSPACE = 8;
    private static final int HARD_MAX_ACTIVE_RUNS_PER_WORKSPACE = 16;
    private static final int HARD_MAX_GLOBAL_WORKERS = 64;
    private static final int HARD_MAX_STEPS_PER_SLICE = 16;
    private static final int HARD_MAX_TRIGGER_FANOUT = 128;
    private static final int HARD_MAX_SCHEDULE_RECORDS_PER_PAGE = 200;
    private static final int HARD_MAX_TRIGGER_DELIVERY_ATTEMPTS = 8;
    private static final int HARD_MAX_RUN_DISPATCHES = 256;
    private static final int HARD_MAX_ACTION_ATTEMPTS = 3;
    private static final int HARD_MAX_RETENTION_DELETES_PER_WORKSPACE = 500;
    private static final Duration MIN_LEASE_DURATION = Duration.ofSeconds(10);
    private static final Duration MAX_LEASE_DURATION = Duration.ofMinutes(10);
    private static final Duration MAX_RETRY_DELAY = Duration.ofMinutes(15);

    private final boolean enabled;
    private final boolean schedulingEnabled;
    private final int schedulerDelayMs;
    private final int initialDelayMs;
    private final int maxWorkspacesPerSweep;
    private final int workspaceQuantum;
    private final int maxOutboxLeasesPerWorkspace;
    private final int maxActiveRunsPerWorkspace;
    private final int maxGlobalWorkers;
    private final int maxStepsPerSlice;
    private final int maxTriggerFanout;
    private final int maxScheduleRecordsPerPage;
    private final Duration leaseDuration;
    private final int maxTriggerDeliveryAttempts;
    private final int maxRunDispatches;
    private final int maxActionAttempts;
    private final Duration retryBase;
    private final Duration retryMaximum;
    private final Duration completedOutboxRetention;
    private final Duration deadOutboxRetention;
    private final int maxRetentionDeletesPerWorkspace;

    public WorkflowRuntimeProperties(
            @Value("${connex.workflows.runtime.enabled:false}") boolean enabled,
            @Value("${connex.workflows.runtime.scheduling-enabled:true}")
            boolean schedulingEnabled,
            @Value("${connex.workflows.runtime.scheduler-delay-ms:5000}")
            int schedulerDelayMs,
            @Value("${connex.workflows.runtime.initial-delay-ms:30000}")
            int initialDelayMs,
            @Value("${connex.workflows.runtime.max-workspaces-per-sweep:32}")
            int maxWorkspacesPerSweep,
            @Value("${connex.workflows.runtime.workspace-quantum:4}") int workspaceQuantum,
            @Value("${connex.workflows.runtime.max-outbox-leases-per-workspace:2}")
            int maxOutboxLeasesPerWorkspace,
            @Value("${connex.workflows.runtime.max-active-runs-per-workspace:4}")
            int maxActiveRunsPerWorkspace,
            @Value("${connex.workflows.runtime.max-global-workers:16}")
            int maxGlobalWorkers,
            @Value("${connex.workflows.runtime.max-steps-per-slice:8}")
            int maxStepsPerSlice,
            @Value("${connex.workflows.runtime.max-trigger-fanout:128}")
            int maxTriggerFanout,
            @Value("${connex.workflows.runtime.max-schedule-records-per-page:100}")
            int maxScheduleRecordsPerPage,
            @Value("${connex.workflows.runtime.lease-duration:PT2M}") Duration leaseDuration,
            @Value("${connex.workflows.runtime.max-trigger-delivery-attempts:8}")
            int maxTriggerDeliveryAttempts,
            @Value("${connex.workflows.runtime.max-run-dispatches:256}")
            int maxRunDispatches,
            @Value("${connex.workflows.runtime.max-action-attempts:3}")
            int maxActionAttempts,
            @Value("${connex.workflows.runtime.retry-base:PT30S}") Duration retryBase,
            @Value("${connex.workflows.runtime.retry-maximum:PT15M}") Duration retryMaximum,
            @Value("${connex.workflows.runtime.completed-outbox-retention:P7D}")
            Duration completedOutboxRetention,
            @Value("${connex.workflows.runtime.dead-outbox-retention:P30D}")
            Duration deadOutboxRetention,
            @Value("${connex.workflows.runtime.max-retention-deletes-per-workspace:100}")
            int maxRetentionDeletesPerWorkspace) {
        this.enabled = enabled;
        this.schedulingEnabled = schedulingEnabled;
        this.schedulerDelayMs = bounded(
            schedulerDelayMs, 250, HARD_MAX_SCHEDULER_DELAY_MS, "scheduler-delay-ms");
        this.initialDelayMs = bounded(
            initialDelayMs, 0, HARD_MAX_INITIAL_DELAY_MS, "initial-delay-ms");
        this.maxWorkspacesPerSweep = bounded(
            maxWorkspacesPerSweep, 1, HARD_MAX_WORKSPACES_PER_SWEEP,
            "max-workspaces-per-sweep");
        this.workspaceQuantum = bounded(
            workspaceQuantum, 1, HARD_MAX_WORKSPACE_QUANTUM, "workspace-quantum");
        this.maxOutboxLeasesPerWorkspace = bounded(
            maxOutboxLeasesPerWorkspace, 1, HARD_MAX_OUTBOX_LEASES_PER_WORKSPACE,
            "max-outbox-leases-per-workspace");
        this.maxActiveRunsPerWorkspace = bounded(
            maxActiveRunsPerWorkspace, 1, HARD_MAX_ACTIVE_RUNS_PER_WORKSPACE,
            "max-active-runs-per-workspace");
        this.maxGlobalWorkers = bounded(
            maxGlobalWorkers, 1, HARD_MAX_GLOBAL_WORKERS, "max-global-workers");
        this.maxStepsPerSlice = bounded(
            maxStepsPerSlice, 1, HARD_MAX_STEPS_PER_SLICE, "max-steps-per-slice");
        this.maxTriggerFanout = bounded(
            maxTriggerFanout, 1, HARD_MAX_TRIGGER_FANOUT, "max-trigger-fanout");
        this.maxScheduleRecordsPerPage = bounded(
            maxScheduleRecordsPerPage, 1, HARD_MAX_SCHEDULE_RECORDS_PER_PAGE,
            "max-schedule-records-per-page");
        this.leaseDuration = bounded(
            leaseDuration, MIN_LEASE_DURATION, MAX_LEASE_DURATION, "lease-duration");
        this.maxTriggerDeliveryAttempts = bounded(
            maxTriggerDeliveryAttempts, 1, HARD_MAX_TRIGGER_DELIVERY_ATTEMPTS,
            "max-trigger-delivery-attempts");
        this.maxRunDispatches = bounded(
            maxRunDispatches, 1, HARD_MAX_RUN_DISPATCHES, "max-run-dispatches");
        this.maxActionAttempts = bounded(
            maxActionAttempts, 1, HARD_MAX_ACTION_ATTEMPTS, "max-action-attempts");
        this.retryBase = bounded(
            retryBase, Duration.ofSeconds(1), MAX_RETRY_DELAY, "retry-base");
        this.retryMaximum = bounded(
            retryMaximum, retryBase, MAX_RETRY_DELAY, "retry-maximum");
        this.completedOutboxRetention = positive(
            completedOutboxRetention, "completed-outbox-retention");
        this.deadOutboxRetention = positive(deadOutboxRetention, "dead-outbox-retention");
        this.maxRetentionDeletesPerWorkspace = bounded(
            maxRetentionDeletesPerWorkspace,
            1,
            HARD_MAX_RETENTION_DELETES_PER_WORKSPACE,
            "max-retention-deletes-per-workspace");
    }

    public boolean enabled() {
        return enabled;
    }

    public boolean schedulingEnabled() {
        return schedulingEnabled;
    }

    public int schedulerDelayMs() {
        return schedulerDelayMs;
    }

    public int initialDelayMs() {
        return initialDelayMs;
    }

    public int maxWorkspacesPerSweep() {
        return maxWorkspacesPerSweep;
    }

    public int workspaceQuantum() {
        return workspaceQuantum;
    }

    public int maxOutboxLeasesPerWorkspace() {
        return maxOutboxLeasesPerWorkspace;
    }

    public int maxActiveRunsPerWorkspace() {
        return maxActiveRunsPerWorkspace;
    }

    public int maxGlobalWorkers() {
        return maxGlobalWorkers;
    }

    public int maxStepsPerSlice() {
        return maxStepsPerSlice;
    }

    public int maxTriggerFanout() {
        return maxTriggerFanout;
    }

    public int maxScheduleRecordsPerPage() {
        return maxScheduleRecordsPerPage;
    }

    public Duration leaseDuration() {
        return leaseDuration;
    }

    public int maxTriggerDeliveryAttempts() {
        return maxTriggerDeliveryAttempts;
    }

    public int maxRunDispatches() {
        return maxRunDispatches;
    }

    public int maxActionAttempts() {
        return maxActionAttempts;
    }

    public Duration retryBase() {
        return retryBase;
    }

    public Duration retryMaximum() {
        return retryMaximum;
    }

    public Duration completedOutboxRetention() {
        return completedOutboxRetention;
    }

    public Duration deadOutboxRetention() {
        return deadOutboxRetention;
    }

    public int maxRetentionDeletesPerWorkspace() {
        return maxRetentionDeletesPerWorkspace;
    }

    private static int bounded(int value, int minimum, int maximum, String name) {
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(
                name + " must be between " + minimum + " and " + maximum);
        }
        return value;
    }

    private static Duration bounded(
            Duration value, Duration minimum, Duration maximum, String name) {
        if (value == null || value.compareTo(minimum) < 0 || value.compareTo(maximum) > 0) {
            throw new IllegalArgumentException(name + " is outside the supported range");
        }
        return value;
    }

    private static Duration positive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }
}
