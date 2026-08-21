package ooo.klae.connex.backend.connectedaccounts.capture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import ooo.klae.connex.backend.connectedaccounts.ConnectedAccountProperties;
import ooo.klae.connex.backend.connectedaccounts.ConnectedAccountProviders;
import ooo.klae.connex.backend.dto.ProviderCaptureOverviewDto;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.services.AuditService;
import ooo.klae.connex.backend.services.SessionSecurityService;
import ooo.klae.connex.backend.services.WorkspaceService;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

class ProviderCaptureErasureServiceTest {

    @Test
    void erasureRequiresRecentAuthenticationAndLockedActiveMembership() {
        Fixture fixture = fixture();

        ProviderCaptureOverviewDto.PurgeState result =
            fixture.service().eraseCurrent("google");

        assertEquals("idle", result.status());
        InOrder order = inOrder(
            fixture.sessionSecurityService(),
            fixture.workspaceService(),
            fixture.purgeService());
        order.verify(fixture.sessionSecurityService())
            .requireRecentAuthentication(9);
        order.verify(fixture.workspaceService()).lockAndRequireMember(7, 9);
        order.verify(fixture.purgeService()).purge(7, 9, "google");
    }

    @Test
    void erasureIsIdempotentWithoutConnectionOrCaptureAvailabilityChecks() {
        Fixture fixture = fixture();

        fixture.service().eraseCurrent("google");
        fixture.service().eraseCurrent("google");

        verify(fixture.purgeService(), org.mockito.Mockito.times(2))
            .purge(7, 9, "google");
        verify(fixture.workspaceService(), never())
            .requirePermission(
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void unknownProviderFailsBeforeDestructiveWork() {
        Fixture fixture = fixture();

        assertThrows(
            ResourceNotFoundException.class,
            () -> fixture.service().eraseCurrent("slack"));

        verify(fixture.purgeService(), never())
            .purge(
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyString());
    }

    private static Fixture fixture() {
        WorkspaceService workspaceService = mock(WorkspaceService.class);
        SessionSecurityService sessionSecurityService =
            mock(SessionSecurityService.class);
        ProviderCapturePurgeService purgeService =
            mock(ProviderCapturePurgeService.class);
        AuditService auditService = mock(AuditService.class);
        PlatformTransactionManager transactionManager =
            mock(PlatformTransactionManager.class);
        TenantWorkScope tenantWorkScope = mock(TenantWorkScope.class);
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(7);
        when(workspaceService.getCurrentUserId()).thenReturn(9);
        when(workspaceService.getOrgId(7)).thenReturn(3);
        when(transactionManager.getTransaction(
                org.mockito.ArgumentMatchers.any()))
            .thenReturn(new SimpleTransactionStatus());
        when(tenantWorkScope.unrouted(
                org.mockito.ArgumentMatchers.<Supplier<Object>>any()))
            .thenAnswer(invocation -> invocation
                .<Supplier<Object>>getArgument(0).get());
        ProviderCaptureErasureService service =
            new ProviderCaptureErasureService(
                new ConnectedAccountProviders(new ConnectedAccountProperties()),
                workspaceService,
                sessionSecurityService,
                purgeService,
                auditService,
                transactionManager,
                tenantWorkScope);
        return new Fixture(
            service,
            workspaceService,
            sessionSecurityService,
            purgeService);
    }

    private record Fixture(
        ProviderCaptureErasureService service,
        WorkspaceService workspaceService,
        SessionSecurityService sessionSecurityService,
        ProviderCapturePurgeService purgeService
    ) {
    }
}
