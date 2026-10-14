package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import tools.jackson.databind.ObjectMapper;

import ooo.klae.connex.backend.beans.AuditLog;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.config.AuditIntegrityProperties;
import ooo.klae.connex.backend.mappers.AuditLogMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.observability.ClientAssertedCorrelationPseudonymizer;
import ooo.klae.connex.backend.services.AutomationScope.Origin;
import ooo.klae.connex.backend.services.AutomationScope.Source;
import ooo.klae.connex.backend.tenant.TenantCatalogResolver;
import ooo.klae.connex.backend.tenant.TenantContext;
import ooo.klae.connex.backend.tenant.TenantWorkScope;
import ooo.klae.connex.backend.util.ClientIpResolver;

@ExtendWith(MockitoExtension.class)
class AuditAutomationOriginTest {
    @Mock private AuditLogMapper auditLogMapper;
    @Mock private AuditIntegrityService integrityService;
    @Mock private WorkspaceMapper workspaceMapper;
    @Mock private TenantCatalogResolver catalogResolver;

    private final AutomationScope scope = new AutomationScope();
    private final TenantContext tenantContext = new TenantContext();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final User actor = new User();
    private AutomationExecutor executor;
    private AuditService auditService;

    @BeforeEach
    void setUp() {
        actor.setId(41);
        actor.setDisplayName("Run-as member");
        when(workspaceMapper.getOrgId(7)).thenReturn(8);
        when(catalogResolver.resolveCatalog(8)).thenReturn("cnx_test");
        executor = new AutomationExecutor(tenantContext, scope,
            new TenantWorkScope(tenantContext, catalogResolver, workspaceMapper));
        AuditIntegrityProperties properties = new AuditIntegrityProperties();
        properties.setHmacSecret("test-origin-hmac-secret-change-me");
        auditService = new AuditService(
            org.mockito.Mockito.mock(SessionSecurityService.class), auditLogMapper, integrityService,
            objectMapper, tenantContext, new ClientIpResolver(""),
            new ClientAssertedCorrelationPseudonymizer(properties), scope);
    }

    @AfterEach
    void clearThreadState() {
        tenantContext.clear();
        SecurityContextHolder.clearContext();
    }

    static Stream<Origin> automationOrigins() {
        return Stream.of(
            Origin.rule(23, 31, "user"), Origin.rule(23, 31, "system"),
            Origin.workflow(23, 931L, "user"), Origin.workflow(23, 931L, "system"));
    }

    @ParameterizedTest
    @MethodSource("automationOrigins")
    void automationOriginIsDistinctFromTheSameMemberActingByHand(Origin origin) {
        executor.runAs(7, actor, "member", origin, () -> {
            auditService.recordStrict("note.create", "note", 17, null, "Created note", null);
            return null;
        });
        assertFalse(scope.isActive());
        assertNull(scope.origin());
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(actor, null, actor.getAuthorities()));
        auditService.recordStrict("note.create", "note", 18, null, "Created note", null);

