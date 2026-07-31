package ooo.klae.connex.backend.connectedaccounts.capture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import tools.jackson.databind.ObjectMapper;

import ooo.klae.connex.backend.beans.IdentityMatchRow;
import ooo.klae.connex.backend.beans.ProviderCaptureSyncState;
import ooo.klae.connex.backend.beans.ProviderCapturedInteraction;
import ooo.klae.connex.backend.beans.ProviderCapturedParticipant;
import ooo.klae.connex.backend.beans.ProviderConnection;
import ooo.klae.connex.backend.connectedaccounts.ConnectedCaptureProperties;
import ooo.klae.connex.backend.mappers.IdentityMapper;
import ooo.klae.connex.backend.mappers.ProviderCaptureMapper;
import ooo.klae.connex.backend.mappers.ProviderConnectionMapper;
import ooo.klae.connex.backend.services.DuplicateDecisionLockService;
import ooo.klae.connex.backend.services.MatchingService;
import ooo.klae.connex.backend.services.ProviderCaptureHistoricalBaselineService;
import ooo.klae.connex.backend.services.WorkspaceService;

@ExtendWith(MockitoExtension.class)
class ProviderCapturePagePersistenceTest {
    @Mock private ProviderCaptureMapper captureMapper;
    @Mock private ProviderConnectionMapper connectionMapper;
    @Mock private IdentityMapper identityMapper;
    @Mock private MatchingService matchingService;
    @Mock private DuplicateDecisionLockService duplicateDecisionLockService;
    @Mock private WorkspaceService workspaceService;
    @Mock private ProviderCaptureHistoricalBaselineService historicalBaselineService;

    private ProviderCapturePagePersistence persistence;
    private ProviderCaptureSyncState state;

    @BeforeEach
    void setUp() {
        ConnectedCaptureProperties properties = new ConnectedCaptureProperties();
        persistence = new ProviderCapturePagePersistence(
            captureMapper,
            connectionMapper,
            identityMapper,
            matchingService,
            properties,
            new ObjectMapper(),
            duplicateDecisionLockService,
            workspaceService,
            historicalBaselineService);
        state = new ProviderCaptureSyncState();
        state.setId(8);
        state.setWorkspaceId(7);
        state.setUserId(9);
        state.setProvider("google");
        state.setStream("mail_inbox");
        state.setInitialSyncCompleted(true);
        state.setStableCursor("cursor-old");
        state.setCredentialGeneration(3);
        state.setLeaseOwner("lease");
        state.setProcessedItems(0);
        when(captureMapper.getSyncState(7, 8)).thenReturn(state);
        when(captureMapper.getSyncStateForUpdate(7, 8)).thenReturn(state);
        ProviderConnection connection = new ProviderConnection();
        connection.setUserId(9);
        connection.setProvider("google");
        connection.setStatus("connected");
        connection.setCredentialGeneration(3);
        when(connectionMapper.getByUserAndProviderForShare(9, "google"))
            .thenReturn(connection);
        when(captureMapper.saveSyncSuccess(
                eq(7), eq(8L), eq("lease"), any(), any(), anyString(),
                any(Long.class), any(), anyString(), anyString()))
            .thenReturn(1);
    }

    @Test
    void identicalReplayCreatesOneInteractionActivityAndEvidenceProjection() {
        stubGeneratedIds();
        IdentityMatchRow match = new IdentityMatchRow();
        match.setRecordId(44);
        when(matchingService.normalizeIdentifier(any(), eq("customer@example.net")))
            .thenReturn(java.util.Optional.of("customer@example.net"));
        when(matchingService.extractCompanyDomainFromEmail("customer@example.net"))
            .thenReturn(java.util.Optional.of("example.net"));
        when(matchingService.extractCompanyDomainFromEmail("owner@example.test"))
            .thenReturn(java.util.Optional.of("example.test"));
        when(identityMapper.findCurrentPersonIdentityMatches(
                7, "email", List.of("customer@example.net")))
            .thenReturn(List.of(match));
        when(captureMapper.getActivityIdByProjectionKey(eq(7), anyString()))
            .thenReturn(303);
        when(captureMapper.markInteractionAdmitted(7, 101, 1)).thenReturn(1);
        ArgumentCaptor<ProviderCapturedInteraction> interaction =
            ArgumentCaptor.forClass(ProviderCapturedInteraction.class);

        persistence.commit(
            7, 8, "lease", page(), policy("automatic"), "owner@example.test");
        verify(captureMapper).insertInteraction(interaction.capture());
        when(captureMapper.getInteractionBySourceHash(
                eq(7), eq(9), eq("google"), any()))
            .thenReturn(interaction.getValue());
        when(captureMapper.getInteractionForUpdate(
                7, 9, "google", 101))
            .thenReturn(interaction.getValue());

        persistence.commit(
            7, 8, "lease", page(), policy("automatic"), "owner@example.test");

        verify(captureMapper).insertInteraction(any());
        verify(captureMapper).insertParticipant(any());
        verify(captureMapper).insertProviderActivity(
            eq(7), any(), any(), eq(9), anyString());
        verify(captureMapper).insertProjection(7, 101, 202, 303);
        verify(captureMapper, never()).deleteInteractionActivities(anyInt(), anyLong());
    }

