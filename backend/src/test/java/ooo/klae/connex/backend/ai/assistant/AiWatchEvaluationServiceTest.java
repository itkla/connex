package ooo.klae.connex.backend.ai.assistant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;

import ooo.klae.connex.backend.ai.AiFeature;
import ooo.klae.connex.backend.ai.AiFeatureGate;
import ooo.klae.connex.backend.ai.AiGenerationContextRunner;
import ooo.klae.connex.backend.beans.AiWatch;
import ooo.klae.connex.backend.beans.Notification;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.dto.DealRiskDto;
import ooo.klae.connex.backend.dto.DealRiskFactor;
import ooo.klae.connex.backend.dto.RelationshipTemperatureDto;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.AiWatchMapper;
import ooo.klae.connex.backend.mappers.TaskMapper;
import ooo.klae.connex.backend.notifications.NotificationDelivery;
import ooo.klae.connex.backend.services.DealRiskService;
import ooo.klae.connex.backend.services.OrganizationWorkspaceScopeControlAccess;
import ooo.klae.connex.backend.services.OrganizationWorkspaceScopeControlOperations;
import ooo.klae.connex.backend.services.ScoringService;
import ooo.klae.connex.backend.services.UserService;
import ooo.klae.connex.backend.services.WorkspaceService;
import tools.jackson.databind.json.JsonMapper;

/**
 * Pins that the model never decides a watch fired, that every firing states its evidence, and that
 * repeated evaluation cannot flood a member.
 */
class AiWatchEvaluationServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-24T07:00:00Z");

    private final AiWatchMapper watchMapper = mock(AiWatchMapper.class);
    private final AiFeatureGate featureGate = mock(AiFeatureGate.class);
    private final AiWatchSubjectReader subjectReader = mock(AiWatchSubjectReader.class);
    private final TaskMapper taskMapper = mock(TaskMapper.class);
    private final ScoringService scoringService = mock(ScoringService.class);
    private final DealRiskService dealRiskService = mock(DealRiskService.class);
    private final OrganizationWorkspaceScopeControlAccess workspaceScopeControlAccess =
            mock(OrganizationWorkspaceScopeControlAccess.class);
    private final NotificationDelivery notificationDelivery = mock(NotificationDelivery.class);
    private final AiGenerationContextRunner contextRunner = mock(AiGenerationContextRunner.class);
    private final UserService userService = mock(UserService.class);
    private final WorkspaceService workspaceService = mock(WorkspaceService.class);
    private final PlatformTransactionManager transactionManager =
            mock(PlatformTransactionManager.class);

    private final AiWatchEvaluationService service = serviceAt(NOW);

    /** The same collaborators on a different wall clock, so a later cooldown window can be run. */
    private AiWatchEvaluationService serviceAt(Instant instant) {
        return new AiWatchEvaluationService(
                watchMapper, featureGate, subjectReader, taskMapper, scoringService, dealRiskService,
                workspaceScopeControlAccess, notificationDelivery, contextRunner, userService,
                workspaceService, JsonMapper.builder().build(), transactionManager,
                Clock.fixed(instant, ZoneOffset.UTC));
    }

    @BeforeEach
    void installIdentity() {
        User owner = new User();
        owner.setId(11);
        owner.setLocale("en");
        when(userService.getActiveWorkspaceUser(eq(7), eq(11))).thenReturn(owner);
        when(workspaceService.getCurrentAnalyticsTimezone()).thenReturn("UTC");
        when(featureGate.isFeatureGoverned(AiFeature.ASSISTANT_CHAT)).thenReturn(true);
        when(workspaceScopeControlAccess.getForWorkspace(7)).thenReturn(
                new OrganizationWorkspaceScopeControlOperations.WorkspaceScope(1, List.of(7), "[7]"));
        doAnswer(invocation -> {
            invocation.getArgument(3, Runnable.class).run();
            return null;
        }).when(contextRunner).run(anyInt(), anyInt(), any(), any(Runnable.class));
        when(watchMapper.claimFiring(anyInt(), anyInt(), anyString(), anyString(), anyString()))
                .thenReturn(1);
    }

    private static AiWatch watch(AiWatchType type, String kind, int subjectId) {
        AiWatch watch = new AiWatch();
        watch.setId(5);
        watch.setWorkspaceId(7);
        watch.setOwnerUserId(11);
        watch.setWatchType(type.key());
        watch.setSubjectKind(kind);
        watch.setSubjectId(subjectId);
        watch.setStatus("active");
        watch.setCooldownDays(7);
        return watch;
    }

    private static RelationshipTemperatureDto temperature(
            int id, String band, Integer daysSinceTouch) {
        return new RelationshipTemperatureDto(
                id, 20, band, "cooling", "2026-07-01 09:00:00", daysSinceTouch,
                2, null, null, "warmth-v3", NOW);
    }

    private void subjectIsReadable(String label) {
        when(subjectReader.label(anyString(), anyInt())).thenReturn(Optional.of(label));
    }

    private Notification firedNotification() {
        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationDelivery).deliver(captor.capture());
        return captor.getValue();
    }

    @Test
    void aCoolingWatchFiresOnlyOnceTheAuthoritativeBandReachesTheDeclaredOne() {
        subjectIsReadable("Aiko Tanaka");
        AiWatch cooling = watch(AiWatchType.RELATIONSHIP_COOLING, "person", 42);
        cooling.setThresholdBand("cold");
        when(scoringService.scoreContacts(7, Set.of(42)))
                .thenReturn(List.of(temperature(42, "cool", 40)));

        assertEquals(AiWatchEvaluationService.Outcome.QUIET, service.evaluate(cooling));
        verify(notificationDelivery, never()).deliver(any());
        verify(watchMapper).recordEvaluated(eq(7), eq(5), anyString());

        when(scoringService.scoreContacts(7, Set.of(42)))
                .thenReturn(List.of(temperature(42, "cold", 90)));
        assertEquals(AiWatchEvaluationService.Outcome.FIRED, service.evaluate(cooling));

        Notification fired = firedNotification();
        assertEquals(AiWatchEvaluationService.NOTIFICATION_TYPE, fired.getType());
        assertEquals("ai.watch:5:band:cold:2026-08-24", fired.getDedupeKey());
        assertEquals("/records/contacts/42", fired.getActionUrl());
        assertEquals("Aiko Tanaka", fired.getSourceLabel());
        assertTrue(fired.getData().contains("\"band\":\"cold\""),
                "The firing must carry the authoritative band as evidence");
        assertTrue(fired.getData().contains("warmth-v3"),
                "The firing must name the model version its evidence came from");
    }

    @Test
    void aSilenceWatchFiresOnTheDeclaredDayCountAndKeepsAStableStateToken() {
        subjectIsReadable("Acme");
        AiWatch quiet = watch(AiWatchType.NO_INTERACTION, "company", 8);
        quiet.setThresholdDays(30);
        when(scoringService.scoreCompanies(7, Set.of(8)))
                .thenReturn(List.of(temperature(8, "cool", 29)));

        assertEquals(AiWatchEvaluationService.Outcome.QUIET, service.evaluate(quiet));

        when(scoringService.scoreCompanies(7, Set.of(8)))
                .thenReturn(List.of(temperature(8, "cool", 31)));
        assertEquals(AiWatchEvaluationService.Outcome.FIRED, service.evaluate(quiet));

        Notification fired = firedNotification();
        assertEquals("ai.watch:5:quiet:30:2026-08-24", fired.getDedupeKey());
        assertEquals("/records/companies/8", fired.getActionUrl());
        assertTrue(fired.getData().contains("\"daysSinceTouch\":31"));
    }

    @Test
    void aCommitmentWatchReadsTheTaskProjectionAndKeysItsStateOnTheOldestOverdueDate() {
        subjectIsReadable("Acme renewal");
        AiWatch overdue = watch(AiWatchType.COMMITMENT_OVERDUE, "deal", 3);
        when(taskMapper.countOverdueForSubject(eq(7), eq(null), eq(null), eq(3), any(), any()))
                .thenReturn(new AiWatchOverdueCommitments(0, null));

        assertEquals(AiWatchEvaluationService.Outcome.QUIET, service.evaluate(overdue));

        when(taskMapper.countOverdueForSubject(eq(7), eq(null), eq(null), eq(3), any(), any()))
                .thenReturn(new AiWatchOverdueCommitments(2, "2026-08-10"));
        assertEquals(AiWatchEvaluationService.Outcome.FIRED, service.evaluate(overdue));

        Notification fired = firedNotification();
        assertEquals("ai.watch:5:overdue:2026-08-10:2026-08-24", fired.getDedupeKey());
        assertEquals("/records/deals/3", fired.getActionUrl());
        assertTrue(fired.getData().contains("\"overdueCount\":2"));
    }

    @Test
    void aDealRiskWatchRepeatsTheDeterministicModelsLevelAndFactorCodes() {
        subjectIsReadable("Acme renewal");
        AiWatch risk = watch(AiWatchType.DEAL_RISK_THRESHOLD, "deal", 3);
        risk.setThresholdLevel("high");
        when(dealRiskService.assessDeal(7, 3)).thenReturn(new DealRiskDto(
                3, null, null, "medium", 40,
                List.of(new DealRiskFactor("stalled", "medium", Map.of())),
                "2026-08-24 06:00:00"));

        assertEquals(AiWatchEvaluationService.Outcome.QUIET, service.evaluate(risk));

        when(dealRiskService.assessDeal(7, 3)).thenReturn(new DealRiskDto(
                3, null, null, "high", 80,
                List.of(new DealRiskFactor("close_overdue", "high", Map.of())),
                "2026-08-24 06:30:00"));
        assertEquals(AiWatchEvaluationService.Outcome.FIRED, service.evaluate(risk));

        Notification fired = firedNotification();
        assertEquals("ai.watch:5:risk:high:2026-08-24", fired.getDedupeKey());
        assertEquals("warning", fired.getSeverity());
        assertTrue(fired.getData().contains("close_overdue"),
                "The firing must name the risk model's own factor codes");
    }

    @Test
    void aRepeatedEvaluationInsideTheCooldownIsClaimedByNobodyAndNotifiesNobody() {
        subjectIsReadable("Aiko Tanaka");
        AiWatch cooling = watch(AiWatchType.RELATIONSHIP_COOLING, "person", 42);
        cooling.setThresholdBand("cool");
        when(scoringService.scoreContacts(7, Set.of(42)))
                .thenReturn(List.of(temperature(42, "cool", 40)));
        when(watchMapper.claimFiring(anyInt(), anyInt(), anyString(), anyString(), anyString()))
                .thenReturn(0);

        assertEquals(AiWatchEvaluationService.Outcome.QUIET, service.evaluate(cooling));

        verify(notificationDelivery, never()).deliver(any());
        verify(watchMapper).recordEvaluated(eq(7), eq(5), anyString());
    }

    /**
     * A condition that stays true has one unchanging state token by design, so the cooldown can only
     * mean anything if each window is its own notification. Sharing one dedupe key across windows
     * would upsert the row the member already read or dismissed, and the promised re-announcement
     * would never reach them.
     */
    @Test
    void aFiringInASecondCooldownWindowIsANewInboxRowRatherThanTheOldOneRewritten() {
        subjectIsReadable("Aiko Tanaka");
        AiWatch cooling = watch(AiWatchType.RELATIONSHIP_COOLING, "person", 42);
        cooling.setThresholdBand("cold");
        when(scoringService.scoreContacts(7, Set.of(42)))
                .thenReturn(List.of(temperature(42, "cold", 90)));

        assertEquals(AiWatchEvaluationService.Outcome.FIRED, service.evaluate(cooling));
        assertEquals(AiWatchEvaluationService.Outcome.FIRED,
                serviceAt(NOW.plus(java.time.Duration.ofDays(8))).evaluate(cooling));

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationDelivery, org.mockito.Mockito.times(2)).deliver(captor.capture());
        List<Notification> delivered = captor.getAllValues();
        assertEquals("ai.watch:5:band:cold:2026-08-24", delivered.get(0).getDedupeKey());
        assertEquals("ai.watch:5:band:cold:2026-09-01", delivered.get(1).getDedupeKey());
        assertEquals(delivered.get(0).getData().contains("\"band\":\"cold\""),
                delivered.get(1).getData().contains("\"band\":\"cold\""),
                "Both windows state the same source-owned evidence");
    }

    /** The closed state-token space is what the durable row keeps; the window lives in the key. */
    @Test
    void theDurableStateTokenStaysClosedSoOscillationCannotInventNewTokens() {
        subjectIsReadable("Aiko Tanaka");
        AiWatch cooling = watch(AiWatchType.RELATIONSHIP_COOLING, "person", 42);
        cooling.setThresholdBand("cold");
        when(scoringService.scoreContacts(7, Set.of(42)))
                .thenReturn(List.of(temperature(42, "cold", 90)));

        service.evaluate(cooling);
        serviceAt(NOW.plus(java.time.Duration.ofDays(8))).evaluate(cooling);

        verify(watchMapper, org.mockito.Mockito.times(2))
                .claimFiring(eq(7), eq(5), eq("band:cold"), anyString(), anyString());
    }

    /**
     * The kill switch still stops watches. Deciding a firing invokes no model, but it is still an
     * Ask Connex surface: switching the assistant off must stop the firing stream rather than leave a
     * deterministic side channel delivering into an inbox.
     */
    @Test
    void aWorkspaceWhoseAssistantIsSwitchedOffEvaluatesNothingAndFiresNothing() {
        when(featureGate.isFeatureGoverned(AiFeature.ASSISTANT_CHAT)).thenReturn(false);
        subjectIsReadable("Aiko Tanaka");
        AiWatch cooling = watch(AiWatchType.RELATIONSHIP_COOLING, "person", 42);
        cooling.setThresholdBand("cool");
        when(scoringService.scoreContacts(7, Set.of(42)))
                .thenReturn(List.of(temperature(42, "cold", 90)));

        assertEquals(AiWatchEvaluationService.Outcome.SKIPPED, service.evaluate(cooling));

        verify(notificationDelivery, never()).deliver(any());
        verify(watchMapper, never()).claimFiring(
                anyInt(), anyInt(), anyString(), anyString(), anyString());
        verify(watchMapper, never()).recordEvaluated(anyInt(), anyInt(), anyString());
        verify(subjectReader, never()).label(anyString(), anyInt());
    }

    /**
     * The mirror-image fact: no provider is ever called to decide a firing, so a workspace without a
     * usable one keeps evaluating. Gating on readiness would silently drop conditions during exactly
     * the window in which a member is least likely to notice they stopped being told.
     */
    @Test
    void aWorkspaceWithNoUsableProviderStillEvaluatesAndFires() {
        when(featureGate.isAiUsable(AiFeature.ASSISTANT_CHAT)).thenReturn(false);
        subjectIsReadable("Aiko Tanaka");
        AiWatch cooling = watch(AiWatchType.RELATIONSHIP_COOLING, "person", 42);
        cooling.setThresholdBand("cold");
        when(scoringService.scoreContacts(7, Set.of(42)))
                .thenReturn(List.of(temperature(42, "cold", 90)));

        assertEquals(AiWatchEvaluationService.Outcome.FIRED, service.evaluate(cooling));

        verify(notificationDelivery).deliver(any());
    }

    /**
     * The expiry the member declared is read in the calendar it was validated in, not the sweep's
     * UTC pre-filter. The sweep deliberately selects a day wider than UTC so no zone is cut off
     * early; this is the authoritative comparison that keeps the extra day from evaluating.
     */
    @Test
    void aWatchPastItsDeclaredExpiryIsNotEvaluatedEvenWhenTheSweepStillSelectedIt() {
        subjectIsReadable("Aiko Tanaka");
        AiWatch cooling = watch(AiWatchType.RELATIONSHIP_COOLING, "person", 42);
        cooling.setThresholdBand("cold");
        cooling.setExpiresOn("2026-08-23");
        when(scoringService.scoreContacts(7, Set.of(42)))
                .thenReturn(List.of(temperature(42, "cold", 90)));

        assertEquals(AiWatchEvaluationService.Outcome.SKIPPED, service.evaluate(cooling));

        verify(watchMapper, never()).recordEvaluated(anyInt(), anyInt(), anyString());
        verify(notificationDelivery, never()).deliver(any());

        cooling.setExpiresOn("2026-08-24");
        assertEquals(AiWatchEvaluationService.Outcome.FIRED, service.evaluate(cooling));
    }

    /**
     * The cooldown compare-and-set is what stops a standing condition re-announcing itself, so it
     * cannot outlive a delivery that failed: a claim that committed while its notification did not
     * would suppress every retry until the cooldown elapsed. Claim and notification therefore share
     * one transaction, and a failed delivery rolls the claim back for the next sweep.
     */
    @Test
    void aFailedDeliveryRollsTheFiringClaimBackRatherThanSpendingTheCooldown() {
        subjectIsReadable("Aiko Tanaka");
        AiWatch cooling = watch(AiWatchType.RELATIONSHIP_COOLING, "person", 42);
        cooling.setThresholdBand("cold");
        when(scoringService.scoreContacts(7, Set.of(42)))
                .thenReturn(List.of(temperature(42, "cold", 90)));
        org.mockito.Mockito.doThrow(new IllegalStateException("inbox unavailable"))
                .when(notificationDelivery).deliver(any());

        assertThrows(IllegalStateException.class, () -> service.evaluate(cooling));

        verify(watchMapper).claimFiring(eq(7), eq(5), eq("band:cold"), anyString(), anyString());
        verify(transactionManager).rollback(any());
        verify(transactionManager, never()).commit(any());
    }

    @Test
    void aStoredSubjectKindTheTypeCannotWatchIsSkippedRatherThanReadThroughTheWrongSource() {
        subjectIsReadable("Acme renewal");
        AiWatch dealCooling = watch(AiWatchType.RELATIONSHIP_COOLING, "deal", 3);
        dealCooling.setThresholdBand("cold");

        assertEquals(AiWatchEvaluationService.Outcome.SKIPPED, service.evaluate(dealCooling));

        verify(scoringService, never()).scoreContacts(anyInt(), any(Set.class));
        verify(scoringService, never()).scoreCompanies(anyInt(), any(Set.class));
        verify(notificationDelivery, never()).deliver(any());
    }

    @Test
    void anUnreadableSubjectStopsTheWatchFiringWithoutDeletingIt() {
        when(subjectReader.label(anyString(), anyInt())).thenReturn(Optional.empty());
        AiWatch cooling = watch(AiWatchType.RELATIONSHIP_COOLING, "person", 42);
        cooling.setThresholdBand("cool");

        assertEquals(AiWatchEvaluationService.Outcome.SKIPPED, service.evaluate(cooling));

        verify(watchMapper).recordEvaluated(eq(7), eq(5), anyString());
        verify(notificationDelivery, never()).deliver(any());
        verify(scoringService, never()).scoreContacts(anyInt(), any(Set.class));
    }

    @Test
    void aWatchWhoseOwnerLeftTheWorkspaceIsNeverEvaluatedUnderAnyIdentity() {
        when(userService.getActiveWorkspaceUser(7, 11))
                .thenThrow(new ResourceNotFoundException("gone"));

        assertEquals(AiWatchEvaluationService.Outcome.SKIPPED,
                service.evaluate(watch(AiWatchType.COMMITMENT_OVERDUE, "person", 42)));

        verify(contextRunner, never()).run(anyInt(), anyInt(), any(), any(Runnable.class));
        verify(notificationDelivery, never()).deliver(any());
    }
}
