package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ooo.klae.connex.backend.dto.WorkspaceLifecycleRef;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.exceptions.RecentAuthenticationRequiredException;
import ooo.klae.connex.backend.services.TenantExportGrantService.TenantExportGrant;
import ooo.klae.connex.backend.services.TenantExportService.TenantExportDownload;
import ooo.klae.connex.backend.services.TenantLifecycleControlOperations.AcquiredWorkspace;
import ooo.klae.connex.backend.services.TenantLifecycleControlOperations.OperationLease;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

@ExtendWith(MockitoExtension.class)
class TenantExportGrantServiceTest {
    private static final int ORG_ID = 3;
    private static final int WORKSPACE_ID = 5;
    private static final int ACTOR_ID = 7;
    private static final Instant NOW = Instant.parse("2026-08-08T12:00:00Z");

    @Mock private OrgMemberService orgMemberService;
    @Mock private SessionSecurityService sessionSecurityService;
    @Mock private TenantWorkScope tenantWorkScope;
    @Mock private TenantLifecycleControlOperations controlOperations;
    @Mock private TenantExportService tenantExportService;
    @Mock private TenantExportDownload download;

    private TenantExportGrantService service;

    @BeforeEach
    void setUp() {
        service = new TenantExportGrantService(
            orgMemberService,
            sessionSecurityService,
            tenantWorkScope,
            controlOperations,
            tenantExportService,
            Clock.fixed(NOW, ZoneOffset.UTC));
        org.mockito.Mockito.lenient().doAnswer(
            invocation -> invocation.<Supplier<?>>getArgument(0).get())
            .when(tenantWorkScope).unrouted(any());
    }

    @Test
    void issueRequiresOrgAdminAndRecentWebAuthnBeforePersistingBoundHashes() {
        ArgumentCaptor<byte[]> sessionHash = ArgumentCaptor.forClass(byte[].class);
        ArgumentCaptor<byte[]> tokenHash = ArgumentCaptor.forClass(byte[].class);

        TenantExportGrant grant = service.issue(ORG_ID, WORKSPACE_ID, ACTOR_ID, "session-a");

        InOrder order = inOrder(orgMemberService, sessionSecurityService, controlOperations);
        order.verify(orgMemberService).requireOrgAdmin(ORG_ID, ACTOR_ID);
        order.verify(sessionSecurityService).requireRecentAuthentication(ACTOR_ID);
        order.verify(controlOperations).issueExportGrant(
            org.mockito.ArgumentMatchers.eq(ORG_ID),
            org.mockito.ArgumentMatchers.eq(WORKSPACE_ID),
            org.mockito.ArgumentMatchers.eq(ACTOR_ID),
            sessionHash.capture(),
            tokenHash.capture(),
            org.mockito.ArgumentMatchers.eq(LocalDateTime.of(2026, 8, 8, 12, 2)),
            org.mockito.ArgumentMatchers.eq(LocalDateTime.of(2026, 8, 8, 12, 0)));
        assertEquals(64, grant.token().length());
        assertEquals(NOW.plus(TenantExportGrantService.GRANT_LIFETIME), grant.expiresAt());
        assertEquals(32, sessionHash.getValue().length);
        assertEquals(32, tokenHash.getValue().length);
        assertNotEquals(
            java.util.HexFormat.of().formatHex(sessionHash.getValue()),
            java.util.HexFormat.of().formatHex(tokenHash.getValue()));
    }

    @Test
    void issueDoesNotPersistWhenRecentAuthenticationIsMissing() {
        org.mockito.Mockito.doThrow(new RecentAuthenticationRequiredException())
            .when(sessionSecurityService)
            .requireRecentAuthentication(ACTOR_ID);

        assertThrows(
            RecentAuthenticationRequiredException.class,
            () -> service.issue(ORG_ID, WORKSPACE_ID, ACTOR_ID, "session-a"));

        verify(controlOperations, never()).issueExportGrant(
            anyInt(),
            anyInt(),
            anyInt(),
            any(),
            any(),
            any(),
            any());
    }

    @Test
    void redeemUsesTheGrantWithoutConsultingTheRecentAuthenticationStamp() {
        WorkspaceLifecycleRef workspace = new WorkspaceLifecycleRef(
            WORKSPACE_ID,
            ORG_ID,
            "Workspace",
            "workspace",
            "active");
        AcquiredWorkspace acquired = new AcquiredWorkspace(
            workspace,
            new OperationLease(ORG_ID, WORKSPACE_ID, "export", "lease"));
        when(controlOperations.redeemExportGrant(
                org.mockito.ArgumentMatchers.eq(ORG_ID),
                org.mockito.ArgumentMatchers.eq(WORKSPACE_ID),
                org.mockito.ArgumentMatchers.eq(ACTOR_ID),
                any(),
                any(),
                org.mockito.ArgumentMatchers.eq(LocalDateTime.of(2026, 8, 8, 12, 0))))
            .thenReturn(acquired);
        when(tenantExportService.prepareAcquired(ORG_ID, ACTOR_ID, acquired))
            .thenReturn(download);

        TenantExportDownload result = service.redeem(
            ORG_ID,
            WORKSPACE_ID,
            ACTOR_ID,
            "session-a",
            "a".repeat(64));

        assertSame(download, result);
        verify(orgMemberService).requireOrgAdmin(ORG_ID, ACTOR_ID);
        verify(sessionSecurityService, never()).requireRecentAuthentication(anyInt());
    }

    @Test
    void redeemRejectsNonAdminsBeforeGrantOrWorkspaceLookup() {
        org.mockito.Mockito.doThrow(new ForbiddenException("Organization administrator access required"))
            .when(orgMemberService)
            .requireOrgAdmin(ORG_ID, ACTOR_ID);

        assertThrows(
            ForbiddenException.class,
            () -> service.redeem(
                ORG_ID,
                WORKSPACE_ID,
                ACTOR_ID,
                "session-a",
                "a".repeat(64)));

        verify(controlOperations, never()).redeemExportGrant(
            anyInt(),
            anyInt(),
            anyInt(),
            any(),
            any(),
            any());
    }

    @Test
    void malformedGrantFailsBeforeControlPlaneAccess() {
        assertThrows(
            ForbiddenException.class,
            () -> service.redeem(
                ORG_ID,
                WORKSPACE_ID,
                ACTOR_ID,
                "session-a",
                "not-a-grant"));

        verify(controlOperations, never()).redeemExportGrant(
            anyInt(),
            anyInt(),
            anyInt(),
            any(),
            any(),
            any());
        verify(tenantExportService, never()).prepareAcquired(
            anyInt(),
            anyInt(),
            any());
    }
}