    @Test
    void everyCommitExpiresProviderEvidenceOutsideTheRollingRetentionWindow() {
        persistence.commit(
            7,
            8,
            "lease",
            new ProviderCapturePage(List.of(), null, "cursor-new", 0L),
            policy("review"),
            "owner@example.test");

        verify(captureMapper).deleteExpiredProviderActivities(
            eq(7), eq(9), eq("google"), eq("mail_inbox"), anyString());
        verify(captureMapper).deleteExpiredInteractions(
            eq(7), eq(9), eq("google"), eq("mail_inbox"), anyString());
    }

    @Test
    void incrementalGoogleCalendarRoundPersistsTheNextSyncToken() {
        state.setStream("calendar");
        state.setStableCursor("calendar-sync-old");
        state.setReconciliationMarker(null);

        persistence.commit(
            7,
            8,
            "lease",
            new ProviderCapturePage(
                List.of(), null, "calendar-sync-new", 0L),
            policy("review"),
            "owner@example.test");

        verify(captureMapper).saveSyncSuccess(
            eq(7),
            eq(8L),
            eq("lease"),
            eq("calendar-sync-new"),
            eq(null),
            eq("idle"),
            eq(0L),
            eq(0L),
            anyString(),
            anyString());
        verify(historicalBaselineService, never()).snapshot(
            anyInt(), any(), any(), any());
    }

    @Test
    void ambiguousExactIdentityIsHeldAndNeverProjected() {
        stubGeneratedIds();
        IdentityMatchRow first = new IdentityMatchRow();
        first.setRecordId(44);
        IdentityMatchRow second = new IdentityMatchRow();
        second.setRecordId(45);
        when(matchingService.normalizeIdentifier(any(), eq("customer@example.net")))
            .thenReturn(java.util.Optional.of("customer@example.net"));
        when(matchingService.extractCompanyDomainFromEmail("customer@example.net"))
            .thenReturn(java.util.Optional.of("example.net"));
        when(matchingService.extractCompanyDomainFromEmail("owner@example.test"))
            .thenReturn(java.util.Optional.of("example.test"));
        when(identityMapper.findCurrentPersonIdentityMatches(
                7, "email", List.of("customer@example.net")))
            .thenReturn(List.of(first, second));
        ArgumentCaptor<ProviderCapturedParticipant> participant =
            ArgumentCaptor.forClass(ProviderCapturedParticipant.class);

        persistence.commit(
            7, 8, "lease", page(), policy("automatic"), "owner@example.test");

        verify(captureMapper).insertParticipant(participant.capture());
        assertEquals("ambiguous", participant.getValue().getMatchState());
        assertEquals("multiple_matches", participant.getValue().getHeldReason());
        assertNull(participant.getValue().getPersonId());
        verify(captureMapper, never()).insertProviderActivity(
            anyInt(), any(), any(), anyInt(), anyString());
    }

