package ooo.klae.connex.backend.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.dto.AiGenerationStatusDto;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.exceptions.TooManyRequestsException;
import ooo.klae.connex.backend.services.WorkspaceService;
import ooo.klae.connex.backend.tenant.Permission;
import tools.jackson.databind.json.JsonMapper;

class AiGenerationServiceTest {
    private WorkspaceService workspaceService;
    private AiFeatureGate aiFeatureGate;
    private AiRestrictionEpoch aiRestrictionEpoch;
    private AiGenerationContextRunner contextRunner;
    private AtomicInteger workspaceId;
    private AtomicInteger userId;
    private AtomicLong restrictionEpoch;
    private AtomicReference<Set<Permission>> permissions;
    private AiGenerationService service;

    @BeforeEach
    void setUp() {
        workspaceService = mock(WorkspaceService.class);
        aiFeatureGate = mock(AiFeatureGate.class);
        aiRestrictionEpoch = mock(AiRestrictionEpoch.class);
        contextRunner = mock(AiGenerationContextRunner.class);
        workspaceId = new AtomicInteger(7);
        userId = new AtomicInteger(42);
        restrictionEpoch = new AtomicLong(3);
        permissions = new AtomicReference<>(Set.of(Permission.AI_USE, Permission.REPORT_READ));

        lenient().when(workspaceService.getCurrentWorkspaceId()).thenAnswer(ignored -> workspaceId.get());
        lenient().when(workspaceService.getCurrentUserId()).thenAnswer(ignored -> userId.get());
        lenient().when(workspaceService.permissionsFor(anyInt(), anyInt()))
                .thenAnswer(ignored -> permissions.get());
        lenient().when(aiRestrictionEpoch.current(anyInt())).thenAnswer(ignored -> restrictionEpoch.get());
        lenient().doAnswer(invocation -> {
            invocation.getArgument(2, Runnable.class).run();
            return null;
        }).when(aiRestrictionEpoch).runWithExpectedEgressEpoch(
                anyInt(), anyLong(), any(Runnable.class));
        lenient().when(aiFeatureGate.isAiUsable(any())).thenReturn(true);
        doAnswer(invocation -> {
            invocation.getArgument(3, Runnable.class).run();
            return null;
        }).when(contextRunner).run(anyInt(), anyInt(), any(Locale.class), any(Runnable.class));

        service = new AiGenerationService(
                properties(Duration.ofSeconds(2)),
                workspaceService,
                aiFeatureGate,
                aiRestrictionEpoch,
                contextRunner,
                JsonMapper.builder().build(),
                Clock.systemUTC());
    }

    @AfterEach
    void tearDown() {
        service.shutdown();
    }

    @Test
    void acceptedTransitionsThroughRunningToResolved() throws Exception {
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AiGenerationStatusDto accepted = service.start(
                AiFeature.DEAL_BRIEF,
                "deal-17",
                Set.of(Permission.AI_USE),
                "unavailable",
                () -> {
                    running.countDown();
                    await(release);
                    return AiGenerationTaskResult.resolved("ready");
                });

        assertEquals("accepted", accepted.status());
        assertTrue(accepted.pollWindowMs() > 0);
        assertTrue(running.await(2, TimeUnit.SECONDS));
        assertEquals("running", service.status(accepted.handle()).status());
        release.countDown();

        AiGenerationStatusDto resolved = awaitStatus(accepted.handle(), "resolved");
        assertNotNull(resolved.result());
        assertEquals("ready", resolved.result().asString());
    }

    @Test
    void providerFailureIsSurfacedAsFailed() {
        AiGenerationStatusDto accepted = service.start(
                AiFeature.DEAL_RISK_RATIONALE,
                "deal-29",
                Set.of(Permission.AI_USE),
                "unavailable",
                () -> AiGenerationTaskResult.failed("provider_error"));

        AiGenerationStatusDto failed = awaitStatus(accepted.handle(), "failed");

        assertEquals("provider_error", failed.reason());
    }

