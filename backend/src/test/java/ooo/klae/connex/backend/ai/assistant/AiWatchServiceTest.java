package ooo.klae.connex.backend.ai.assistant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.ai.AiFeature;
import ooo.klae.connex.backend.ai.AiFeatureGate;
import ooo.klae.connex.backend.dto.AiWatchCreateRequest;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.AiWatchMapper;
import ooo.klae.connex.backend.services.WorkspaceService;

/**
 * Pins the typed watch contract: only evaluated types, only compatible subjects, only the threshold
 * the type actually reads, and only the calling member's own rows.
 */
class AiWatchServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-24T07:00:00Z");

    private final AiWatchMapper watchMapper = mock(AiWatchMapper.class);
    private final AiFeatureGate featureGate = mock(AiFeatureGate.class);
    private final AiWatchSubjectReader subjectReader = mock(AiWatchSubjectReader.class);
    private final WorkspaceService workspaceService = mock(WorkspaceService.class);

    private final AiWatchService service = new AiWatchService(
            watchMapper, featureGate, subjectReader, workspaceService,
            Clock.fixed(NOW, ZoneOffset.UTC));

    @BeforeEach
    void installIdentity() {
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(7);
        when(workspaceService.getCurrentUserId()).thenReturn(11);
        when(workspaceService.getCurrentAnalyticsTimezone()).thenReturn("UTC");
        when(subjectReader.label(anyString(), anyInt())).thenReturn(Optional.of("Aiko Tanaka"));
    }

    private static AiWatchCreateRequest cooling(String band) {
        return new AiWatchCreateRequest(
                "relationship_cooling", "person", 42, band, null, null, 7, null);
    }

    private static AiWatchCreateRequest expiring(String expiresOn) {
        return new AiWatchCreateRequest(
                "relationship_cooling", "person", 42, "cold", null, null, 7, expiresOn);
    }

    @Test
    void aWatchIsCreatedForTheCallingMemberInTheirOwnWorkspaceOnly() {
        service.create(cooling("cold"));

        verify(watchMapper).insert(org.mockito.ArgumentMatchers.argThat(watch ->
                watch.getWorkspaceId() == 7
                        && watch.getOwnerUserId() == 11
                        && "cold".equals(watch.getThresholdBand())
                        && "active".equals(watch.getStatus())));
    }

    @Test
    void aTypeThisBuildDoesNotEvaluateIsRefusedRatherThanStoredDormant() {
        AiWatchCreateRequest unsupported = new AiWatchCreateRequest(
                "field_change", "person", 42, null, null, null, 7, null);

        assertThrows(BadRequestException.class, () -> service.create(unsupported));
        verify(watchMapper, never()).insert(any());
    }

    @Test
    void aTypeIsRefusedAgainstARecordKindItCannotWatch() {
        AiWatchCreateRequest dealCooling = new AiWatchCreateRequest(
                "relationship_cooling", "deal", 3, "cold", null, null, 7, null);

        assertThrows(BadRequestException.class, () -> service.create(dealCooling));
        verify(watchMapper, never()).insert(any());
    }

    @Test
    void aMissingOrForeignThresholdIsRefusedSoTheDisplayedTriggerCannotDivergeFromTheEvaluatedOne() {
        assertThrows(BadRequestException.class, () -> service.create(cooling(null)));

        AiWatchCreateRequest strayThreshold = new AiWatchCreateRequest(
                "commitment_overdue", "person", 42, "cold", null, null, 7, null);
        assertThrows(BadRequestException.class, () -> service.create(strayThreshold));
        verify(watchMapper, never()).insert(any());
    }

    @Test
    void anUnreadableRecordCannotBeWatched() {
        when(subjectReader.label(anyString(), anyInt())).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.create(cooling("cold")));
        verify(watchMapper, never()).insert(any());
    }

    @Test
    void theWatchLimitBoundsOneMembersScheduledEvaluationWorkload() {
        when(watchMapper.countForOwner(7, 11))
                .thenReturn(AiWatchService.MAX_WATCHES_PER_MEMBER);

        assertThrows(ConflictException.class, () -> service.create(cooling("cold")));
        verify(watchMapper, never()).insert(any());
    }

    @Test
    void theSameConditionCannotBeWatchedTwiceOnOneRecord() {
        when(watchMapper.insert(any()))
                .thenThrow(new org.springframework.dao.DuplicateKeyException("duplicate"));

        assertThrows(ConflictException.class, () -> service.create(cooling("cold")));
    }

    @Test
    void pausingAndDeletingAreScopedToTheCallingMemberAndWorkspace() {
        when(watchMapper.updateStatus(7, 11, 5, "paused")).thenReturn(0);
        assertThrows(ResourceNotFoundException.class, () -> service.setActive(5, false));

        when(watchMapper.delete(7, 11, 5)).thenReturn(0);
        assertThrows(ResourceNotFoundException.class, () -> service.delete(5));

        when(watchMapper.delete(7, 11, 6)).thenReturn(1);
        service.delete(6);
        verify(watchMapper).delete(eq(7), eq(11), eq(6));
    }

    @Test
    void aWorkspaceWhoseAssistantIsSwitchedOffCannotHaveNewWatchesCreatedInIt() {
        org.mockito.Mockito.doThrow(new ForbiddenException("AI features are not available"))
                .when(featureGate).requireAiUsable(AiFeature.ASSISTANT_CHAT);

        assertThrows(ForbiddenException.class, () -> service.create(cooling("cold")));
        verify(watchMapper, never()).insert(any());
        verify(subjectReader, never()).label(anyString(), anyInt());
    }

    /**
     * The request pattern only proves the shape, so an impossible date reaches the service. Storing
     * it would produce a watch the member sees as active whose expiry comparison never matches.
     */
    @Test
    void anExpiryThatIsNotARealFutureDateIsRefusedRatherThanStoredUnevaluable() {
        assertThrows(BadRequestException.class, () -> service.create(expiring("2026-13-45")));
        assertThrows(BadRequestException.class, () -> service.create(expiring("2026-08-24")));
        assertThrows(BadRequestException.class, () -> service.create(expiring("2020-01-01")));
        verify(watchMapper, never()).insert(any());

        service.create(expiring("2026-09-30"));
        verify(watchMapper).insert(org.mockito.ArgumentMatchers.argThat(watch ->
                "2026-09-30".equals(watch.getExpiresOn())));
    }

    @Test
    void everyEvaluatedTypeDeclaresExactlyOneThresholdShape() {
        assertEquals(AiWatchType.Threshold.BAND,
                AiWatchType.RELATIONSHIP_COOLING.threshold());
        assertEquals(AiWatchType.Threshold.DAYS, AiWatchType.NO_INTERACTION.threshold());
        assertEquals(AiWatchType.Threshold.NONE, AiWatchType.COMMITMENT_OVERDUE.threshold());
        assertEquals(AiWatchType.Threshold.LEVEL, AiWatchType.DEAL_RISK_THRESHOLD.threshold());
        assertEquals(Optional.empty(), AiWatchType.from("saved_view_threshold"));
        assertEquals(Optional.empty(), AiWatchType.from(null));
    }
}
