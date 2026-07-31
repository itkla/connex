package ooo.klae.connex.backend.connectedaccounts.capture;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;

import ooo.klae.connex.backend.beans.ProviderCapturedInteraction;
import ooo.klae.connex.backend.beans.ProviderCapturedParticipant;
import ooo.klae.connex.backend.connectedaccounts.ConnectedCaptureProperties;
import ooo.klae.connex.backend.mappers.IdentityMapper;
import ooo.klae.connex.backend.mappers.ProviderCaptureMapper;
import ooo.klae.connex.backend.mappers.ProviderConnectionMapper;
import ooo.klae.connex.backend.services.DuplicateDecisionLockService;
import ooo.klae.connex.backend.services.MatchingService;
import ooo.klae.connex.backend.services.ProviderCaptureHistoricalBaselineService;
import ooo.klae.connex.backend.services.WorkspaceService;

class ProviderCaptureHistoricalProjectionTest {

    @Test
    void delayedReviewProjectionPersistsHistoricalNotificationBaseline() {
        ProviderCaptureMapper captureMapper =
            mock(ProviderCaptureMapper.class);
        ProviderCaptureHistoricalBaselineService baselineService =
            mock(ProviderCaptureHistoricalBaselineService.class);
        ProviderCapturePagePersistence persistence =
            new ProviderCapturePagePersistence(
                captureMapper,
                mock(ProviderConnectionMapper.class),
                mock(IdentityMapper.class),
                mock(MatchingService.class),
                new ConnectedCaptureProperties(),
                new ObjectMapper(),
                mock(DuplicateDecisionLockService.class),
                mock(WorkspaceService.class),
                baselineService);
        ProviderCapturedInteraction interaction =
            new ProviderCapturedInteraction();
        interaction.setId(101);
        interaction.setProvider("google");
        interaction.setStream("mail_inbox");
        interaction.setUserId(9);
        ProviderCapturedParticipant participant =
            new ProviderCapturedParticipant();
        participant.setId(202);
        participant.setPersonId(44);
        participant.setMatchState("matched");
        when(captureMapper.getPersonIdsForInteractions(
                7, List.of(101L)))
            .thenReturn(List.of(44));
        when(captureMapper.getActivityIdsForInteractions(
                7, List.of(101L)))
            .thenReturn(List.of(), List.of(303));
        ProviderCaptureHistoricalBaselineService.Snapshot baseline =
            mock(ProviderCaptureHistoricalBaselineService.Snapshot.class);
        when(baselineService.snapshot(
                eq(7), any(), eq(Set.of(44)), eq(Set.of())))
            .thenReturn(baseline);
        when(captureMapper.getActivityIdByProjectionKey(
                eq(7), anyString()))
            .thenReturn(303);

        persistence.projectHistorical(
            7, 9, interaction, List.of(participant));

        verify(captureMapper).insertProviderActivity(
            eq(7), eq(interaction), eq(participant), eq(9), anyString());
        verify(baselineService).persist(
            eq(7),
            any(),
            eq(baseline),
            eq(Set.of(44)),
            eq(Set.of(303)),
            anyString());
    }
}
