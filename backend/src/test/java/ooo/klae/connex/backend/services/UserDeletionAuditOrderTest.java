package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.publicapi.ApiCredentialLifecycleService;
import ooo.klae.connex.backend.publicapi.ApiCredentialReferenceRoot;
import ooo.klae.connex.backend.publicapi.PendingCredentialAudit;
import ooo.klae.connex.backend.services.UserDeletionTransaction.AuditScopeKey;
import ooo.klae.connex.backend.storage.ManagedObjectService;
import ooo.klae.connex.backend.tenant.TenantContext;

/**
 * Pins account erasure's audit emission order. Account erasure is the only transaction that appends
 * into more than one audit-integrity chain, so it must acquire every head in one canonical order.
 */
class UserDeletionAuditOrderTest {

    private static final String OWNER = "audit-order-owner";

    @AfterEach
    void clearTenantContext() {
        new TenantContext().clear();
    }

    @Test
    void erasureEmitsEveryAuditInSortedScopeOrderBeforeTheAccountDelete() {
        Fixture fixture = new Fixture();
        fixture.credentialAudits(
            new RecordingCredentialAudit(30, 8, fixture.emitted),
            new RecordingCredentialAudit(5, 2, fixture.emitted),
            new RecordingCredentialAudit(30, 8, fixture.emitted));
        fixture.tenantContext.set(13, 6, 9, "member", null);

        fixture.transaction.delete(9, OWNER);

        assertEquals(
            List.of("workspace:5", "workspace:13", "workspace:30", "workspace:30"),
            fixture.emitted);
        InOrder order = inOrder(fixture.auditService, fixture.userMapper);
        order.verify(fixture.auditService).recordScoped(
            eq("user.delete"), eq("user"), eq(9), eq(13), eq(6), any(), any(), any());
        order.verify(fixture.userMapper).delete(9);
    }

    @Test
    void erasureEmitsEveryCredentialAuditItsDeletionReturned() {
        Fixture fixture = new Fixture();
        RecordingCredentialAudit first = new RecordingCredentialAudit(30, 8, fixture.emitted);
        RecordingCredentialAudit second = new RecordingCredentialAudit(5, 2, fixture.emitted);
        fixture.credentialAudits(first, second);
        fixture.tenantContext.set(30, 8, 9, "member", null);

        fixture.transaction.delete(9, OWNER);

        assertEquals(1, first.emissions);
        assertEquals(1, second.emissions);
        assertEquals(3, fixture.emitted.size());
    }

    @Test
    void unresolvedTenantContextEmitsTheAccountAuditLast() {
        Fixture fixture = new Fixture();
        fixture.credentialAudits(new RecordingCredentialAudit(5, 2, fixture.emitted));

        fixture.transaction.delete(9, OWNER);

        assertEquals(List.of("workspace:5", "system:0"), fixture.emitted);
    }

    @Test
    void auditScopeKeyMirrorsTheChainScopeRule() {
        assertEquals(
            new AuditScopeKey(UserDeletionTransaction.WORKSPACE_SCOPE_RANK, 7),
            UserDeletionTransaction.auditScopeKey(7, 3));
        assertEquals(
            new AuditScopeKey(UserDeletionTransaction.ORGANIZATION_SCOPE_RANK, 3),
            UserDeletionTransaction.auditScopeKey(null, 3));
        assertEquals(
            new AuditScopeKey(UserDeletionTransaction.SYSTEM_SCOPE_RANK, 0),
            UserDeletionTransaction.auditScopeKey(null, null));
    }

    private static final class Fixture {
        private final UserMapper userMapper = mock(UserMapper.class);
        private final WorkspaceService workspaceService = mock(WorkspaceService.class);
        private final OrgMemberService orgMemberService = mock(OrgMemberService.class);
        private final ManagedObjectService managedObjectService = mock(ManagedObjectService.class);
        private final AuditService auditService = mock(AuditService.class);
        private final ApiCredentialLifecycleService lifecycleService =
            mock(ApiCredentialLifecycleService.class);
        private final TenantContext tenantContext = new TenantContext();
        private final List<String> emitted = new ArrayList<>();
        private final UserDeletionTransaction transaction;

        private Fixture() {
            tenantContext.clear();
            User before = new User();
            before.setId(9);
            before.setUsername("erased");
            when(userMapper.isAccountDeletionReservationOwner(9, OWNER)).thenReturn(true);
            when(userMapper.lockById(9)).thenReturn(9);
            when(userMapper.getUserById(9)).thenReturn(before);
            when(workspaceService.discoverOwnedWorkspaceIds(9)).thenReturn(List.of());
            when(lifecycleService.discoverAccountReferenceRoots(9))
                .thenReturn(List.of(new ApiCredentialReferenceRoot(5, 2)));
            doAnswer(invocation -> {
                Integer workspaceId = invocation.getArgument(3);
                Integer orgId = invocation.getArgument(4);
                emitted.add(scopeLabel(workspaceId, orgId));
                return null;
            }).when(auditService).recordScoped(
                eq("user.delete"), any(), anyInt(), any(), any(), any(), any(), any());
            transaction = new UserDeletionTransaction(
                userMapper,
                workspaceService,
                orgMemberService,
                managedObjectService,
                auditService,
                lifecycleService,
                tenantContext);
        }

        private void credentialAudits(PendingCredentialAudit... audits) {
            when(lifecycleService.deleteForAccount(eq(9), any())).thenReturn(List.of(audits));
        }
    }

    private static String scopeLabel(Integer workspaceId, Integer orgId) {
        if (workspaceId != null) {
            return "workspace:" + workspaceId;
        }
        if (orgId != null) {
            return "organization:" + orgId;
        }
        return "system:0";
    }

    private static final class RecordingCredentialAudit implements PendingCredentialAudit {
        private final int workspaceId;
        private final int organizationId;
        private final List<String> emitted;
        private int emissions;

        private RecordingCredentialAudit(
                int workspaceId, int organizationId, List<String> emitted) {
            this.workspaceId = workspaceId;
            this.organizationId = organizationId;
            this.emitted = emitted;
        }

        @Override
        public int workspaceId() {
            return workspaceId;
        }

        @Override
        public int organizationId() {
            return organizationId;
        }

        @Override
        public void emit() {
            emissions++;
            emitted.add(scopeLabel(workspaceId, organizationId));
        }
    }
}