    @Test
    void maximumLifetimeIsSurfacedAsTimedOut() throws Exception {
        service.shutdown();
        service = new AiGenerationService(
                properties(Duration.ofMillis(100)),
                workspaceService,
                aiFeatureGate,
                aiRestrictionEpoch,
                contextRunner,
                JsonMapper.builder().build(),
                Clock.systemUTC());
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch neverReleased = new CountDownLatch(1);
        AiGenerationStatusDto accepted = service.start(
                AiFeature.INTRO_RATIONALE,
                "pair-11-12",
                Set.of(Permission.AI_USE),
                "unavailable",
                () -> {
                    running.countDown();
                    await(neverReleased);
                    return AiGenerationTaskResult.resolved("late");
                });

        assertTrue(running.await(2, TimeUnit.SECONDS));
        AiGenerationStatusDto timedOut = awaitStatus(accepted.handle(), "timed_out");

        assertEquals("generation_timeout", timedOut.reason());
    }

    @Test
    void pollAfterPermissionLossIsRefusedAndInvalidated() throws Exception {
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AiGenerationStatusDto accepted = service.start(
                AiFeature.REPORT_NARRATIVE,
                "report-9",
                Set.of(Permission.AI_USE, Permission.REPORT_READ),
                "unavailable",
                () -> {
                    running.countDown();
                    await(release);
                    return AiGenerationTaskResult.resolved("ready");
                });
        assertTrue(running.await(2, TimeUnit.SECONDS));
        permissions.set(Set.of(Permission.REPORT_READ));

        assertThrows(ResourceNotFoundException.class, () -> service.status(accepted.handle()));

        permissions.set(Set.of(Permission.AI_USE, Permission.REPORT_READ));
        assertThrows(ResourceNotFoundException.class, () -> service.status(accepted.handle()));
    }

    @Test
    void immediateUnavailableResultDoesNotConsumeAPollingHandle() {
        permissions.set(Set.of(Permission.REPORT_READ));
        AiGenerationStatusDto unavailable = service.start(
                AiFeature.REPORT_NARRATIVE,
                "report-9",
                Set.of(Permission.AI_USE, Permission.REPORT_READ),
                "deterministic-report",
                () -> AiGenerationTaskResult.resolved("unused"));

        assertEquals("resolved", unavailable.status());
        assertEquals("deterministic-report", unavailable.result().asString());
        assertThrows(ResourceNotFoundException.class, () -> service.status(unavailable.handle()));
    }

    @Test
    void goalReadLossRefusesAnAttainmentReportHandle() throws Exception {
        permissions.set(Set.of(Permission.AI_USE, Permission.REPORT_READ, Permission.GOAL_READ));
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AiGenerationStatusDto accepted = service.start(
                AiFeature.REPORT_NARRATIVE,
                "attainment-report",
                Set.of(Permission.AI_USE, Permission.REPORT_READ, Permission.GOAL_READ),
                "unavailable",
                () -> {
                    running.countDown();
                    await(release);
                    return AiGenerationTaskResult.resolved("ready");
                });
        assertTrue(running.await(2, TimeUnit.SECONDS));
        permissions.set(Set.of(Permission.AI_USE, Permission.REPORT_READ));

        assertThrows(ResourceNotFoundException.class, () -> service.status(accepted.handle()));
    }

    @Test
    void restrictionChangeBeforeTaskExecutionSkipsTheBillableTask() throws Exception {
        CountDownLatch contextEntered = new CountDownLatch(1);
        CountDownLatch releaseContext = new CountDownLatch(1);
        CountDownLatch contextExited = new CountDownLatch(1);
        AtomicInteger billableAttempts = new AtomicInteger();
        doAnswer(invocation -> {
            contextEntered.countDown();
            await(releaseContext);
            invocation.getArgument(3, Runnable.class).run();
            contextExited.countDown();
            return null;
        }).when(contextRunner).run(anyInt(), anyInt(), any(Locale.class), any(Runnable.class));
        AiGenerationStatusDto accepted = service.start(
                AiFeature.DEAL_BRIEF,
                "deal-17",
                Set.of(Permission.AI_USE),
                "unavailable",
                () -> {
                    billableAttempts.incrementAndGet();
                    return AiGenerationTaskResult.resolved("ready");
                });
        assertTrue(contextEntered.await(2, TimeUnit.SECONDS));
        restrictionEpoch.incrementAndGet();
        releaseContext.countDown();

        assertTrue(contextExited.await(2, TimeUnit.SECONDS));
        assertEquals(0, billableAttempts.get());
        assertThrows(ResourceNotFoundException.class, () -> service.status(accepted.handle()));
    }

