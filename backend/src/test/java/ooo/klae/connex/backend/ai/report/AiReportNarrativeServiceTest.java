package ooo.klae.connex.backend.ai.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.ai.AiFeatureGate;
import ooo.klae.connex.backend.ai.AiInvocationService;
import ooo.klae.connex.backend.ai.AiOutputCacheStore;
import ooo.klae.connex.backend.dto.ReportAppendixRowDto;
import ooo.klae.connex.backend.dto.ReportNarrativeDto;
import ooo.klae.connex.backend.services.AuthService;
import ooo.klae.connex.backend.services.WorkspaceService;

/**
 * Verifies the non-blocking cache-only narrative path never reaches the AI provider.
 */
class AiReportNarrativeServiceTest {
    private final AiReportAssembler assembler = mock(AiReportAssembler.class);
    private final AiInvocationService invocationService = mock(AiInvocationService.class);
    private final AiFeatureGate featureGate = mock(AiFeatureGate.class);
    private final AiOutputCacheStore cacheStore = mock(AiOutputCacheStore.class);
    private final WorkspaceService workspaceService = mock(WorkspaceService.class);
    private final AuthService authService = mock(AuthService.class);
    private final Clock clock = Clock.systemUTC();

    private final AiReportNarrativeService service = new AiReportNarrativeService(
            assembler, invocationService, featureGate, cacheStore, workspaceService, authService, clock);

    private static final List<ReportAppendixRowDto> SOURCES = List.of(
            new ReportAppendixRowDto("metric.0.0", "w1", "count · Total", BigDecimal.ONE, null, "count"));

    @Test
    void cachedNarrativeReturnsNotConfiguredWhenGateClosedWithoutTouchingProvider() {
        when(featureGate.isAiUsable()).thenReturn(false);

        ReportNarrativeDto result = service.cachedNarrative(
                7, "Report", LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31), SOURCES);

        assertFalse(result.available());
        assertEquals("not_configured", result.reason());
        verifyNoInteractions(invocationService, assembler, cacheStore);
    }

    @Test
    void cachedNarrativeReturnsNotCachedOnCacheMissWithoutTouchingProvider() {
        when(featureGate.isAiUsable()).thenReturn(true);
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(1);
        when(assembler.assemble(any())).thenReturn(mock(AiReportAssembly.class));
        when(cacheStore.contentHash(any(), any())).thenReturn("hash");
        when(cacheStore.find(anyInt(), anyString(), anyInt(), anyInt())).thenReturn(Optional.empty());

        ReportNarrativeDto result = service.cachedNarrative(
                7, "Report", LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31), SOURCES);

        assertFalse(result.available());
        assertEquals("not_cached", result.reason());
        verifyNoInteractions(invocationService);
        verify(cacheStore, never()).save(anyInt(), anyString(), anyInt(), anyInt(), anyString(), any(), anyInt(), anyString());
    }
}