    @Test
    void restrictedExactIdentityIsHeldAndNeverProjected() {
        stubGeneratedIds();
        IdentityMatchRow match = new IdentityMatchRow();
        match.setRecordId(44);
        when(matchingService.normalizeIdentifier(any(), eq("customer@example.net")))
            .thenReturn(java.util.Optional.of("customer@example.net"));
        when(matchingService.extractCompanyDomainFromEmail("customer@example.net"))
            .thenReturn(java.util.Optional.of("example.net"));
        when(matchingService.extractCompanyDomainFromEmail("owner@example.test"))
            .thenReturn(java.util.Optional.of("example.test"));
        when(identityMapper.findCurrentPersonIdentityMatches(
                7, "email", List.of("customer@example.net")))
            .thenReturn(List.of(match));
        when(captureMapper.isPersonProcessingRestricted(7, 44))
            .thenReturn(true);
        ArgumentCaptor<ProviderCapturedParticipant> participant =
            ArgumentCaptor.forClass(ProviderCapturedParticipant.class);

        persistence.commit(
            7, 8, "lease", page(), policy("automatic"), "owner@example.test");

        verify(captureMapper).insertParticipant(participant.capture());
        assertEquals("unmatched", participant.getValue().getMatchState());
        assertEquals("restricted_person", participant.getValue().getHeldReason());
        assertNull(participant.getValue().getPersonId());
        verify(captureMapper, never()).insertProviderActivity(
            anyInt(), any(), any(), anyInt(), anyString());
    }

    @Test
    void privateItemWithdrawsPreviouslyAdmittedEvidence() {
        ProviderCapturedInteraction existing = new ProviderCapturedInteraction();
        existing.setId(101);
        existing.setWorkspaceId(7);
        existing.setVersion(3);
        existing.setAdmissionStatus("admitted");
        when(captureMapper.getInteractionBySourceHash(
                eq(7), eq(9), eq("google"), any()))
            .thenReturn(existing);
        when(captureMapper.getInteractionForUpdate(
                7, 9, "google", 101))
            .thenReturn(existing);
        ProviderCaptureItem privateItem = new ProviderCaptureItem(
            "mail-1",
            "version-2",
            null,
            "email",
            "Private",
            "body",
            Instant.parse("2026-07-30T09:00:00Z"),
            null,
            true,
            false,
            List.of());

        persistence.commit(
            7,
            8,
            "lease",
            new ProviderCapturePage(List.of(privateItem), null, "cursor-2", 1L),
            policy("review"),
            "owner@example.test");

        verify(captureMapper).deleteInteractionActivities(7, 101);
        verify(captureMapper).deleteParticipants(7, 101);
        verify(captureMapper).updateInteraction(existing);
        assertEquals("withdrawn", existing.getAdmissionStatus());
        assertNull(existing.getSubject());
        assertNull(existing.getBody());
    }

    @Test
    void completedFullScanWithdrawsSourcesMissingFromTheProviderWindow() {
        state.setStableCursor(null);
        state.setReconciliationMarker("3f054771-fc90-4a29-aa73-bb3673ba142b");

        persistence.commit(
            7,
            8,
            "lease",
            new ProviderCapturePage(List.of(), null, "cursor-2", 0L),
            policy("review"),
            "owner@example.test");

        verify(captureMapper).deleteMissingReconciliationActivities(
            7, 9, "google", "mail_inbox",
            "3f054771-fc90-4a29-aa73-bb3673ba142b");
        verify(captureMapper).deleteMissingReconciliationParticipants(
            7, 9, "google", "mail_inbox",
            "3f054771-fc90-4a29-aa73-bb3673ba142b");
        verify(captureMapper).withdrawMissingReconciliationItems(
            7, 9, "google", "mail_inbox",
            "3f054771-fc90-4a29-aa73-bb3673ba142b");
    }

    private static ProviderCapturePage page() {
        ProviderCaptureItem item = new ProviderCaptureItem(
            "mail-1",
            "version-1",
            null,
            "email",
            "Hello",
            "body",
            Instant.parse("2026-07-30T09:00:00Z"),
            null,
            false,
            false,
            List.of(new ProviderCaptureParticipant(
                "to", "Customer", "customer@example.net")));
        return new ProviderCapturePage(List.of(item), null, "cursor-1", 1L);
    }

    private static CaptureExecutionPolicy policy(String admissionMode) {
        return new CaptureExecutionPolicy(
            true,
            true,
            true,
            true,
            90,
            false,
            admissionMode,
            true,
            true,
            List.of(),
            List.of(),
            List.of(),
            1);
    }

    private void stubGeneratedIds() {
        when(captureMapper.insertInteraction(any())).thenAnswer(invocation -> {
            invocation.<ProviderCapturedInteraction>getArgument(0).setId(101);
            return 1;
        });
        when(captureMapper.insertParticipant(any())).thenAnswer(invocation -> {
            invocation.<ProviderCapturedParticipant>getArgument(0).setId(202);
            return 1;
        });
    }
}
