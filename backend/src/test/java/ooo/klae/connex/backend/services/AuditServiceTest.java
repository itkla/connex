package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import tools.jackson.databind.ObjectMapper;

import ooo.klae.connex.backend.beans.AuditLog;
import ooo.klae.connex.backend.mappers.AuditLogMapper;
import ooo.klae.connex.backend.tenant.TenantContext;
import ooo.klae.connex.backend.util.ClientIpResolver;

@ExtendWith(MockitoExtension.class)
class AuditServiceTest {
    @Mock private AuditLogMapper auditLogMapper;
    @Mock private AuditIntegrityService auditIntegrityService;
    @Mock private TenantContext tenantContext;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private AuditService service;

    @BeforeEach
    void setUp() {
        service = new AuditService(auditLogMapper, auditIntegrityService, objectMapper, tenantContext, new ClientIpResolver(""));
        lenient().when(tenantContext.getWorkspaceId()).thenReturn(7);
    }

    @Test
    void recentScopesToWorkspaceAndForwardsLimitAndOffset() {
        when(auditLogMapper.findRecent(7, 50, 200)).thenReturn(List.of());
        service.recent(50, 200);
        verify(auditLogMapper).findRecent(7, 50, 200);
    }

    @Test
    void recentCapsLimitAtTwoHundred() {
        when(auditLogMapper.findRecent(7, 200, 400)).thenReturn(List.of());
        service.recent(5000, 400);
        verify(auditLogMapper).findRecent(7, 200, 400);
    }

    @Test
    void recentFloorsLimitAtOne() {
        when(auditLogMapper.findRecent(7, 1, 0)).thenReturn(List.of());
        service.recent(0, 0);
        verify(auditLogMapper).findRecent(7, 1, 0);
    }

    @Test
    void recentClampsNegativeOffsetToZero() {
        when(auditLogMapper.findRecent(7, 50, 0)).thenReturn(List.of());
        service.recent(50, -20);
        verify(auditLogMapper).findRecent(7, 50, 0);
    }

    @Test
    void recentCapsExcessiveOffset() {
        when(auditLogMapper.findRecent(7, 50, 100_000)).thenReturn(List.of());
        service.recent(50, Integer.MAX_VALUE);
        verify(auditLogMapper).findRecent(7, 50, 100_000);
    }

    @Test
    void forEntityScopesToWorkspaceWithLimitAndOffset() {
        when(auditLogMapper.findByEntity(7, "company", 12, 100, 300)).thenReturn(List.of());
        service.forEntity("company", 12, 100, 300);
        verify(auditLogMapper).findByEntity(7, "company", 12, 100, 300);
    }

    @Test
    void forEntityCapsLimitAndClampsOffset() {
        when(auditLogMapper.findByEntity(7, "person", 3, 200, 0)).thenReturn(List.of());
        service.forEntity("person", 3, 999, -5);
        verify(auditLogMapper).findByEntity(7, "person", 3, 200, 0);
    }

    @Test
    void exportRecentScopesToWorkspaceAndCapsLimitAtTenThousand() {
        AuditLog entry = new AuditLog();
        entry.setAction("audit.export");
        entry.setSummary("=formula [Private](note:42)");
        entry.setRowHash("abc123");
        when(auditLogMapper.findWorkspaceExport(7, 10_000, 0)).thenReturn(List.of(entry));
        when(auditIntegrityService.integrityPayload(entry))
            .thenReturn("{\"summary\":\"=formula [Private](note:42)\"}");

        String csv = service.exportRecent(50_000, -1);

        verify(auditLogMapper).findWorkspaceExport(7, 10_000, 0);
        assertTrue(csv.contains("rowHash"));
        assertTrue(csv.contains("integrityPayloadRedacted"));
        assertTrue(csv.contains("contentRedacted"));
        assertTrue(csv.contains("'=formula a note"));
        assertFalse(csv.contains("Private"));
        assertTrue(csv.contains("abc123"));
    }

    @Test
    void recordSanitizesReferenceContentBeforeIntegrityHashing() {
        service.record(
            "deal.update",
            "deal",
            12,
            "Deal [Private](note:42)",
            "Updated [Private](note:42)",
            Map.of("closedReason", Map.of("old", "", "new", "See [Private](note:42)"))
        );

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditIntegrityService).append(captor.capture());
        AuditLog entry = captor.getValue();
        assertEquals("Deal a note", entry.getTargetLabel());
        assertEquals("Updated a note", entry.getSummary());
        assertFalse(entry.getChanges().contains("Private"));
        assertTrue(entry.getChanges().contains("See a note"));
    }

    @Test
    void strictIndependentScopedRecordUsesExactScopeAndIndependentAppend() {
        service.recordStrictIndependentScoped(
            "ai.llm.call", "ai_call", null, 17, 23, "bedrock/us-east-1", "AI call attempt",
            Map.of("outcome", "attempt"));

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditIntegrityService).appendIndependent(captor.capture());
        AuditLog entry = captor.getValue();
        assertEquals(17, entry.getWorkspaceId());
        assertEquals(23, entry.getOrgId());
        assertEquals("ai.llm.call", entry.getAction());
        assertTrue(entry.getChanges().contains("attempt"));
    }

    @Test
    void strictIndependentScopedRecordPropagatesPersistenceFailure() {
        IllegalStateException failure = new IllegalStateException("audit unavailable");
        doThrow(failure).when(auditIntegrityService).appendIndependent(any(AuditLog.class));

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
            () -> service.recordStrictIndependentScoped(
                "ai.llm.call", "ai_call", null, 17, 23, "bedrock/us-east-1", "AI call attempt",
                Map.of("outcome", "attempt")));

        assertEquals(failure, thrown);
    }

    @Test
    void independentScopedRecordRemainsBestEffort() {
        doThrow(new IllegalStateException("audit unavailable"))
            .when(auditIntegrityService).appendIndependent(any(AuditLog.class));

        assertDoesNotThrow(() -> service.recordIndependentScoped(
            "ai.llm.call", "ai_call", null, 17, 23, "bedrock/us-east-1", "AI call success",
            Map.of("outcome", "success")));
    }

    @Test
    void recentRedactsLegacyContentWithoutChangingIntegrityMetadata() {
        AuditLog entry = new AuditLog();
        entry.setTargetLabel("Task [Private](note:42)");
        entry.setSummary("Updated [Private](note:42)");
        entry.setChanges("{\"description\":{\"new\":\"Review [Private](note:42)\"}}");
        entry.setRowHash("raw-row-hash");
        when(auditLogMapper.findRecent(7, 25, 0)).thenReturn(List.of(entry));

        AuditLog result = service.recent(25, 0).getFirst();

        assertEquals("Task a note", result.getTargetLabel());
        assertEquals("Updated a note", result.getSummary());
        assertFalse(result.getChanges().contains("Private"));
        assertTrue(result.getChanges().contains("Review a note"));
        assertEquals("raw-row-hash", result.getRowHash());
        assertTrue(result.isContentRedacted());
    }

    @Test
    void exportForEntityScopesToWorkspaceAndForwardsEntity() {
        when(auditLogMapper.findByEntity(7, "company", 12, 25, 50)).thenReturn(List.of());
        service.exportForEntity("company", 12, 25, 50);
        verify(auditLogMapper).findByEntity(7, "company", 12, 25, 50);
    }
}
