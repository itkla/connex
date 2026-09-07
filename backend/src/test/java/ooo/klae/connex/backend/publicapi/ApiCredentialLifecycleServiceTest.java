package ooo.klae.connex.backend.publicapi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import ooo.klae.connex.backend.mappers.ApiCredentialMapper;
import ooo.klae.connex.backend.mappers.OrganizationMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.services.AuditService;

class ApiCredentialLifecycleServiceTest {

    @Test
    void accountErasureSortsWorkspaceRootsAndExactCredentialChildrenBeforeDeletion() {
        ApiCredentialMapper mapper = mock(ApiCredentialMapper.class);
        WorkspaceMapper workspaceMapper = mock(WorkspaceMapper.class);
        OrganizationMapper organizationMapper = mock(OrganizationMapper.class);
        AuditService auditService = mock(AuditService.class);
        ApiCredentialLifecycleService service =
            new ApiCredentialLifecycleService(
                mapper, workspaceMapper, organizationMapper, auditService);
        ApiCredential later = credential(22, 9, 4, 17);
        ApiCredential first = credential(11, 3, 2, 17);
        ApiCredential middle = credential(12, 3, 2, 17);
        when(mapper.listByAccountReference(17)).thenReturn(List.of(later, middle, first));
        when(mapper.findByIdForUpdate(3, 11)).thenReturn(first);
        when(mapper.findByIdForUpdate(3, 12)).thenReturn(middle);
        when(mapper.findByIdForUpdate(9, 22)).thenReturn(later);
        when(mapper.deleteById(3, 11)).thenReturn(1);
        when(mapper.deleteById(3, 12)).thenReturn(1);
        when(mapper.deleteById(9, 22)).thenReturn(1);

        List<PendingCredentialAudit> pending = service.deleteForAccount(17, List.of(
            new ApiCredentialReferenceRoot(3, 2),
            new ApiCredentialReferenceRoot(9, 4)));

        InOrder order = inOrder(mapper);
        order.verify(mapper).listByAccountReference(17);
        order.verify(mapper).findByIdForUpdate(3, 11);
        order.verify(mapper).findByIdForUpdate(3, 12);
        order.verify(mapper).deleteById(3, 11);
        order.verify(mapper).deleteById(3, 12);
        order.verify(mapper).findByIdForUpdate(9, 22);
        order.verify(mapper).deleteById(9, 22);
        verifyNoInteractions(workspaceMapper, organizationMapper, auditService);

        assertEquals(
            List.of(List.of(3, 2), List.of(3, 2), List.of(9, 4)),
            pending.stream()
                .map(audit -> List.of(audit.workspaceId(), audit.organizationId()))
                .toList());
        pending.forEach(PendingCredentialAudit::emit);
        InOrder auditOrder = inOrder(auditService);
        auditOrder.verify(auditService).recordStrictScoped(
            "api_credential.account_erased",
            "api_credential",
            null,
            3,
            2,
            "Credential 11 (last4 0011)",
            "Deleted an API credential during identity lifecycle cleanup",
            java.util.Map.of("credentialId", 11L, "last4", "0011"));
        auditOrder.verify(auditService).recordStrictScoped(
            "api_credential.account_erased",
            "api_credential",
            null,
            3,
            2,
            "Credential 12 (last4 0012)",
            "Deleted an API credential during identity lifecycle cleanup",
            java.util.Map.of("credentialId", 12L, "last4", "0012"));
        auditOrder.verify(auditService).recordStrictScoped(
            "api_credential.account_erased",
            "api_credential",
            null,
            9,
            4,
            "Credential 22 (last4 0022)",
            "Deleted an API credential during identity lifecycle cleanup",
            java.util.Map.of("credentialId", 22L, "last4", "0022"));
    }

    @Test
    void membershipCleanupStillDeletesAndAuditsWhileWorkspaceIsTearingDown() {
        ApiCredentialMapper mapper = mock(ApiCredentialMapper.class);
        WorkspaceMapper workspaceMapper = mock(WorkspaceMapper.class);
        OrganizationMapper organizationMapper = mock(OrganizationMapper.class);
        AuditService auditService = mock(AuditService.class);
        ApiCredentialLifecycleService service =
            new ApiCredentialLifecycleService(
                mapper, workspaceMapper, organizationMapper, auditService);
        ApiCredential credential = credential(11, 3, 2, 17);
        when(workspaceMapper.lockWorkspaceOrgIdForShare(3)).thenReturn(2);
        when(organizationMapper.lockByIdForShare(2)).thenReturn(2);
        when(mapper.listByMembershipForUpdate(3, 17)).thenReturn(List.of(credential));
        when(mapper.deleteByMembership(3, 17)).thenReturn(1);

        service.deleteForMembership(3, 17);

        InOrder order = inOrder(workspaceMapper, organizationMapper, mapper, auditService);
        order.verify(workspaceMapper).lockWorkspaceOrgIdForShare(3);
        order.verify(organizationMapper).lockByIdForShare(2);
        order.verify(mapper).listByMembershipForUpdate(3, 17);
        order.verify(mapper).deleteByMembership(3, 17);
        order.verify(auditService).recordStrictScoped(
            "api_credential.membership_removed",
            "api_credential",
            null,
            3,
            2,
            "Credential 11 (last4 0011)",
            "Deleted an API credential during identity lifecycle cleanup",
            java.util.Map.of("credentialId", 11L, "last4", "0011"));
        verify(workspaceMapper, never()).lockActiveWorkspaceForShare(anyInt());
        verify(organizationMapper, never()).lockActiveByIdForShare(anyInt());
    }