    @Test
    void preparedRestrictionEpochMismatchIsRejectedBeforeQueueing() {
        AtomicInteger billableAttempts = new AtomicInteger();
        restrictionEpoch.set(4);

        assertThrows(ConflictException.class, () -> service.startAtRestrictionEpoch(
                AiFeature.REPORT_NARRATIVE,
                "report-9",
                Set.of(Permission.AI_USE, Permission.REPORT_READ),
                "unavailable",
                () -> {
                    billableAttempts.incrementAndGet();
                    return AiGenerationTaskResult.resolved("ready");
                },
                3));
        assertEquals(0, billableAttempts.get());
    }

    @Test
    void featureGateRevocationInvalidatesASensitiveResolvedResult() {
        AiGenerationStatusDto accepted = service.start(
                AiFeature.DEAL_BRIEF,
                "deal-17",
                Set.of(Permission.AI_USE),
                "unavailable",
                () -> AiGenerationTaskResult.resolved("ready"));
        awaitStatus(accepted.handle(), "resolved");
        when(aiFeatureGate.isAiUsable(AiFeature.DEAL_BRIEF)).thenReturn(false);

        assertThrows(ResourceNotFoundException.class, () -> service.status(accepted.handle()));
    }

    @Test
    void terminalHandlesRemainBoundedPerUserAcrossWorkspaces() {
        service.shutdown();
        AiProperties properties = properties(Duration.ofSeconds(2));
        properties.setGenerationMaxHandlesPerUser(2);
        service = new AiGenerationService(
                properties,
                workspaceService,
                aiFeatureGate,
                aiRestrictionEpoch,
                contextRunner,
                JsonMapper.builder().build(),
                Clock.systemUTC());
        AiGenerationStatusDto first = service.start(
                AiFeature.DEAL_BRIEF,
                "deal-17",
                Set.of(Permission.AI_USE),
                "unavailable",
                () -> AiGenerationTaskResult.unavailable("first"));
        AiGenerationStatusDto second = service.start(
                AiFeature.DEAL_BRIEF,
                "deal-18",
                Set.of(Permission.AI_USE),
                "unavailable",
                () -> AiGenerationTaskResult.unavailable("second"));
        awaitStatus(first.handle(), "resolved");
        awaitStatus(second.handle(), "resolved");

        workspaceId.set(8);
        assertThrows(TooManyRequestsException.class, () -> service.start(
                AiFeature.DEAL_BRIEF,
                "deal-19",
                Set.of(Permission.AI_USE),
                "unavailable",
                () -> AiGenerationTaskResult.unavailable("third")));
    }

    @Test
    void resultCapacityIsReservedBeforeBillableWorkAndFairPerUser() throws Exception {
        service.shutdown();
        AiProperties properties = properties(Duration.ofSeconds(2));
        properties.setGenerationMaxResultBytes(64);
        properties.setGenerationMaxRetainedResultBytes(128);
        properties.setGenerationMaxRetainedResultBytesPerWorkspace(128);
        properties.setGenerationMaxRetainedResultBytesPerUser(64);
        service = new AiGenerationService(
                properties,
                workspaceService,
                aiFeatureGate,
                aiRestrictionEpoch,
                contextRunner,
                JsonMapper.builder().build(),
                Clock.systemUTC());
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger rejectedAttempts = new AtomicInteger();
        AiGenerationStatusDto first = service.start(
                AiFeature.DEAL_BRIEF,
                "deal-17",
                Set.of(Permission.AI_USE),
                "unavailable",
                () -> {
                    running.countDown();
                    await(release);
                    return AiGenerationTaskResult.resolved("ready");
                });
        assertTrue(running.await(2, TimeUnit.SECONDS));

        workspaceId.set(8);
        assertThrows(TooManyRequestsException.class, () -> service.start(
                AiFeature.DEAL_BRIEF,
                "deal-18",
                Set.of(Permission.AI_USE),
                "unavailable",
                () -> {
                    rejectedAttempts.incrementAndGet();
                    return AiGenerationTaskResult.resolved("must-not-run");
                }));
        assertEquals(0, rejectedAttempts.get());

        userId.set(43);
        AiGenerationStatusDto otherUser = service.start(
                AiFeature.DEAL_BRIEF,
                "deal-18",
                Set.of(Permission.AI_USE),
                "unavailable",
                () -> AiGenerationTaskResult.resolved("other-user"));
        assertEquals("resolved", awaitStatus(otherUser.handle(), "resolved").status());

        workspaceId.set(7);
        userId.set(42);
        release.countDown();
        assertEquals("resolved", awaitStatus(first.handle(), "resolved").status());
    }

