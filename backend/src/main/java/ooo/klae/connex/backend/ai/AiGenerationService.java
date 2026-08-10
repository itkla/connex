package ooo.klae.connex.backend.ai;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import ooo.klae.connex.backend.dto.AiGenerationStatusDto;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.exceptions.TooManyRequestsException;
import ooo.klae.connex.backend.services.WorkspaceService;
import ooo.klae.connex.backend.tenant.Permission;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Bounded single-node delivery registry for long-running AI generation. Feature services retain
 * ownership of policy, cache identity, quota, validation, and provider single-flight; this boundary
 * only moves their work off the request thread and exposes short workspace-scoped status reads.
 */
@Service
public class AiGenerationService {
    private static final String CAPACITY_MESSAGE = "AI generation is busy; retry shortly";

    private final WorkspaceService workspaceService;
    private final AiFeatureGate aiFeatureGate;
    private final AiRestrictionEpoch aiRestrictionEpoch;
    private final AiGenerationContextRunner contextRunner;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final int maxHandles;
    private final int maxActivePerUser;
    private final int maxHandlesPerUser;
    private final int maxResultBytes;
    private final long maxRetainedResultBytes;
    private final long maxRetainedResultBytesPerWorkspace;
    private final long maxRetainedResultBytesPerUser;
    private final Duration maxLifetime;
    private final Duration pollWindow;
    private final Duration pollInterval;
    private final ThreadPoolExecutor workers;
    private final ScheduledThreadPoolExecutor scheduler;
    private final Object stateLock = new Object();
    private final Map<String, GenerationState> generations = new LinkedHashMap<>();
    private final Map<GenerationKey, String> activeHandles = new LinkedHashMap<>();
    private long retainedResultBytes;

    public AiGenerationService(
            AiProperties properties,
            WorkspaceService workspaceService,
            AiFeatureGate aiFeatureGate,
            AiRestrictionEpoch aiRestrictionEpoch,
            AiGenerationContextRunner contextRunner,
            ObjectMapper objectMapper,
            Clock clock) {
        AiProperties configured = Objects.requireNonNull(properties, "properties");
        this.workspaceService = Objects.requireNonNull(workspaceService, "workspaceService");
        this.aiFeatureGate = Objects.requireNonNull(aiFeatureGate, "aiFeatureGate");
        this.aiRestrictionEpoch = Objects.requireNonNull(aiRestrictionEpoch, "aiRestrictionEpoch");
        this.contextRunner = Objects.requireNonNull(contextRunner, "contextRunner");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.clock = Objects.requireNonNull(clock, "clock");
        int workerThreads = positive(configured.getGenerationWorkerThreads(), "worker threads");
        int queueCapacity = positive(configured.getGenerationQueueCapacity(), "queue capacity");
        maxHandles = positive(configured.getGenerationMaxHandles(), "handle capacity");
        maxActivePerUser = positive(configured.getGenerationMaxActivePerUser(), "per-user active capacity");
        maxHandlesPerUser = positive(
                configured.getGenerationMaxHandlesPerUser(), "per-user handle capacity");
        maxResultBytes = positive(configured.getGenerationMaxResultBytes(), "result byte capacity");
        maxRetainedResultBytes = positive(
                configured.getGenerationMaxRetainedResultBytes(), "retained result byte capacity");
        maxRetainedResultBytesPerWorkspace = positive(
                configured.getGenerationMaxRetainedResultBytesPerWorkspace(),
                "per-workspace retained result byte capacity");
        maxRetainedResultBytesPerUser = positive(
                configured.getGenerationMaxRetainedResultBytesPerUser(),
                "per-user retained result byte capacity");
        maxLifetime = positiveDuration(configured.getGenerationMaxLifetime(), "maximum lifetime");
        pollWindow = positiveDuration(configured.getGenerationPollWindow(), "poll window");
        pollInterval = positiveDuration(configured.getGenerationPollInterval(), "poll interval");
        if (pollWindow.compareTo(maxLifetime) < 0) {
            throw new IllegalStateException("AI generation poll window must cover the maximum lifetime");
        }
        if (maxHandles < workerThreads + queueCapacity) {
            throw new IllegalStateException("AI generation handle capacity must cover active work capacity");
        }
        if (maxRetainedResultBytesPerUser < maxResultBytes
                || maxRetainedResultBytesPerWorkspace < maxRetainedResultBytesPerUser
                || maxRetainedResultBytes < maxRetainedResultBytesPerWorkspace) {
            throw new IllegalStateException(
                    "AI generation result capacities must cover one result and nest by user, workspace, and instance");
        }
        workers = new ThreadPoolExecutor(
                workerThreads,
                workerThreads,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                Thread.ofPlatform().daemon().name("connex-ai-generation-", 0).factory(),
                new ThreadPoolExecutor.AbortPolicy());
        scheduler = new ScheduledThreadPoolExecutor(
                1,
                Thread.ofPlatform().daemon().name("connex-ai-generation-timer-", 0).factory());
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.scheduleWithFixedDelay(
                this::purgeExpired,
                pollInterval.toMillis(),
                pollInterval.toMillis(),
                TimeUnit.MILLISECONDS);
    }

