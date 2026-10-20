package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.dto.SegmentCondition;
import ooo.klae.connex.backend.dto.SegmentDefinition;
import ooo.klae.connex.backend.mappers.PersonMapper;
import ooo.klae.connex.backend.mappers.SegmentMapper;
import ooo.klae.connex.backend.mappers.TagMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.tenant.TenantCatalogResolver;
import ooo.klae.connex.backend.tenant.TenantContext;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

class SegmentServiceTimeZoneTest {

    private final WorkspaceService workspaceService = mock(WorkspaceService.class);
    private final AuthService authService = mock(AuthService.class);
    private final SegmentMapper segmentMapper = mock(SegmentMapper.class);
    private final TenantContext tenantContext = new TenantContext();
    private final SegmentService service = new SegmentService(
        workspaceService, authService, mock(ScoringService.class), mock(DealRiskService.class),
        segmentMapper, mock(PersonEdgeReadService.class), mock(PersonMapper.class), mock(TagMapper.class),
        new SegmentCatalog());

    @AfterEach
    void clearContext() {
        tenantContext.clear();
    }

    @ParameterizedTest
    @CsvSource({
        "2026-10-20T14:59:59Z, 2026-10-20, 2026-10-22",
        "2026-10-20T15:00:01Z, 2026-10-20, 2026-10-22",
        "2026-10-21T00:00:01Z, 2026-10-21, 2026-10-23"
    })
    void interactiveBoundsUseUtcEvenForATokyoActor(String asOf, String from, String to) {
        tenantContext.set(7, 42, 17, "member", null);
        User actor = new User();
        actor.setId(17);
        actor.setTimezone("Asia/Tokyo");
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(7);
        when(authService.getCurrentUser()).thenReturn(actor);
        when(segmentMapper.personIdsMatching(anyMap())).thenReturn(List.of(19));

        withClock(asOf, () -> assertEquals(List.of(19), service.evaluate("person", definition())));

        assertBounds(false, from, to);
        verify(workspaceService, never()).getCurrentAnalyticsTimezone();
    }

    @ParameterizedTest
    @EnumSource(BackgroundPath.class)
    void sessionFreePathsUseUtcWithoutResolvingWorkspaceMetadata(BackgroundPath path) {
        if (path == BackgroundPath.CAMPAIGN) {
            when(segmentMapper.personIdsMatchingIncludingRestricted(anyMap())).thenReturn(List.of(19));
        } else {
            when(segmentMapper.personIdsMatching(anyMap())).thenReturn(List.of(19));
        }
        if (path == BackgroundPath.BOUNDED) {
            when(segmentMapper.maximumEntityId(7, "person")).thenReturn(19);
            when(segmentMapper.entityIdsPage(7, "person", 0, 19, 10)).thenReturn(List.of(19));
        }
        if (path == BackgroundPath.SINGLE_RECORD) {
            when(segmentMapper.entityIdInWorkspace(7, "person", 19)).thenReturn(true);
        }

        withClock("2026-10-20T15:00:01Z", () -> {
            switch (path) {
                case EVALUATE -> assertEquals(List.of(19), service.evaluate(7, 17, "person", definition()));
                case CAMPAIGN -> assertEquals(List.of(19),
                    service.evaluateIncludingRestrictedPeople(7, 17, "person", definition()));
                case BOUNDED -> assertEquals(List.of(19), service.evaluate(7, 17, "person", definition(), 10));
                case SINGLE_RECORD -> assertTrue(service.matchesEntity(7, 17, "person", definition(), 19));
            }
        });

        assertBounds(path == BackgroundPath.CAMPAIGN, "2026-10-20", "2026-10-22");
        verifyNoInteractions(workspaceService, authService);
    }