    @Test
    void schedulerRejectionAfterShutdownDoesNotInvokeBillableWork() {
        service.shutdown();
        AtomicInteger billableAttempts = new AtomicInteger();

        assertThrows(TooManyRequestsException.class, () -> service.start(
                AiFeature.DEAL_BRIEF,
                "deal-17",
                Set.of(Permission.AI_USE),
                "unavailable",
                () -> {
                    billableAttempts.incrementAndGet();
                    return AiGenerationTaskResult.resolved("must-not-run");
                }));

        assertEquals(0, billableAttempts.get());
    }

    @Test
    void rejectedWorkerSubmissionIsNeverPublishedToAConcurrentDuplicate() throws Exception {
        ThreadPoolExecutor workers = workerExecutor();
        CountDownLatch rejectionEntered = new CountDownLatch(1);
        CountDownLatch releaseRejection = new CountDownLatch(1);
        CountDownLatch duplicateStarted = new CountDownLatch(1);
        AtomicInteger billableAttempts = new AtomicInteger();
        workers.setRejectedExecutionHandler((task, executor) -> {
            rejectionEntered.countDown();
            await(releaseRejection);
            throw new RejectedExecutionException("worker unavailable");
        });
        workers.shutdownNow();
        var generationTask = (java.util.function.Supplier<AiGenerationTaskResult<String>>) () -> {
            billableAttempts.incrementAndGet();
            return AiGenerationTaskResult.resolved("must-not-run");
        };

        CompletableFuture<AiGenerationStatusDto> initiating = CompletableFuture.supplyAsync(() ->
                service.start(
                        AiFeature.DEAL_BRIEF,
                        "deal-17",
                        Set.of(Permission.AI_USE),
                        "unavailable",
                        generationTask));
        assertTrue(rejectionEntered.await(2, TimeUnit.SECONDS));
        CompletableFuture<AiGenerationStatusDto> duplicate = CompletableFuture.supplyAsync(() -> {
            duplicateStarted.countDown();
            return service.start(
                    AiFeature.DEAL_BRIEF,
                    "deal-17",
                    Set.of(Permission.AI_USE),
                    "unavailable",
                    generationTask);
        });
        assertTrue(duplicateStarted.await(2, TimeUnit.SECONDS));
        releaseRejection.countDown();

        ExecutionException initiatingFailure = assertThrows(
                ExecutionException.class,
                () -> initiating.get(2, TimeUnit.SECONDS));
        ExecutionException duplicateFailure = assertThrows(
                ExecutionException.class,
                () -> duplicate.get(2, TimeUnit.SECONDS));
        assertInstanceOf(TooManyRequestsException.class, initiatingFailure.getCause());
        assertInstanceOf(TooManyRequestsException.class, duplicateFailure.getCause());
        assertEquals(0, billableAttempts.get());
    }

    @Test
    void crossWorkspaceAndCrossUserPollsAreRefused() throws Exception {
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AiGenerationStatusDto accepted = service.start(
                AiFeature.DEAL_BRIEF,
                "deal-17",
                Set.of(Permission.AI_USE),
                "unavailable",
                () -> {
                    running.countDown();
                    await(release);
                    return AiGenerationTaskResult.resolved("ready");
                });
        assertTrue(running.await(2, TimeUnit.SECONDS));

        workspaceId.set(8);
        assertThrows(ResourceNotFoundException.class, () -> service.status(accepted.handle()));
        workspaceId.set(7);
        userId.set(43);
        assertThrows(ResourceNotFoundException.class, () -> service.status(accepted.handle()));
        userId.set(42);
        release.countDown();
        assertEquals("resolved", awaitStatus(accepted.handle(), "resolved").status());
    }