    /**
     * Starts or joins one exact active generation request.
     * @param feature AI feature adapter
     * @param identityMaterial bounded normalized material used only through its SHA-256 fingerprint
     * @param requiredPermissions permissions that must remain present for execution and disclosure
     * @param unavailableResult graceful feature result returned when initial permissions are absent
     * @param task feature-owned generation work
     * @param <T> feature result type
     * @return accepted status, an existing coalesced status, or a resolved unavailable status
     */
    public <T> AiGenerationStatusDto start(
            AiFeature feature,
            Object identityMaterial,
            Set<Permission> requiredPermissions,
            T unavailableResult,
            Supplier<AiGenerationTaskResult<T>> task) {
        return startInternal(
                feature, identityMaterial, requiredPermissions, unavailableResult, task, null,
                AiGenerationTerminalListener.NO_OP);
    }

    /**
     * Starts report generation only when the frozen document still matches the current restriction
     * epoch, preventing pre-restriction source material from entering the worker queue.
     */
    public <T> AiGenerationStatusDto startAtRestrictionEpoch(
            AiFeature feature,
            Object identityMaterial,
            Set<Permission> requiredPermissions,
            T unavailableResult,
            Supplier<AiGenerationTaskResult<T>> task,
            long expectedRestrictionEpoch) {
        return startInternal(
                feature,
                identityMaterial,
                requiredPermissions,
                unavailableResult,
                task,
                expectedRestrictionEpoch,
                AiGenerationTerminalListener.NO_OP);
    }

    /**
     * Starts a restriction-fenced generation and reports its winning terminal transition to a
     * feature-owned durable-state listener.
     * @param feature AI feature adapter
     * @param identityMaterial bounded normalized material used only through its SHA-256 fingerprint
     * @param requiredPermissions permissions that must remain present for execution and disclosure
     * @param unavailableResult graceful feature result returned when initial permissions are absent
     * @param task feature-owned generation work
     * @param expectedRestrictionEpoch restriction epoch captured before durable preparation
     * @param terminalListener listener for the winning resolved, failed, or timed-out transition
     * @param <T> feature result type
     * @return accepted status, an existing coalesced status, or a resolved unavailable status
     */
    public <T> AiGenerationStatusDto startAtRestrictionEpoch(
            AiFeature feature,
            Object identityMaterial,
            Set<Permission> requiredPermissions,
            T unavailableResult,
            Supplier<AiGenerationTaskResult<T>> task,
            long expectedRestrictionEpoch,
            AiGenerationTerminalListener terminalListener) {
        return startInternal(
                feature,
                identityMaterial,
                requiredPermissions,
                unavailableResult,
                task,
                expectedRestrictionEpoch,
                terminalListener);
    }