        ArgumentCaptor<AuditLog> entries = ArgumentCaptor.forClass(AuditLog.class);
        verify(integrityService, times(2)).append(entries.capture());
        AuditLog automated = entries.getAllValues().getFirst();
        AuditLog manual = entries.getAllValues().getLast();
        assertEquals(actor.getId(), automated.getActorId());
        assertEquals(automated.getActorId(), manual.getActorId());
        assertEquals(objectMapper.readTree(objectMapper.writeValueAsString(origin.context())),
            objectMapper.readTree(automated.getContext()).get("executionOrigin"));
        assertNull(manual.getContext());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void deferredOriginKeepsCallTimeAttributionAndExistingContextAfterCommitOrRollback(boolean committed) {
        Origin origin = Origin.workflow(23, 931L, "user");
        List<TransactionSynchronization> synchronizations;
        TransactionSynchronizationManager.initSynchronization();
        try {
            executor.runAs(7, actor, "member", origin, () -> {
                auditService.deferFailure("activity.create", "activity", 17, null,
                    "Activity failed", "DataIntegrityViolationException");
                return null;
            });
            synchronizations = TransactionSynchronizationManager.getSynchronizations();
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
        assertNull(scope.origin());
        verify(integrityService, never()).appendIndependent(any());
        synchronizations.getFirst().afterCompletion(committed
            ? TransactionSynchronization.STATUS_COMMITTED : TransactionSynchronization.STATUS_ROLLED_BACK);

        ArgumentCaptor<AuditLog> entry = ArgumentCaptor.forClass(AuditLog.class);
        verify(integrityService).appendIndependent(entry.capture());
        assertEquals(actor.getId(), entry.getValue().getActorId());
        assertEquals("DataIntegrityViolationException",
            objectMapper.readTree(entry.getValue().getContext()).get("error").stringValue());
        assertEquals(objectMapper.readTree(objectMapper.writeValueAsString(origin.context())),
            objectMapper.readTree(entry.getValue().getContext()).get("executionOrigin"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"note.create", "secret_store.secret.use", "ai.llm.call"})
    void auditExportPreservesTheValidatedOriginEnvelope(String action) {
        Origin origin = Origin.workflow(23, 931L, "user");
        executor.runAs(7, actor, "member", origin, () -> {
            auditService.recordStrict(action, "workspace", 7, null, "Operation", Map.of());
            return null;
        });
        ArgumentCaptor<AuditLog> entry = ArgumentCaptor.forClass(AuditLog.class);
        verify(integrityService).append(entry.capture());
        when(auditLogMapper.findWorkspaceExport(7, 10, 0)).thenReturn(List.of(entry.getValue()));
        tenantContext.set(7, 8, actor.getId(), "member", "cnx_test");

        String csv = auditService.exportRecent(10, 0);

        assertTrue(csv.contains("\"\"executionOrigin\"\""));
        assertTrue(csv.contains("\"\"workflowRunId\"\":931"));
        assertEquals(objectMapper.readTree(objectMapper.writeValueAsString(origin.context())),
            objectMapper.readTree(entry.getValue().getContext()).get("executionOrigin"));
    }

    @Test
    void nestedSystemWorkRestoresAutomationOriginEvenWhenItFails() {
        Origin outer = Origin.rule(23, 31, "user");
        Origin inner = Origin.task(Source.DELIVERY_WEBHOOK, "system");
        executor.runAs(7, actor, "member", outer, () -> {
            assertThrows(IllegalStateException.class, () -> executor.runAs(7, actor, "system", inner, () -> {
                assertEquals(inner, scope.origin());
                assertTrue(scope.isActive());
                throw new IllegalStateException("Failed system work");
            }));
            assertEquals(outer, scope.origin());
            auditService.recordStrict("note.create", "note", 17, null, "Created note", null);
            return null;
        });
        assertNull(scope.origin());
        assertFalse(scope.isActive());
        ArgumentCaptor<AuditLog> entry = ArgumentCaptor.forClass(AuditLog.class);
        verify(integrityService).append(entry.capture());
        assertEquals(objectMapper.readTree(objectMapper.writeValueAsString(outer.context())),
            objectMapper.readTree(entry.getValue().getContext()).get("executionOrigin"));
    }

    @Test
    void pureObserverStaysUntaggedWhileAnExplicitMutatingObserverKeepsTriggersEnabled() {
        executor.runAsObserver(7, actor, "system", () -> {
            assertFalse(scope.isActive());
            assertNull(scope.origin());
            auditService.recordStrict("observation.read", "person", 17, null, "Observed", null);
            return null;
        });
        Origin origin = Origin.task(Source.LEAD_RESPONSE_SLA, "system");
        executor.runAsObserver(7, actor, "system", origin, () -> {
            assertFalse(scope.isActive());
            auditService.recordStrict("person.first_response_breached", "person", 17,
                null, "Breach observed", null);
            return null;
        });
        ArgumentCaptor<AuditLog> entries = ArgumentCaptor.forClass(AuditLog.class);
        verify(integrityService, times(2)).append(entries.capture());
        assertNull(entries.getAllValues().getFirst().getContext());
        assertEquals(objectMapper.readTree(objectMapper.writeValueAsString(origin.context())),
            objectMapper.readTree(entries.getAllValues().getLast().getContext()).get("executionOrigin"));
        assertNull(scope.origin());
    }
}