    @Test
    void restrictionChangeInvalidatesAHandle() throws Exception {
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AiGenerationStatusDto accepted = service.start(
                AiFeature.DEAL_BRIEF,
                "deal-17",
                Set.of(Permission.AI_USE),
                "unavailable",
                () -> {
                    running.countDown();
                    await(release);
                    return AiGenerationTaskResult.resolved("ready");
                });
        assertTrue(running.await(2, TimeUnit.SECONDS));
        restrictionEpoch.incrementAndGet();

        assertThrows(ResourceNotFoundException.class, () -> service.status(accepted.handle()));
    }

    @Test
    void concurrentDuplicateStartsInvokeBillableTaskOnce() throws Exception {
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger billableAttempts = new AtomicInteger();
        var task = (java.util.function.Supplier<AiGenerationTaskResult<String>>) () -> {
            billableAttempts.incrementAndGet();
            running.countDown();
            await(release);
            return AiGenerationTaskResult.resolved("ready");
        };

        AiGenerationStatusDto first = service.start(
                AiFeature.DEAL_BRIEF,
                "deal-17",
                Set.of(Permission.AI_USE),
                "unavailable",
                task);
        assertTrue(running.await(2, TimeUnit.SECONDS));
        AiGenerationStatusDto second = service.start(
                AiFeature.DEAL_BRIEF,
                "deal-17",
                Set.of(Permission.AI_USE),
                "unavailable",
                task);

        assertEquals(first.handle(), second.handle());
        assertEquals(1, billableAttempts.get());
        assertEquals("running", service.status(first.handle()).status());
        assertEquals("running", service.status(first.handle()).status());
        assertEquals(1, billableAttempts.get());
        release.countDown();
        awaitStatus(first.handle(), "resolved");
        assertEquals(1, billableAttempts.get());
    }

    @Test
    void frozenReportResultIsRetainedWithoutReenteringAssemblyDuringPolls() {
        AtomicInteger figureAssemblies = new AtomicInteger();
        AtomicInteger narrativeInvocations = new AtomicInteger();
        FrozenReport prepared = new FrozenReport(
                figureAssemblies.incrementAndGet(), "frozen-figures", null);
        AiGenerationStatusDto accepted = service.start(
                AiFeature.REPORT_NARRATIVE,
                prepared,
                Set.of(Permission.AI_USE, Permission.REPORT_READ),
                prepared,
                () -> {
                    narrativeInvocations.incrementAndGet();
                    return AiGenerationTaskResult.resolved(
                            new FrozenReport(prepared.assembly(), prepared.figures(), "narrative"));
                });

        AiGenerationStatusDto resolved = awaitStatus(accepted.handle(), "resolved");
        assertEquals("frozen-figures", resolved.result().get("figures").asString());
        assertEquals("narrative", resolved.result().get("narrative").asString());
        assertEquals("resolved", service.status(accepted.handle()).status());
        assertEquals("resolved", service.status(accepted.handle()).status());
        assertEquals(1, figureAssemblies.get());
        assertEquals(1, narrativeInvocations.get());
    }

    private AiGenerationStatusDto awaitStatus(String handle, String expected) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        AiGenerationStatusDto current = service.status(handle);
        while (!expected.equals(current.status()) && System.nanoTime() < deadline) {
            Thread.onSpinWait();
            current = service.status(handle);
        }
        assertEquals(expected, current.status());
        return current;
    }

    private ThreadPoolExecutor workerExecutor() throws ReflectiveOperationException {
        Field field = AiGenerationService.class.getDeclaredField("workers");
        field.setAccessible(true);
        return ThreadPoolExecutor.class.cast(field.get(service));
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private static AiProperties properties(Duration lifetime) {
        AiProperties properties = new AiProperties();
        properties.setGenerationWorkerThreads(2);
        properties.setGenerationQueueCapacity(2);
        properties.setGenerationMaxHandles(16);
        properties.setGenerationMaxActivePerUser(4);
        properties.setGenerationMaxHandlesPerUser(8);
        properties.setGenerationMaxResultBytes(4096);
        properties.setGenerationMaxRetainedResultBytes(65536);
        properties.setGenerationMaxRetainedResultBytesPerWorkspace(32768);
        properties.setGenerationMaxRetainedResultBytesPerUser(16384);
        properties.setGenerationMaxLifetime(lifetime);
        properties.setGenerationPollWindow(Duration.ofSeconds(5));
        properties.setGenerationPollInterval(Duration.ofMillis(10));
        return properties;
    }

    private record FrozenReport(int assembly, String figures, String narrative) {
    }
}