    private <T> AiGenerationStatusDto startInternal(
            AiFeature feature,
            Object identityMaterial,
            Set<Permission> requiredPermissions,
            T unavailableResult,
            Supplier<AiGenerationTaskResult<T>> task,
            Long expectedRestrictionEpoch,
            AiGenerationTerminalListener terminalListener) {
        AiFeature requestedFeature = Objects.requireNonNull(feature, "feature");
        Set<Permission> required = Set.copyOf(
                Objects.requireNonNull(requiredPermissions, "requiredPermissions"));
        Objects.requireNonNull(unavailableResult, "unavailableResult");
        Objects.requireNonNull(task, "task");
        AiGenerationTerminalListener listener = Objects.requireNonNull(
                terminalListener, "terminalListener");
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        int userId = workspaceService.getCurrentUserId();
        Locale locale = LocaleContextHolder.getLocale();
        Instant now = clock.instant();
        long restrictionEpoch = aiRestrictionEpoch.current(workspaceId);
        if (expectedRestrictionEpoch != null && expectedRestrictionEpoch != restrictionEpoch) {
            throw new ConflictException("AI restrictions changed during generation preparation");
        }
        boolean initiallyAuthorized = workspaceService.permissionsFor(workspaceId, userId).containsAll(required);
        GenerationKey key = new GenerationKey(
                workspaceId,
                userId,
                requestedFeature,
                locale.toLanguageTag(),
                fingerprint(identityMaterial));

        if (!initiallyAuthorized) {
            SerializedResult result = serializeResult(unavailableResult);
            Set<Permission> disclosurePermissions = required.stream()
                    .filter(permission -> permission != Permission.AI_USE)
                    .collect(Collectors.toUnmodifiableSet());
            GenerationState resolved = GenerationState.resolvedUnavailable(
                    requestedFeature, workspaceId, userId, disclosurePermissions, restrictionEpoch,
                    now.plus(pollWindow), result.node());
            return toDto(resolved);
        }

        synchronized (stateLock) {
            purgeExpiredLocked(now);
            String activeHandle = activeHandles.get(key);
            GenerationState active = activeHandle == null ? null : generations.get(activeHandle);
            if (active != null && active.isActive()) {
                return toDto(active);
            }
            requireHandleCapacity();
            requireUserHandleCapacity(userId);
            requireUserCapacity(workspaceId, userId);
            requireResultCapacity(workspaceId, userId);
            GenerationState state = GenerationState.accepted(
                    requestedFeature,
                    workspaceId,
                    userId,
                    required,
                    restrictionEpoch,
                    now.plus(pollWindow),
                    key,
                    maxResultBytes,
                    listener);
            ScheduledFuture<?> timeout;
            try {
                timeout = scheduler.schedule(
                        () -> timeOut(state), maxLifetime.toMillis(), TimeUnit.MILLISECONDS);
            } catch (RejectedExecutionException exception) {
                throw new TooManyRequestsException(CAPACITY_MESSAGE);
            }
            Future<?> future;
            try {
                future = workers.submit(() -> execute(state, locale, task));
            } catch (RejectedExecutionException exception) {
                cancelTimer(timeout);
                throw new TooManyRequestsException(CAPACITY_MESSAGE);
            }
            state.future = future;
            state.timeout = timeout;
            generations.put(state.handle, state);
            activeHandles.put(key, state.handle);
            retainedResultBytes += state.retainedResultBytes;
            return toDto(state);
        }
    }

    /** Returns one status after exact scope, permission, feature-gate, and restriction revalidation. */
    public AiGenerationStatusDto status(String handle) {
        String normalizedHandle = normalizeHandle(handle);
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        int userId = workspaceService.getCurrentUserId();
        GenerationState state;
        synchronized (stateLock) {
            purgeExpiredLocked(clock.instant());
            state = generations.get(normalizedHandle);
            if (state == null || state.workspaceId != workspaceId || state.userId != userId) {
                throw unavailableHandle();
            }
        }
        boolean authorized = workspaceService.permissionsFor(workspaceId, userId)
                .containsAll(state.requiredPermissions);
        boolean epochCurrent = aiRestrictionEpoch.current(workspaceId) == state.restrictionEpoch;
        if (!authorized || !epochCurrent) {
            invalidate(state, authorized ? "restrictions_changed" : "access_revoked");
            throw unavailableHandle();
        }

        boolean featureValidated = false;
        while (true) {
            synchronized (stateLock) {
                if (generations.get(normalizedHandle) != state) {
                    throw unavailableHandle();
                }
                if (!state.requiresLiveFeatureGate() || featureValidated) {
                    return toDto(state);
                }
            }
            if (!aiFeatureGate.isAiUsable(state.feature)) {
                invalidate(state, "access_revoked");
                throw unavailableHandle();
            }
            featureValidated = true;
        }
    }

