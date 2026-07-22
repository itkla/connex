package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ooo.klae.connex.backend.beans.AuditIntegrityHead;
import ooo.klae.connex.backend.beans.AuditLog;
import ooo.klae.connex.backend.config.AuditIntegrityProperties;
import ooo.klae.connex.backend.mappers.AuditIntegrityMapper;
import ooo.klae.connex.backend.mappers.AuditLogMapper;
import ooo.klae.connex.backend.mappers.OrganizationMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class AuditIntegrityLockOrderTest {
    private static final String GENESIS_HASH =
            "0000000000000000000000000000000000000000000000000000000000000000";

    @Mock private AuditLogMapper auditLogMapper;
    @Mock private AuditIntegrityMapper auditIntegrityMapper;
    @Mock private UserMapper userMapper;
    @Mock private WorkspaceMapper workspaceMapper;
    @Mock private OrganizationMapper organizationMapper;

    private AuditIntegrityService service;

    @BeforeEach
    void setUp() {
        AuditIntegrityProperties properties = new AuditIntegrityProperties();
        properties.setHmacSecret("test-audit-integrity-hmac-secret-change-me");
        service = new AuditIntegrityService(
                auditLogMapper,
                auditIntegrityMapper,
                userMapper,
                workspaceMapper,
                organizationMapper,
                properties,
                new ObjectMapper(),
                Clock.fixed(Instant.parse("2026-07-21T12:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void independentAppendLocksForeignKeyParentsBeforeIntegrityHead() {
        AuditLog entry = new AuditLog();
        entry.setActorId(9);
        entry.setWorkspaceId(7);
        entry.setOrgId(3);
        entry.setAction("ai.llm.call");
        entry.setEntityType("ai_call");
        entry.setOutcome("success");
        entry.setSummary("AI call success");
        AuditIntegrityHead head = new AuditIntegrityHead();
        head.setScopeType("workspace");
        head.setScopeId(7);
        head.setNextChainIndex(1);
        head.setCurrentHash(GENESIS_HASH);
        when(userMapper.lockByIdForShare(9)).thenReturn(9);
        when(workspaceMapper.lockWorkspaceForShare(7)).thenReturn(7);
        when(organizationMapper.lockByIdForShare(3)).thenReturn(3);
        when(auditIntegrityMapper.lockHead("workspace", 7)).thenReturn(head);
        when(auditIntegrityMapper.advanceHead(eq("workspace"), eq(7), eq(1L), eq(2L), any()))
                .thenReturn(1);

        service.appendIndependent(entry);

        InOrder order = inOrder(
                userMapper, workspaceMapper, organizationMapper, auditIntegrityMapper, auditLogMapper);
        order.verify(userMapper).lockByIdForShare(9);
        order.verify(workspaceMapper).lockWorkspaceForShare(7);
        order.verify(organizationMapper).lockByIdForShare(3);
        order.verify(auditIntegrityMapper).ensureHead("workspace", 7, GENESIS_HASH);
        order.verify(auditIntegrityMapper).lockHead("workspace", 7);
        order.verify(auditLogMapper).insert(entry);
        order.verify(auditIntegrityMapper).advanceHead("workspace", 7, 1, 2, entry.getRowHash());
    }

    @Test
    void missingActorFallsBackToCapturedLabelBeforeInsert() {
        AuditLog entry = new AuditLog();
        entry.setActorId(9);
        entry.setActorLabel("Deleted User");
        entry.setOrgId(3);
        entry.setAction("secret_store.secret.use");
        entry.setEntityType("organization");
        entry.setOutcome("success");
        entry.setSummary("Secret used");
        AuditIntegrityHead head = new AuditIntegrityHead();
        head.setScopeType("organization");
        head.setScopeId(3);
        head.setNextChainIndex(1);
        head.setCurrentHash(GENESIS_HASH);
        when(userMapper.lockByIdForShare(9)).thenReturn(null);
        when(organizationMapper.lockByIdForShare(3)).thenReturn(3);
        when(auditIntegrityMapper.lockHead("organization", 3)).thenReturn(head);
        when(auditIntegrityMapper.advanceHead(eq("organization"), eq(3), eq(1L), eq(2L), any()))
                .thenReturn(1);

        service.appendIndependent(entry);

        assertNull(entry.getActorId());
        assertEquals("Deleted User", entry.getActorLabel());
        InOrder order = inOrder(userMapper, organizationMapper, auditIntegrityMapper, auditLogMapper);
        order.verify(userMapper).lockByIdForShare(9);
        order.verify(organizationMapper).lockByIdForShare(3);
        order.verify(auditIntegrityMapper).ensureHead("organization", 3, GENESIS_HASH);
        order.verify(auditLogMapper).insert(entry);
    }
}