    @Test
    void membershipCleanupWorkspaceRootLockCarriesNoLifecycleStatePredicate() throws Exception {
        String xml;
        try (InputStream input = ApiCredentialLifecycleServiceTest.class.getClassLoader()
                .getResourceAsStream("mappers/WorkspaceMapper.xml")) {
            assertNotNull(input, "Missing mappers/WorkspaceMapper.xml");
            xml = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        int start = xml.indexOf("id=\"lockWorkspaceOrgIdForShare\"");
        assertTrue(start > 0, "lockWorkspaceOrgIdForShare must exist");
        String statement = xml.substring(start, xml.indexOf("</select>", start));
        assertFalse(statement.contains("lifecycle_state"));
        assertTrue(statement.contains("FOR SHARE"));
        assertTrue(statement.contains("org_id"));
    }

    @Test
    void membershipCleanupSkipsCredentialChildrenWhenWorkspaceRootIsGone() {
        ApiCredentialMapper mapper = mock(ApiCredentialMapper.class);
        WorkspaceMapper workspaceMapper = mock(WorkspaceMapper.class);
        OrganizationMapper organizationMapper = mock(OrganizationMapper.class);
        AuditService auditService = mock(AuditService.class);
        ApiCredentialLifecycleService service =
            new ApiCredentialLifecycleService(
                mapper, workspaceMapper, organizationMapper, auditService);
        when(workspaceMapper.lockWorkspaceOrgIdForShare(3)).thenReturn(null);

        service.deleteForMembership(3, 17);

        verify(workspaceMapper).lockWorkspaceOrgIdForShare(3);
        verify(workspaceMapper, never()).lockActiveWorkspaceForShare(anyInt());
        verifyNoInteractions(mapper, organizationMapper, auditService);
    }

    @Test
    void membershipCleanupSkipsCredentialChildrenWhenOrganizationRootIsGone() {
        ApiCredentialMapper mapper = mock(ApiCredentialMapper.class);
        WorkspaceMapper workspaceMapper = mock(WorkspaceMapper.class);
        OrganizationMapper organizationMapper = mock(OrganizationMapper.class);
        AuditService auditService = mock(AuditService.class);
        ApiCredentialLifecycleService service =
            new ApiCredentialLifecycleService(
                mapper, workspaceMapper, organizationMapper, auditService);
        when(workspaceMapper.lockWorkspaceOrgIdForShare(3)).thenReturn(2);
        when(organizationMapper.lockByIdForShare(2)).thenReturn(null);

        service.deleteForMembership(3, 17);

        verify(organizationMapper).lockByIdForShare(2);
        verifyNoInteractions(mapper, auditService);
    }

    @Test
    void accountErasureSkipsCredentialChildThatVanishedAfterRootDiscovery() {
        ApiCredentialMapper mapper = mock(ApiCredentialMapper.class);
        WorkspaceMapper workspaceMapper = mock(WorkspaceMapper.class);
        OrganizationMapper organizationMapper = mock(OrganizationMapper.class);
        AuditService auditService = mock(AuditService.class);
        ApiCredentialLifecycleService service =
            new ApiCredentialLifecycleService(
                mapper, workspaceMapper, organizationMapper, auditService);
        ApiCredential vanished = credential(11, 3, 2, 17);
        ApiCredential surviving = credential(12, 3, 2, 17);
        when(mapper.listByAccountReference(17)).thenReturn(List.of(vanished, surviving));
        when(mapper.findByIdForUpdate(3, 12)).thenReturn(surviving);
        when(mapper.deleteById(3, 12)).thenReturn(1);

        List<PendingCredentialAudit> pending = service.deleteForAccount(
            17, List.of(new ApiCredentialReferenceRoot(3, 2)));

        verify(mapper).listByAccountReference(17);
        verify(mapper).findByIdForUpdate(3, 11);
        verify(mapper, never()).deleteById(3, 11);
        verify(mapper).deleteById(3, 12);
        assertEquals(
            List.of(List.of(3, 2)),
            pending.stream()
                .map(audit -> List.of(audit.workspaceId(), audit.organizationId()))
                .toList());
        verifyNoInteractions(workspaceMapper, organizationMapper);
        verifyNoInteractions(auditService);
    }

    @Test
    void accountErasureConvergesWhenEveryPlannedCredentialWasDeletedByAnotherErasure() {
        ApiCredentialMapper mapper = mock(ApiCredentialMapper.class);
        WorkspaceMapper workspaceMapper = mock(WorkspaceMapper.class);
        OrganizationMapper organizationMapper = mock(OrganizationMapper.class);
        AuditService auditService = mock(AuditService.class);
        ApiCredentialLifecycleService service =
            new ApiCredentialLifecycleService(
                mapper, workspaceMapper, organizationMapper, auditService);
        when(mapper.listByAccountReference(17)).thenReturn(
            List.of(credential(11, 3, 2, 17), credential(12, 3, 2, 17)));

        assertEquals(
            List.of(),
            service.deleteForAccount(17, List.of(new ApiCredentialReferenceRoot(3, 2))));

        verify(mapper).findByIdForUpdate(3, 11);
        verify(mapper).findByIdForUpdate(3, 12);
        verify(mapper, never()).deleteById(anyInt(), anyLong());
        verifyNoInteractions(workspaceMapper, organizationMapper, auditService);
    }

    @Test
    void accountErasureDeletesRevokerCredentialWhileWorkspaceIsTearingDown() {
        ApiCredentialMapper mapper = mock(ApiCredentialMapper.class);
        WorkspaceMapper workspaceMapper = mock(WorkspaceMapper.class);
        OrganizationMapper organizationMapper = mock(OrganizationMapper.class);
        AuditService auditService = mock(AuditService.class);
        ApiCredentialLifecycleService service =
            new ApiCredentialLifecycleService(
                mapper, workspaceMapper, organizationMapper, auditService);
        ApiCredential credential = credential(11, 3, 2, 99);
        credential.setRevokedById(17);
        when(mapper.listByAccountReference(17)).thenReturn(List.of(credential));
        when(mapper.findByIdForUpdate(3, 11)).thenReturn(credential);
        when(mapper.deleteById(3, 11)).thenReturn(1);

        List<PendingCredentialAudit> pending = service.deleteForAccount(
            17, List.of(new ApiCredentialReferenceRoot(3, 2)));

        verify(mapper).deleteById(3, 11);
        verifyNoInteractions(auditService);
        assertEquals(1, pending.size());
        pending.forEach(PendingCredentialAudit::emit);
        verify(auditService).recordStrictScoped(
            "api_credential.account_erased",
            "api_credential",
            null,
            3,
            2,
            "Credential 11 (last4 0011)",
            "Deleted an API credential during identity lifecycle cleanup",
            java.util.Map.of("credentialId", 11L, "last4", "0011"));
        verifyNoInteractions(workspaceMapper, organizationMapper);
    }

    @Test
    void discoverAccountReferenceRootsIsAscendingAndDistinct() {
        ApiCredentialMapper mapper = mock(ApiCredentialMapper.class);
        WorkspaceMapper workspaceMapper = mock(WorkspaceMapper.class);
        OrganizationMapper organizationMapper = mock(OrganizationMapper.class);
        AuditService auditService = mock(AuditService.class);
        ApiCredentialLifecycleService service =
            new ApiCredentialLifecycleService(
                mapper, workspaceMapper, organizationMapper, auditService);
        when(mapper.listAccountReferenceRoots(17)).thenReturn(List.of(
            new ApiCredentialReferenceRoot(9, 4),
            new ApiCredentialReferenceRoot(3, 2),
            new ApiCredentialReferenceRoot(9, 4)));

        assertEquals(List.of(
            new ApiCredentialReferenceRoot(3, 2),
            new ApiCredentialReferenceRoot(9, 4)),
            service.discoverAccountReferenceRoots(17));
        verifyNoInteractions(workspaceMapper, organizationMapper, auditService);
    }

    @Test
    void accountErasureRejectsCredentialOutsideTheLockedRootPairs() {
        ApiCredentialMapper mapper = mock(ApiCredentialMapper.class);
        WorkspaceMapper workspaceMapper = mock(WorkspaceMapper.class);
        OrganizationMapper organizationMapper = mock(OrganizationMapper.class);
        AuditService auditService = mock(AuditService.class);
        ApiCredentialLifecycleService service =
            new ApiCredentialLifecycleService(
                mapper, workspaceMapper, organizationMapper, auditService);
        when(mapper.listByAccountReference(17)).thenReturn(List.of(credential(11, 3, 2, 17)));

        IllegalStateException failure = assertThrows(
            IllegalStateException.class,
            () -> service.deleteForAccount(
                17, List.of(new ApiCredentialReferenceRoot(3, 4))));

        assertEquals(
            "API credential account cleanup crossed an unlocked tenant root",
            failure.getMessage());
        verify(mapper, never()).deleteById(3, 11);
        verifyNoInteractions(workspaceMapper, organizationMapper, auditService);
    }

    private static ApiCredential credential(
            long id, int workspaceId, int organizationId, int accountId) {
        ApiCredential credential = new ApiCredential();
        credential.setId(id);
        credential.setWorkspaceId(workspaceId);
        credential.setOrganizationId(organizationId);
        credential.setCreatedById(accountId);
        credential.setTokenLast4(String.format("%04d", id));
        return credential;
    }
}