    private <T> void execute(
            GenerationState state,
            Locale locale,
            Supplier<AiGenerationTaskResult<T>> task) {
        if (!markRunning(state)) {
            return;
        }
        try {
            contextRunner.run(state.workspaceId, state.userId, locale, () -> {
                if (!workspaceService.permissionsFor(state.workspaceId, state.userId)
                        .containsAll(state.requiredPermissions)) {
                    fail(state, "access_revoked");
                    return;
                }
                if (aiRestrictionEpoch.current(state.workspaceId) != state.restrictionEpoch) {
                    fail(state, "restrictions_changed");
                    return;
                }
                var outcomeReference = new AtomicReference<AiGenerationTaskResult<T>>();
                aiRestrictionEpoch.runWithExpectedEgressEpoch(
                        state.workspaceId,
                        state.restrictionEpoch,
                        () -> outcomeReference.set(Objects.requireNonNull(task.get(), "task result")));
                AiGenerationTaskResult<T> outcome = Objects.requireNonNull(
                        outcomeReference.get(), "task result");
                switch (outcome.outcome()) {
                    case FAILED -> fail(state, outcome.reason());
                    case TIMED_OUT -> timeOut(state, outcome.reason());
                    case RESOLVED -> resolve(
                            state, serializeResult(outcome.result()), outcome.sensitive());
                }
            });
        } catch (RuntimeException exception) {
            fail(state, Thread.currentThread().isInterrupted() ? "cancelled" : "generation_failed");
        }
    }

    private boolean markRunning(GenerationState state) {
        synchronized (stateLock) {
            if (generations.get(state.handle) != state || state.status != Status.ACCEPTED) {
                return false;
            }
            state.status = Status.RUNNING;
            return true;
        }
    }

    private void resolve(GenerationState state, SerializedResult result, boolean sensitive) {
        ScheduledFuture<?> timeout;
        synchronized (stateLock) {
            if (generations.get(state.handle) != state || !state.isActive()) {
                return;
            }
            retainedResultBytes -= state.retainedResultBytes - result.bytes();
            state.retainedResultBytes = result.bytes();
            state.status = Status.RESOLVED;
            state.result = result.node();
            state.sensitive = sensitive;
            timeout = finishLocked(state);
        }
        cancelTimer(timeout);
        notifyTerminal(state, AiGenerationTaskResult.Outcome.RESOLVED, null);
    }

    private void fail(GenerationState state, String reason) {
        ScheduledFuture<?> timeout;
        synchronized (stateLock) {
            if (generations.get(state.handle) != state || !state.isActive()) {
                return;
            }
            state.status = Status.FAILED;
            state.reason = stableReason(reason, "generation_failed");
            releaseResultCapacityLocked(state);
            timeout = finishLocked(state);
        }
        cancelTimer(timeout);
        notifyTerminal(state, AiGenerationTaskResult.Outcome.FAILED, state.reason);
    }

    private void timeOut(GenerationState state) {
        timeOut(state, "generation_timeout");
    }

    private void timeOut(GenerationState state, String reason) {
        Future<?> future;
        synchronized (stateLock) {
            if (generations.get(state.handle) != state || !state.isActive()) {
                return;
            }
            state.status = Status.TIMED_OUT;
            state.reason = stableReason(reason, "generation_timeout");
            releaseResultCapacityLocked(state);
            finishLocked(state);
            future = state.future;
        }
        if (future != null) {
            future.cancel(true);
        }
        notifyTerminal(state, AiGenerationTaskResult.Outcome.TIMED_OUT, state.reason);
    }

    private void notifyTerminal(
            GenerationState state,
            AiGenerationTaskResult.Outcome outcome,
            String reason) {
        try {
            state.terminalListener.onTerminal(outcome, reason);
        } catch (RuntimeException exception) {
            return;
        }
    }

    private ScheduledFuture<?> finishLocked(GenerationState state) {
        if (state.key != null && state.handle.equals(activeHandles.get(state.key))) {
            activeHandles.remove(state.key);
        }
        ScheduledFuture<?> timeout = state.timeout;
        state.timeout = null;
        return timeout;
    }

    private void invalidate(GenerationState state, String reason) {
        Future<?> future;
        ScheduledFuture<?> timeout;
        boolean active;
        synchronized (stateLock) {
            if (!generations.remove(state.handle, state)) {
                return;
            }
            active = state.isActive();
            releaseResultCapacityLocked(state);
            if (state.key != null && state.handle.equals(activeHandles.get(state.key))) {
                activeHandles.remove(state.key);
            }
            future = state.future;
            timeout = state.timeout;
        }
        if (future != null) {
            future.cancel(true);
        }
        cancelTimer(timeout);
        if (active) {
            notifyTerminal(
                    state, AiGenerationTaskResult.Outcome.FAILED,
                    stableReason(reason, "access_revoked"));
        }
    }

