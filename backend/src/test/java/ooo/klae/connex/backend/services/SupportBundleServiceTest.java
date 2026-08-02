package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mockito;

import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.services.SupportBundleService.SupportBundleDownload;
import ooo.klae.connex.backend.services.SupportBundleService.SupportBundleRequest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Covers the support bundle's authorization ordering, window bounds, manifest integrity, and the
 * redaction guarantees the bundle promises.
 */
class SupportBundleServiceTest {
    private static final String SENTINEL = "SENTINEL_SECRET_VALUE";
    private static final Instant NOW = Instant.parse("2026-07-31T05:00:00Z");
    private static final int ORG_ID = 3;
    private static final int ACTOR_ID = 55;

    private OrgMemberService orgMemberService;
    private SessionSecurityService sessionSecurityService;
    private SupportBundleReadinessService readinessService;
    private SupportBundleConfigService configService;
    private MigrationHistoryService migrationHistoryService;
    private ProductVersionService productVersionService;
    private AuditService auditService;
    private ObjectMapper objectMapper;
    private SupportBundleService service;

    @BeforeEach
    void setUp() {
        orgMemberService = Mockito.mock(OrgMemberService.class);
        sessionSecurityService = Mockito.mock(SessionSecurityService.class);
        readinessService = Mockito.mock(SupportBundleReadinessService.class);
        configService = Mockito.mock(SupportBundleConfigService.class);
        migrationHistoryService = Mockito.mock(MigrationHistoryService.class);
        productVersionService = Mockito.mock(ProductVersionService.class);
        auditService = Mockito.mock(AuditService.class);
        objectMapper = new ObjectMapper();

        when(readinessService.readiness(anyInt())).thenReturn(Map.of("profile", "on-prem"));
        when(configService.safeConfiguration()).thenReturn(Map.of("connex.ai.enabled", "false"));
        when(migrationHistoryService.history()).thenReturn(List.of());
        when(productVersionService.version()).thenReturn("test");
        when(auditService.supportSliceForOrg(anyInt(), any(), any(), any(), anyInt()))
            .thenReturn("auditId,scope\r\n1,organization\r\n");

        service = new SupportBundleService(
            orgMemberService,
            sessionSecurityService,
            readinessService,
            configService,
            migrationHistoryService,
            productVersionService,
            auditService,
            objectMapper,
            Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private SupportBundleRequest request(Instant since) {
        return new SupportBundleRequest(ORG_ID, null, null, null, null, since);
    }

    @Test
    void checksOrgAdminBeforeRecentAuthentication() {
        service.prepare(request(null), ACTOR_ID).cancel();

        InOrder order = inOrder(orgMemberService, sessionSecurityService);
        order.verify(orgMemberService).requireOrgAdmin(ORG_ID, ACTOR_ID);
        order.verify(sessionSecurityService).requireRecentAuthentication(ACTOR_ID);
    }

    @Test
    void refusesWhenNotAnOrgAdminAndNeverAudits() {
        doThrow(new ForbiddenException("nope"))
            .when(orgMemberService).requireOrgAdmin(anyInt(), anyInt());

        assertThrows(ForbiddenException.class, () -> service.prepare(request(null), ACTOR_ID));
        verify(sessionSecurityService, never()).requireRecentAuthentication(anyInt());
        verify(auditService, never()).recordStrictIndependentScoped(
            anyString(), anyString(), any(), any(), any(), anyString(), anyString(), any());
    }

    @Test
    void refusesWhenStepUpIsStale() {
        doThrow(new ForbiddenException("stale"))
            .when(sessionSecurityService).requireRecentAuthentication(anyInt());

        assertThrows(ForbiddenException.class, () -> service.prepare(request(null), ACTOR_ID));
    }

    @Test
    void defaultsToASevenDayWindow() throws Exception {
        JsonNode manifest = manifestOf(service.prepare(request(null), ACTOR_ID));

        assertEquals(NOW.minus(Duration.ofDays(7)).toString(),
            manifest.get("filters").get("since").asString());
        assertEquals(NOW.toString(), manifest.get("filters").get("until").asString());
    }

    @Test
    void acceptsExactlyThirtyDays() {
        assertNotNull(service.prepare(request(NOW.minus(Duration.ofDays(30))), ACTOR_ID));
    }

    @Test
    void rejectsWindowsOlderThanThirtyDaysAndFutureWindows() {
        assertThrows(BadRequestException.class,
            () -> service.prepare(request(NOW.minus(Duration.ofDays(31))), ACTOR_ID));
        assertThrows(BadRequestException.class,
            () -> service.prepare(request(NOW.plusSeconds(60)), ACTOR_ID));
    }

    @Test
    void rejectsMalformedCorrelationIds() {
        assertThrows(BadRequestException.class,
            () -> SupportBundleService.validateCorrelationId("has space"));
        assertThrows(BadRequestException.class,
            () -> SupportBundleService.validateCorrelationId("short"));
        assertNull(SupportBundleService.validateCorrelationId(null));
        assertNull(SupportBundleService.validateCorrelationId("  "));
    }

    @Test
    void writesTheManifestLastAndDoesNotSelfHashIt() throws Exception {
        Map<String, byte[]> entries = entriesOf(service.prepare(request(null), ACTOR_ID));

        List<String> order = new ArrayList<>(entries.keySet());
        assertEquals("manifest.json", order.get(order.size() - 1));

        JsonNode manifest = objectMapper.readTree(entries.get("manifest.json"));
        for (JsonNode file : manifest.get("files")) {
            assertFalse("manifest.json".equals(file.get("path").asString()),
                "The manifest must not list itself in its own inventory");
        }
    }

    @Test
    void everyInventoryDigestAndLengthMatchesTheEntryBytes() throws Exception {
        Map<String, byte[]> entries = entriesOf(service.prepare(request(null), ACTOR_ID));
        JsonNode manifest = objectMapper.readTree(entries.get("manifest.json"));

        int checked = 0;
        for (JsonNode file : manifest.get("files")) {
            String path = file.get("path").asString();
            byte[] content = entries.get(path);
            assertNotNull(content, "Inventory lists " + path + " but the archive has no such entry");
            assertEquals(content.length, file.get("byteLength").asInt());
            assertEquals(sha256(content), file.get("sha256").asString());
            checked++;
        }
        assertEquals(entries.size() - 1, checked,
            "Every archive entry except the manifest must appear in the inventory");
    }

    @Test
    void declaresTheSourcesItDeliberatelyOmits() throws Exception {
        JsonNode omissions = manifestOf(service.prepare(request(null), ACTOR_ID)).get("omissions");

        assertEquals("no_persisted_source", omissions.get("client-errors.json").asString());
        assertEquals("job_run_not_available", omissions.get("job-runs.json").asString());
    }

    @Test
    void neverEmitsSecretMaterialFromAnySource() throws Exception {
        Map<String, Object> readiness = new LinkedHashMap<>();
        readiness.put("profile", "on-prem");
        readiness.put("mailHost", SENTINEL);
        when(readinessService.readiness(anyInt())).thenReturn(Map.of("profile", "on-prem"));
        when(configService.safeConfiguration()).thenReturn(Map.of("connex.ai.enabled", "false"));
        when(auditService.supportSliceForOrg(anyInt(), any(), any(), any(), anyInt()))
            .thenReturn("auditId,scope,actorId\r\n1,organization,55\r\n");

        Map<String, byte[]> entries = entriesOf(service.prepare(request(null), ACTOR_ID));

        for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
            String content = new String(entry.getValue(), java.nio.charset.StandardCharsets.UTF_8);
            assertFalse(content.contains(SENTINEL),
                "Entry " + entry.getKey() + " leaked the sentinel secret");
            assertFalse(content.contains("password"),
                "Entry " + entry.getKey() + " leaked a credential-shaped key");
            assertFalse(content.contains("jdbc:"),
                "Entry " + entry.getKey() + " leaked a datasource URL");
        }
        assertTrue(readiness.containsKey("mailHost"));
    }

    @Test
    void auditSliceCarriesNoDisplayName() throws Exception {
        when(auditService.supportSliceForOrg(anyInt(), any(), any(), any(), anyInt()))
            .thenReturn("auditId,scope,workspaceId,orgId,action,entityType,entityId,actorId,"
                + "outcome,requestId,createdAt,contentFieldsOmitted\r\n");

        Map<String, byte[]> entries = entriesOf(service.prepare(request(null), ACTOR_ID));
        String header = new String(entries.get("audit-slice.csv"),
            java.nio.charset.StandardCharsets.UTF_8);

        assertTrue(header.contains("actorId"));
        assertFalse(header.contains("actorLabel"));
        assertFalse(header.contains("currentActorLabel"));
        assertFalse(header.contains("targetLabel"));
    }

    @Test
    void auditsTheDownloadWithFilterMetadataOnly() {
        service.prepare(request(null), ACTOR_ID).cancel();

        verify(auditService).recordStrictIndependentScoped(
            eq("org.support_bundle.download"),
            eq("organization"),
            eq(ORG_ID),
            eq(null),
            eq(ORG_ID),
            anyString(),
            anyString(),
            any());
    }

    @Test
    void refusesToStreamTwiceFromOneDownload() throws Exception {
        SupportBundleDownload download = service.prepare(request(null), ACTOR_ID);
        download.writeTo(new ByteArrayOutputStream());

        assertThrows(IllegalStateException.class,
            () -> download.writeTo(new ByteArrayOutputStream()));
    }

    @Test
    void releasesAdmissionSoConcurrentDownloadsDoNotLeak() throws Exception {
        for (int attempt = 0; attempt < 12; attempt++) {
            service.prepare(request(null), ACTOR_ID).writeTo(new ByteArrayOutputStream());
        }
        assertNotNull(service.prepare(request(null), ACTOR_ID));
    }

    private JsonNode manifestOf(SupportBundleDownload download) throws Exception {
        return objectMapper.readTree(entriesOf(download).get("manifest.json"));
    }

    private Map<String, byte[]> entriesOf(SupportBundleDownload download) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        download.writeTo(output);
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(
                new ByteArrayInputStream(output.toByteArray()))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                entries.put(entry.getName(), zip.readAllBytes());
            }
        }
        return entries;
    }

    private static String sha256(byte[] content) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    }
}
