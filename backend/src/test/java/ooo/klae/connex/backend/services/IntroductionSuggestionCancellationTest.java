package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CancellationException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.stubbing.Answer;

import tools.jackson.databind.ObjectMapper;

import ooo.klae.connex.backend.beans.IntroCandidatePerson;
import ooo.klae.connex.backend.mappers.IntroductionMapper;
import ooo.klae.connex.backend.mappers.PersonEdgeMapper;
import ooo.klae.connex.backend.mappers.PersonMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.notifications.NotificationDelivery;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

/**
 * Verifies that suggestion ranking, which introduction rationales run on a fixed-size AI
 * generation worker, stops immediately after each workspace-wide load and after the rescore once
 * the worker has been interrupted, while request and scheduled callers are never cancelled.
 */
@ExtendWith(MockitoExtension.class)
class IntroductionSuggestionCancellationTest {
    private static final int WORKSPACE_ID = 17;
    private static final int LIMIT = 50;

    @Mock private IntroductionMapper introductionMapper;
    @Mock private UserMapper userMapper;
    @Mock private PersonEdgeMapper edgeMapper;
    @Mock private PersonEdgeReadService edgeReader;
    @Mock private PersonMapper personMapper;
    @Mock private ScoringService scoringService;
    @Mock private WarmPathService warmPathService;
    @Mock private WorkspaceService workspaceService;
    @Mock private AuthService authService;
    @Mock private Clock clock;
    @Mock private ReferenceService referenceService;
    @Mock private NotificationDelivery notificationDelivery;
    @Mock private NotificationPreferenceService notificationPreferenceService;
    @Mock private ObjectMapper objectMapper;
    @Mock private TenantWorkScope tenantWorkScope;

    private IntroductionService service;

    @BeforeEach
    void setUp() {
        service = new IntroductionService(
            introductionMapper,
            userMapper,
            edgeMapper,
            edgeReader,
            personMapper,
            scoringService,
            warmPathService,
            workspaceService,
            authService,
            clock,
            referenceService,
            notificationDelivery,
            notificationPreferenceService,
            objectMapper,
            tenantWorkScope);
    }

    @Test
    void interruptDuringCandidateLoadStopsBeforeTheEdgeGraphIsLoaded() {
        when(introductionMapper.findCandidatePersons(WORKSPACE_ID))
            .thenAnswer(interruptingWith(List.of(candidate(1), candidate(2))));

        assertRankingCancelled();
        verify(introductionMapper, never()).findIntroExcludedPersonIds(anyInt());
        verify(edgeReader, never()).getAllEdges(anyInt());
    }

    @Test
    void interruptDuringExclusionLoadStopsBeforeTheEdgeGraphIsLoaded() {
        when(introductionMapper.findCandidatePersons(WORKSPACE_ID))
            .thenReturn(List.of(candidate(1), candidate(2)));
        when(introductionMapper.findIntroExcludedPersonIds(WORKSPACE_ID))
            .thenAnswer(interruptingWith(List.of()));

        assertRankingCancelled();
        verify(edgeReader, never()).getAllEdges(anyInt());
    }

    @Test
    void interruptDuringEdgeLoadStopsBeforeEmploymentIsLoaded() {
        when(introductionMapper.findCandidatePersons(WORKSPACE_ID))
            .thenReturn(List.of(candidate(1), candidate(2)));
        when(edgeReader.getAllEdges(WORKSPACE_ID)).thenAnswer(interruptingWith(List.of()));

        assertRankingCancelled();
        verify(introductionMapper, never()).findWorkspaceEmployment(anyInt());
    }

    @Test
    void interruptDuringEmploymentLoadStopsBeforeExistingPairsAreLoaded() {
        when(introductionMapper.findCandidatePersons(WORKSPACE_ID))
            .thenReturn(List.of(candidate(1), candidate(2)));
        when(introductionMapper.findWorkspaceEmployment(WORKSPACE_ID))
            .thenAnswer(interruptingWith(List.of()));

        assertRankingCancelled();
        verify(introductionMapper, never()).findExistingPairs(anyInt());
    }

    @Test
    void interruptDuringExistingPairLoadStopsBeforeTheWorkspaceIsRescored() {
        when(introductionMapper.findCandidatePersons(WORKSPACE_ID))
            .thenReturn(List.of(candidate(1), candidate(2)));
        when(introductionMapper.findExistingPairs(WORKSPACE_ID)).thenAnswer(interruptingWith(List.of()));

        assertRankingCancelled();
        verify(scoringService, never()).scoreContacts(anyInt());
    }

    @Test
    void interruptDuringTheRescoreStopsBeforeSuggestionsAreRanked() {
        when(introductionMapper.findCandidatePersons(WORKSPACE_ID))
            .thenReturn(List.of(candidate(1), candidate(2)));
        when(scoringService.scoreContacts(WORKSPACE_ID)).thenAnswer(interruptingWith(List.of()));

        assertRankingCancelled();
        verify(clock, never()).instant();
    }

    @Test
    void requestAndScheduledRankingIgnoresAnInterruptedThread() {
        when(introductionMapper.findCandidatePersons(WORKSPACE_ID))
            .thenReturn(List.of(candidate(1), candidate(2)));
        when(clock.instant()).thenReturn(Instant.parse("2026-09-19T00:00:00Z"));

        Thread.currentThread().interrupt();
        try {
            service.computeSuggestions(WORKSPACE_ID, LIMIT);
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
        verify(introductionMapper).findExistingPairs(WORKSPACE_ID);
        verify(scoringService).scoreContacts(WORKSPACE_ID);
    }

    private void assertRankingCancelled() {
        try {
            assertThrows(CancellationException.class,
                () -> service.computeCancellableSuggestions(WORKSPACE_ID, LIMIT));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    private static <T> Answer<T> interruptingWith(T result) {
        return invocation -> {
            Thread.currentThread().interrupt();
            return result;
        };
    }

    private static IntroCandidatePerson candidate(int id) {
        IntroCandidatePerson candidate = new IntroCandidatePerson();
        candidate.setId(id);
        candidate.setName("Candidate " + id);
        return candidate;
    }
}
