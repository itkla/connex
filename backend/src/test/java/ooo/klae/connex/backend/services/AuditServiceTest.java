package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import tools.jackson.databind.ObjectMapper;

import ooo.klae.connex.backend.ai.AiFeature;
import ooo.klae.connex.backend.beans.AuditLog;
import ooo.klae.connex.backend.config.AuditIntegrityProperties;
import ooo.klae.connex.backend.mappers.AuditLogMapper;
import ooo.klae.connex.backend.observability.ClientAssertedCorrelationPseudonymizer;
import ooo.klae.connex.backend.observability.CorrelationIds;
import ooo.klae.connex.backend.tenant.TenantContext;
import ooo.klae.connex.backend.util.ClientIpResolver;

@ExtendWith(MockitoExtension.class)
class AuditServiceTest {
    @Mock private AuditLogMapper auditLogMapper;
    @Mock private AuditIntegrityService auditIntegrityService;
    @Mock private TenantContext tenantContext;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private AuditService service;
    private ClientAssertedCorrelationPseudonymizer correlationPseudonymizer;

    @BeforeEach
    void setUp() {
        AuditIntegrityProperties properties = new AuditIntegrityProperties();
        properties.setHmacSecret("test-correlation-hmac-secret-change-me");
        correlationPseudonymizer = new ClientAssertedCorrelationPseudonymizer(properties);
        service = new AuditService(
            auditLogMapper,
            auditIntegrityService,
            objectMapper,
            tenantContext,
            new ClientIpResolver(""),
            correlationPseudonymizer);
        lenient().when(tenantContext.getWorkspaceId()).thenReturn(7);
        lenient().when(tenantContext.getOrgId()).thenReturn(8);
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
    void strictScopedRecordUsesExactScopeAndCallerTransactionAppend() {
        service.recordStrictScoped(
            "org.ai_budget.save", "organization", 23, 17, 23,
            "Organization 23", "Updated organization AI daily token budget",
            Map.of("dailyUsageLimit", 1_000L));

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditIntegrityService).append(captor.capture());
        AuditLog entry = captor.getValue();
        assertEquals(17, entry.getWorkspaceId());
        assertEquals(23, entry.getOrgId());
        assertEquals("org.ai_budget.save", entry.getAction());
        assertTrue(entry.getChanges().contains("1000"));
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
    void recentProjectsSecretMetadataWithoutDisclosingUncontrolledContent() {
        AuditLog entry = new AuditLog();
        entry.setAction("secret_store.secret.use");
        entry.setEntityType("organization");
        entry.setTargetLabel("private CRM content");
        entry.setOutcome("success");
        entry.setSummary("secret token value");
        entry.setChanges("""
                {"secretId":17,"purpose":"org.ai.provider_credential","keyId":"raw-secret-token",
                "rewrapped":false,"credential":"raw-secret-token"}
                """);
        entry.setContext("{\"error\":\"raw-secret-token\"}");
        when(auditLogMapper.findRecent(7, 25, 0)).thenReturn(List.of(entry));

        AuditLog result = service.recent(25, 0).getFirst();

        assertEquals("org.ai.provider_credential", result.getTargetLabel());
        assertEquals("Secret used", result.getSummary());
        assertEquals("success", result.getOutcome());
        assertTrue(result.getChanges().contains("secretId"));
        assertTrue(result.getChanges().contains("org.ai.provider_credential"));
        assertFalse(result.getChanges().contains("\"credential\""));
        assertFalse(result.getChanges().contains("raw-secret-token"));
        assertNull(result.getContext());
        assertTrue(result.isContentRedacted());
    }

    @Test
    void recentProjectsAiMetadataAndUsesTheDomainOutcome() {
        AuditLog entry = new AuditLog();
        entry.setAction("ai.llm.call");
        entry.setEntityType("ai_call");
        entry.setTargetLabel("private CRM content");
        entry.setOutcome("success");
        entry.setSummary("private model output");
        entry.setChanges("""
                {"provider":"vertex","region":"secret region value","model":"claude-sonnet-4@20250514",
                "feature":"assistant.chat","outcome":"blocked","correlationId":"123e4567-e89b-42d3-a456-426614174000",
                "inputTokens":80,"prompt":"private CRM content","response":"private model output"}
                """);
        entry.setContext("{\"error\":\"ProviderException\",\"detail\":\"secret token value\"}");
        when(auditLogMapper.findRecent(7, 25, 0)).thenReturn(List.of(entry));

        AuditLog result = service.recent(25, 0).getFirst();

        assertEquals("vertex", result.getTargetLabel());
        assertEquals("blocked", result.getOutcome());
        assertEquals("AI call blocked", result.getSummary());
        assertTrue(result.getChanges().contains("claude-sonnet-4@20250514"));
        assertTrue(result.getChanges().contains(AiFeature.ASSISTANT_CHAT.wireKey()));
        assertFalse(result.getChanges().contains("prompt"));
        assertFalse(result.getChanges().contains("response"));
        assertFalse(result.getChanges().contains("secret region value"));
        assertEquals("{\"error\":\"ProviderException\"}", result.getContext());
        assertTrue(result.isContentRedacted());
    }

    @Test
    void recentPreservesAControlledSecretPurposeForCurrentFailureRows() {
        AuditLog entry = new AuditLog();
        entry.setAction("secret_store.secret.use_failed");
        entry.setEntityType("organization");
        entry.setTargetLabel("org.ai.provider_credential");
        entry.setOutcome("failure");
        entry.setSummary("Secret use failed");
        entry.setContext("{\"error\":\"SecretUnavailableException\"}");
        when(auditLogMapper.findRecent(7, 25, 0)).thenReturn(List.of(entry));

        AuditLog result = service.recent(25, 0).getFirst();

        assertEquals("org.ai.provider_credential", result.getTargetLabel());
        assertEquals("Secret use failed", result.getSummary());
        assertEquals("{\"error\":\"SecretUnavailableException\"}", result.getContext());
    }

    @Test
    void recentProjectsFullyPartialSensitiveRowsWithoutFailing() {
        AuditLog entry = new AuditLog();
        entry.setAction("secret_store.secret.use_failed");
        when(auditLogMapper.findRecent(7, 25, 0)).thenReturn(List.of(entry));

        AuditLog result = service.recent(25, 0).getFirst();

        assertEquals("secret_store", result.getTargetLabel());
        assertEquals("Secret use failed", result.getSummary());
        assertNull(result.getEntityType());
        assertNull(result.getOutcome());
        assertTrue(result.isContentRedacted());
    }

    @Test
    void recentPreservesSupportedGemmaModelNames() {
        AuditLog entry = new AuditLog();
        entry.setAction("ai.llm.call");
        entry.setEntityType("ai_call");
        entry.setOutcome("success");
        entry.setChanges("""
                {"provider":"openai_compatible","region":"eastus2","model":"google/gemma-4-31b-it",
                "outcome":"success"}
                """);
        when(auditLogMapper.findRecent(7, 25, 0)).thenReturn(List.of(entry));

        AuditLog result = service.recent(25, 0).getFirst();

        assertTrue(result.getChanges().contains("google/gemma-4-31b-it"));
        assertEquals("openai_compatible/eastus2", result.getTargetLabel());
    }

    @Test
    void exportOmitsSensitiveIntegrityPayloadAndRawContent() {
        AuditLog entry = new AuditLog();
        entry.setAction("ai.llm.call");
        entry.setEntityType("ai_call");
        entry.setTargetLabel("private CRM content");
        entry.setOutcome("success");
        entry.setSummary("private model output");
        entry.setChanges("""
                {"provider":"vertex","model":"sk-proj-abc123","feature":"report.narrative","outcome":"success",
                "prompt":"private CRM content","credential":"raw-secret-token"}
                """);
        when(auditLogMapper.findWorkspaceExport(7, 25, 0)).thenReturn(List.of(entry));

        String csv = service.exportRecent(25, 0);

        verify(auditIntegrityService, never()).integrityPayload(entry);
        assertTrue(csv.contains("contentRedacted,integrityPayloadRedacted,integrityPayload"));
        assertTrue(csv.contains("AI call success"));
        assertTrue(csv.contains("report.narrative"));
        assertFalse(csv.contains("private CRM content"));
        assertFalse(csv.contains("private model output"));
        assertFalse(csv.contains("raw-secret-token"));
        assertFalse(csv.contains("sk-proj-abc123"));
    }

    @Test
    void exportForEntityScopesToWorkspaceAndForwardsEntity() {
        when(auditLogMapper.findByEntity(7, "company", 12, 25, 50)).thenReturn(List.of());
        service.exportForEntity("company", 12, 25, 50);
        verify(auditLogMapper).findByEntity(7, "company", 12, 25, 50);
    }

    /**
     * The audit identifier must not be attacker-influenced: {@code X-Correlation-Id} is
     * client-settable, so a caller could otherwise make unrelated requests share one identifier or
     * inject rows into an investigator's filtered slice.
     */
    @Test
    void auditRequestIdIgnoresTheClientSuppliedCorrelationId() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CorrelationIds.HEADER_NAME, "abcd1234efgh");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        MDC.put(CorrelationIds.MDC_KEY, "abcd1234efgh");
        try {
            service.record("person.archive", "person", 412, "person:412", "Archived", null);

            ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
            verify(auditIntegrityService).append(captor.capture());
            String recorded = captor.getValue().getRequestId();
            assertNotNull(recorded);
            assertNotEquals("abcd1234efgh", recorded);
            assertEquals(
                correlationPseudonymizer.forStorage(8, "abcd1234efgh"),
                captor.getValue().getUntrustedClientAssertedCorrelationHmac());
        } finally {
            MDC.remove(CorrelationIds.MDC_KEY);
            RequestContextHolder.resetRequestAttributes();
        }
    }

    /** Every audit event written during one request shares that request's server-minted id. */
    @Test
    void auditEventsInOneRequestShareOneServerMintedId() {
        RequestContextHolder.setRequestAttributes(
            new ServletRequestAttributes(new MockHttpServletRequest()));
        try {
            service.record("person.archive", "person", 412, "person:412", "Archived", null);
            service.record("person.update", "person", 412, "person:412", "Updated", null);

            ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
            verify(auditIntegrityService, org.mockito.Mockito.times(2)).append(captor.capture());
            List<AuditLog> written = captor.getAllValues();
            assertNotNull(written.get(0).getRequestId());
            assertEquals(written.get(0).getRequestId(), written.get(1).getRequestId());
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }

    /**
     * A scheduler thread has no request attributes at all, so the request id is simply absent.
     * This is unchanged, pre-existing behaviour and is asserted so it stays deliberate.
     */
    @Test
    void schedulerThreadsRecordNoRequestId() {
        RequestContextHolder.resetRequestAttributes();

        service.record("job.run", "job", 1, "job:1", "Ran", null);

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditIntegrityService).append(captor.capture());
        assertNull(captor.getValue().getRequestId());
        assertNull(captor.getValue().getUntrustedClientAssertedCorrelationHmac());
    }
}