    @Test
    void routedOutboxTransactionNeedsNoControlCatalogReadOrTenantIdentity() {
        TenantWorkScope scope = new TenantWorkScope(
            tenantContext, mock(TenantCatalogResolver.class), mock(WorkspaceMapper.class));
        when(segmentMapper.personIdsMatching(anyMap())).thenReturn(List.of(19));
        boolean previousTransaction = TransactionSynchronizationManager.isActualTransactionActive();

        withClock("2026-10-20T15:00:01Z", () -> scope.withCatalog("cnx_tenant", () -> {
            assertFalse(tenantContext.isResolved());
            TransactionSynchronizationManager.setActualTransactionActive(true);
            try {
                assertEquals(List.of(19), service.evaluate(7, 17, "person", definition()));
            } finally {
                TransactionSynchronizationManager.setActualTransactionActive(previousTransaction);
            }
            return null;
        }));

        assertBounds(false, "2026-10-20", "2026-10-22");
        verifyNoInteractions(workspaceService, authService);
    }

    @Test
    void boundedPagesKeepTheInitialUtcDateAcrossMidnight() {
        when(segmentMapper.maximumEntityId(7, "person")).thenReturn(3);
        when(segmentMapper.entityIdsPage(7, "person", 0, 3, 2)).thenReturn(List.of(1, 2));
        when(segmentMapper.entityIdsPage(7, "person", 2, 3, 2)).thenReturn(List.of(3));
        when(segmentMapper.personIdsMatching(anyMap())).thenReturn(List.of(), List.of(3));
        Clock beforeMidnight = Clock.fixed(Instant.parse("2026-10-20T23:59:59Z"), ZoneOffset.UTC);
        Clock afterMidnight = Clock.fixed(Instant.parse("2026-10-21T00:00:01Z"), ZoneOffset.UTC);
        AtomicInteger reads = new AtomicInteger();

        try (var clocks = mockStatic(Clock.class)) {
            clocks.when(() -> Clock.system(any(ZoneId.class)))
                .thenAnswer(invocation -> (reads.getAndIncrement() == 0 ? beforeMidnight : afterMidnight)
                    .withZone(invocation.getArgument(0)));
            assertEquals(List.of(3), service.evaluate(7, 17, "person", definition(), 2));
        }

        ArgumentCaptor<Map<String, Object>> params = ArgumentCaptor.captor();
        verify(segmentMapper, times(2)).personIdsMatching(params.capture());
        for (Map<String, Object> page : params.getAllValues()) {
            assertEquals("2026-10-20", page.get("dateFrom"));
            assertEquals("2026-10-22", page.get("dateTo"));
        }
        assertEquals(1, reads.get());
    }

    @Test
    void definitionValidationDoesNotResolveWorkspaceDates() {
        try (var clocks = mockStatic(Clock.class)) {
            service.validate("person", definition());

            clocks.verifyNoInteractions();
        }
        verifyNoInteractions(workspaceService, authService, segmentMapper);
    }

    private void assertBounds(boolean restricted, String from, String to) {
        ArgumentCaptor<Map<String, Object>> params = ArgumentCaptor.captor();
        if (restricted) {
            verify(segmentMapper).personIdsMatchingIncludingRestricted(params.capture());
        } else {
            verify(segmentMapper).personIdsMatching(params.capture());
        }
        assertEquals(7, params.getValue().get("workspaceId"));
        assertEquals(from, params.getValue().get("dateFrom"));
        assertEquals(to, params.getValue().get("dateTo"));
    }

    private static SegmentDefinition definition() {
        SegmentCondition condition = new SegmentCondition();
        condition.setType("field");
        condition.setField("created");
        condition.setOp("within_days");
        condition.setDays(2);
        SegmentDefinition definition = new SegmentDefinition();
        definition.setMatch("all");
        definition.setConditions(List.of(condition));
        return definition;
    }

    private static void withClock(String asOf, Runnable work) {
        Clock fixed = Clock.fixed(Instant.parse(asOf), ZoneOffset.UTC);
        try (var clocks = mockStatic(Clock.class)) {
            clocks.when(() -> Clock.system(any(ZoneId.class)))
                .thenAnswer(invocation -> fixed.withZone(invocation.getArgument(0)));
            work.run();
        }
    }

    private enum BackgroundPath { EVALUATE, CAMPAIGN, BOUNDED, SINGLE_RECORD }
}
