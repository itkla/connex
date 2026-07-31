package ooo.klae.connex.backend.connectedaccounts.capture;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;

import ooo.klae.connex.backend.connectedaccounts.ConnectedCaptureProperties;
import ooo.klae.connex.backend.mappers.IdentityMapper;
import ooo.klae.connex.backend.mappers.ProviderCaptureMapper;
import ooo.klae.connex.backend.mappers.ProviderConnectionMapper;
import ooo.klae.connex.backend.services.DuplicateDecisionLockService;
import ooo.klae.connex.backend.services.MatchingService;
import ooo.klae.connex.backend.services.ProviderCaptureHistoricalBaselineService;
import ooo.klae.connex.backend.services.WorkspaceService;

class ProviderCaptureBodyAccessTest {

    @Test
    void rejectsPrivateAndExcludedMetadataBeforeBodyRetrieval() {
        MatchingService matchingService = mock(MatchingService.class);
        ProviderCapturePagePersistence persistence =
            new ProviderCapturePagePersistence(
                mock(ProviderCaptureMapper.class),
                mock(ProviderConnectionMapper.class),
                mock(IdentityMapper.class),
                matchingService,
                new ConnectedCaptureProperties(),
                new ObjectMapper(),
                mock(DuplicateDecisionLockService.class),
                mock(WorkspaceService.class),
                mock(ProviderCaptureHistoricalBaselineService.class));
        ProviderCaptureItem privateItem = new ProviderCaptureItem(
            "event-1",
            "version-1",
            null,
            "meeting",
            "Private",
            null,
            Instant.parse("2026-07-30T09:00:00Z"),
            Instant.parse("2026-07-30T10:00:00Z"),
            true,
            false,
            List.of());
        ProviderCaptureItem excludedDomainItem = new ProviderCaptureItem(
            "mail-1",
            "version-1",
            "thread-1",
            "email",
            "Excluded",
            null,
            Instant.parse("2026-07-30T09:00:00Z"),
            null,
            false,
            false,
            List.of(new ProviderCaptureParticipant(
                "from", "Customer", "customer@example.net")));
        when(matchingService.extractCompanyDomainFromEmail(
                "customer@example.net"))
            .thenReturn(java.util.Optional.of("example.net"));
        when(matchingService.extractCompanyDomainFromEmail(
                "owner@example.test"))
            .thenReturn(java.util.Optional.of("example.test"));
        CaptureExecutionPolicy privatePolicy = policy(List.of());
        CaptureExecutionPolicy excludedDomainPolicy =
            policy(List.of("example.net"));

        assertFalse(persistence.bodyAllowed(
            privateItem, privatePolicy, "owner@example.test"));
        assertFalse(persistence.bodyAllowed(
            excludedDomainItem,
            excludedDomainPolicy,
            "owner@example.test"));
    }

    private static CaptureExecutionPolicy policy(List<String> excludedDomains) {
        return new CaptureExecutionPolicy(
            true,
            true,
            true,
            true,
            90,
            true,
            "review",
            true,
            false,
            excludedDomains,
            List.of(),
            List.of(),
            1);
    }
}