    private void purgeExpired() {
        synchronized (stateLock) {
            purgeExpiredLocked(clock.instant());
        }
    }

    private void purgeExpiredLocked(Instant now) {
        var expired = generations.values().stream()
                .filter(state -> !state.expiresAt.isAfter(now))
                .toList();
        for (GenerationState state : expired) {
            generations.remove(state.handle);
            releaseResultCapacityLocked(state);
            if (state.key != null && state.handle.equals(activeHandles.get(state.key))) {
                activeHandles.remove(state.key);
            }
            if (state.future != null) {
                state.future.cancel(true);
            }
            if (state.timeout != null) {
                state.timeout.cancel(false);
            }
        }
    }

    private void requireHandleCapacity() {
        if (generations.size() >= maxHandles) {
            throw new TooManyRequestsException(CAPACITY_MESSAGE);
        }
    }

    private void requireUserCapacity(int workspaceId, int userId) {
        long active = generations.values().stream()
                .filter(state -> state.workspaceId == workspaceId
                        && state.userId == userId
                        && state.isActive())
                .count();
        if (active >= maxActivePerUser) {
            throw new TooManyRequestsException(CAPACITY_MESSAGE);
        }
    }

    private void requireUserHandleCapacity(int userId) {
        long handles = generations.values().stream()
                .filter(state -> state.userId == userId)
                .count();
        if (handles >= maxHandlesPerUser) {
            throw new TooManyRequestsException(CAPACITY_MESSAGE);
        }
    }

    private void requireResultCapacity(int workspaceId, int userId) {
        long workspaceBytes = generations.values().stream()
                .filter(state -> state.workspaceId == workspaceId)
                .mapToLong(state -> state.retainedResultBytes)
                .sum();
        long userBytes = generations.values().stream()
                .filter(state -> state.userId == userId)
                .mapToLong(state -> state.retainedResultBytes)
                .sum();
        if (exceedsCapacity(retainedResultBytes, maxRetainedResultBytes)
                || exceedsCapacity(workspaceBytes, maxRetainedResultBytesPerWorkspace)
                || exceedsCapacity(userBytes, maxRetainedResultBytesPerUser)) {
            throw new TooManyRequestsException(CAPACITY_MESSAGE);
        }
    }

    private boolean exceedsCapacity(long retainedBytes, long capacity) {
        return retainedBytes > capacity - maxResultBytes;
    }

    private void releaseResultCapacityLocked(GenerationState state) {
        retainedResultBytes -= state.retainedResultBytes;
        state.retainedResultBytes = 0;
    }

    private SerializedResult serializeResult(Object result) {
        try {
            JsonNode node = objectMapper.valueToTree(Objects.requireNonNull(result, "result"));
            int bytes = objectMapper.writeValueAsBytes(node).length;
            if (bytes > maxResultBytes) {
                throw new IllegalStateException("AI generation result exceeds the configured size limit");
            }
            return new SerializedResult(node, bytes);
        } catch (JacksonException | IllegalArgumentException exception) {
            throw new IllegalStateException("AI generation result could not be serialized", exception);
        }
    }

