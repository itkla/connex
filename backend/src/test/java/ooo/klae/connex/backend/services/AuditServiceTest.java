package ooo.klae.connex.backend.services;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import tools.jackson.databind.ObjectMapper;

import ooo.klae.connex.backend.mappers.AuditLogMapper;
import ooo.klae.connex.backend.tenant.TenantContext;

@ExtendWith(MockitoExtension.class)
class AuditServiceTest {
    @Mock private AuditLogMapper auditLogMapper;
    @Mock private ObjectMapper objectMapper;
    @Mock private TenantContext tenantContext;

    private AuditService service;

    @BeforeEach
    void setUp() {
        service = new AuditService(auditLogMapper, objectMapper, tenantContext);
        when(tenantContext.getWorkspaceId()).thenReturn(7);
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
}