    private String fingerprint(Object identityMaterial) {
        try {
            byte[] serialized = objectMapper.writeValueAsBytes(
                    Objects.requireNonNull(identityMaterial, "identityMaterial"));
            if (serialized.length > maxResultBytes) {
                throw new IllegalArgumentException("AI generation identity exceeds the configured size limit");
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update("ai-generation-v1".getBytes(StandardCharsets.UTF_8));
            digest.update(serialized);
            return HexFormat.of().formatHex(digest.digest());
        } catch (JacksonException exception) {
            throw new IllegalArgumentException("AI generation identity could not be serialized", exception);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private AiGenerationStatusDto toDto(GenerationState state) {
        return new AiGenerationStatusDto(
                state.handle,
                state.feature.wireKey(),
                state.status.wire,
                state.result,
                state.reason,
                pollInterval.toMillis(),
                Math.max(0, Duration.between(clock.instant(), state.expiresAt).toMillis()),
                state.expiresAt.toString());
    }

    private static String normalizeHandle(String handle) {
        if (handle == null || handle.length() != 36) {
            throw unavailableHandle();
        }
        try {
            return UUID.fromString(handle).toString();
        } catch (IllegalArgumentException exception) {
            throw unavailableHandle();
        }
    }

    private static String stableReason(String reason, String fallback) {
        return reason == null || reason.isBlank() ? fallback : reason;
    }

    private static void cancelTimer(ScheduledFuture<?> timer) {
        if (timer != null) {
            timer.cancel(false);
        }
    }

    private static ResourceNotFoundException unavailableHandle() {
        return new ResourceNotFoundException("AI generation not found");
    }

    private static int positive(int value, String name) {
        if (value <= 0) {
            throw new IllegalStateException("AI generation " + name + " must be positive");
        }
        return value;
    }

    private static long positive(long value, String name) {
        if (value <= 0) {
            throw new IllegalStateException("AI generation " + name + " must be positive");
        }
        return value;
    }

    private static Duration positiveDuration(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalStateException("AI generation " + name + " must be positive");
        }
        return value;
    }

    @PreDestroy
    public void shutdown() {
        workers.shutdownNow();
        scheduler.shutdownNow();
        synchronized (stateLock) {
            generations.clear();
            activeHandles.clear();
            retainedResultBytes = 0;
        }
    }

    private enum Status {
        ACCEPTED("accepted"),
        RUNNING("running"),
        RESOLVED("resolved"),
        FAILED("failed"),
        TIMED_OUT("timed_out");

        private final String wire;

        Status(String wire) {
            this.wire = wire;
        }
    }

    private record GenerationKey(
            int workspaceId,
            int userId,
            AiFeature feature,
            String locale,
            String fingerprint) {
    }

    private record SerializedResult(JsonNode node, int bytes) {
    }

    private static final class GenerationState {
        private final String handle;
        private final AiFeature feature;
        private final int workspaceId;
        private final int userId;
        private final Set<Permission> requiredPermissions;
        private final long restrictionEpoch;
        private final Instant expiresAt;
        private final GenerationKey key;
        private final AiGenerationTerminalListener terminalListener;
        private Status status;
        private JsonNode result;
        private String reason;
        private boolean sensitive;
        private int retainedResultBytes;
        private Future<?> future;
        private ScheduledFuture<?> timeout;

        private GenerationState(
                AiFeature feature,
                int workspaceId,
                int userId,
                Set<Permission> requiredPermissions,
                long restrictionEpoch,
                Instant expiresAt,
                GenerationKey key,
                Status status,
                JsonNode result,
                int retainedResultBytes,
                AiGenerationTerminalListener terminalListener) {
            handle = UUID.randomUUID().toString();
            this.feature = feature;
            this.workspaceId = workspaceId;
            this.userId = userId;
            this.requiredPermissions = requiredPermissions;
            this.restrictionEpoch = restrictionEpoch;
            this.expiresAt = expiresAt;
            this.key = key;
            this.terminalListener = terminalListener;
            this.status = status;
            this.result = result;
            this.retainedResultBytes = retainedResultBytes;
        }

        private static GenerationState accepted(
                AiFeature feature,
                int workspaceId,
                int userId,
                Set<Permission> requiredPermissions,
                long restrictionEpoch,
                Instant expiresAt,
                GenerationKey key,
                int retainedResultBytes,
                AiGenerationTerminalListener terminalListener) {
            return new GenerationState(
                    feature, workspaceId, userId, requiredPermissions, restrictionEpoch,
                    expiresAt, key, Status.ACCEPTED, null, retainedResultBytes, terminalListener);
        }

        private static GenerationState resolvedUnavailable(
                AiFeature feature,
                int workspaceId,
                int userId,
                Set<Permission> requiredPermissions,
                long restrictionEpoch,
                Instant expiresAt,
                JsonNode result) {
            return new GenerationState(
                    feature, workspaceId, userId, requiredPermissions, restrictionEpoch,
                    expiresAt, null, Status.RESOLVED, result, 0,
                    AiGenerationTerminalListener.NO_OP);
        }

        private boolean isActive() {
            return status == Status.ACCEPTED || status == Status.RUNNING;
        }

        private boolean requiresLiveFeatureGate() {
            return status == Status.RESOLVED
                    && sensitive
                    && requiredPermissions.contains(Permission.AI_USE);
        }
    }
}
