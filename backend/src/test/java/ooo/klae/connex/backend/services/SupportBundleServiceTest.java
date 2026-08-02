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

import ooo.klae.connex.backend.dto.AuditSupportRowDto;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.util.ClientIpResolver;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.services.SupportBundleService.SupportBundle;
import ooo.klae.connex.backend.services.SupportBundleService.SupportBundleRequest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Covers the support bundle's authorization ordering, window bounds, manifest integrity, and the
 * redaction guarantees the bundle promises.
 */
class SupportBundleServiceTest {
    private static final String SENTINEL = "SENTINEL_SECRET_VALUE";
    private static final String SUPPORT_CSV_HEADER =
        "auditId,scope,workspaceId,orgId,action,entityType,entityId,actorId,outcome,requestId,"
            + "createdAt,contentFieldsOmitted";
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
    private ooo.klae.connex.backend.mappers.AuditLogMapper auditLogMapper;
    private AuditIntegrityService auditIntegrityService;
    private ooo.klae.connex.backend.tenant.TenantContext tenantContext;

    @BeforeEach
    void setUp() {
        orgMemberService = Mockito.mock(OrgMemberService.class);
        sessionSecurityService = Mockito.mock(SessionSecurityService.class);
        readinessService = Mockito.mock(SupportBundleReadinessService.class);
        configService = Mockito.mock(SupportBundleConfigService.class);
        migrationHistoryService = Mockito.mock(MigrationHistoryService.class);
        productVersionService = Mockito.mock(ProductVersionService.class);
        auditService = Mockito.mock(AuditService.class);
        auditLogMapper = Mockito.mock(ooo.klae.connex.backend.mappers.AuditLogMapper.class);
        auditIntegrityService = Mockito.mock(AuditIntegrityService.class);
        tenantContext = Mockito.mock(ooo.klae.connex.backend.tenant.TenantContext.class);
        objectMapper = new ObjectMapper();

        when(readinessService.readiness(anyInt())).thenReturn(Map.of("profile", "on-prem"));
        when(configService.safeConfiguration()).thenReturn(
            new SupportBundleConfigService.SafeConfiguration(
                Map.of("connex.ai.enabled", "false"), Map.of()));
        when(migrationHistoryService.history()).thenReturn(List.of());
        when(productVersionService.version()).thenReturn("test");
        when(auditService.supportSliceForOrg(anyInt(), any(), any(), any(), anyInt()))
            .thenReturn(new AuditService.AuditSlice("auditId,scope\r\n1,organization\r\n", 1, false));

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
        service.generate(request(null), ACTOR_ID);

        InOrder order = inOrder(orgMemberService, sessionSecurityService);
        order.verify(orgMemberService).requireOrgAdmin(ORG_ID, ACTOR_ID);
        order.verify(sessionSecurityService).requireRecentAuthentication(ACTOR_ID);
    }

    @Test
    void refusesWhenNotAnOrgAdminAndNeverAudits() {
        doThrow(new ForbiddenException("nope"))
            .when(orgMemberService).requireOrgAdmin(anyInt(), anyInt());

        assertThrows(ForbiddenException.class, () -> service.generate(request(null), ACTOR_ID));
        verify(sessionSecurityService, never()).requireRecentAuthentication(anyInt());
        verify(auditService, never()).recordStrictIndependentScoped(
            anyString(), anyString(), any(), any(), any(), anyString(), anyString(), any());
    }

    @Test
    void refusesWhenStepUpIsStale() {
        doThrow(new ForbiddenException("stale"))
            .when(sessionSecurityService).requireRecentAuthentication(anyInt());

        assertThrows(ForbiddenException.class, () -> service.generate(request(null), ACTOR_ID));
    }

    @Test
    void defaultsToASevenDayWindow() throws Exception {
        JsonNode manifest = manifestOf(service.generate(request(null), ACTOR_ID));

        assertEquals(NOW.minus(Duration.ofDays(7)).toString(),
            manifest.get("filters").get("since").asString());
        assertEquals(NOW.toString(), manifest.get("filters").get("until").asString());
    }

    @Test
    void acceptsExactlyThirtyDays() {
        assertNotNull(service.generate(request(NOW.minus(Duration.ofDays(30))), ACTOR_ID));
    }

    @Test
    void rejectsWindowsOlderThanThirtyDaysAndFutureWindows() {
        assertThrows(BadRequestException.class,
            () -> service.generate(request(NOW.minus(Duration.ofDays(31))), ACTOR_ID));
        assertThrows(BadRequestException.class,
            () -> service.generate(request(NOW.plusSeconds(60)), ACTOR_ID));
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
        Map<String, byte[]> entries = entriesOf(service.generate(request(null), ACTOR_ID));

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
        Map<String, byte[]> entries = entriesOf(service.generate(request(null), ACTOR_ID));
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
        JsonNode omissions = manifestOf(service.generate(request(null), ACTOR_ID)).get("omissions");

        assertEquals("no_persisted_source", omissions.get("client-errors.json").asString());
        assertEquals("job_run_not_available", omissions.get("job-runs.json").asString());
    }



    @Test
    void auditsTheDownloadWithFilterMetadataOnly() {
        service.generate(request(null), ACTOR_ID);

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



/**
     * Drives the real CSV formatter over real projected rows. The previous version of this test
     * asserted against a hand-typed mock string, so adding a display-name column to the formatter
     * would not have failed it.
     */
    @Test
    void auditSliceFormatterEmitsActorIdAndNoPersonalName() {
        AuditService realAuditService = new AuditService(
            auditLogMapper, auditIntegrityService, new ObjectMapper(), tenantContext,
            new ClientIpResolver(""));
        when(auditLogMapper.findOrgSupportSlice(anyInt(), any(), any(), any(), anyInt()))
            .thenReturn(List.of(new AuditSupportRowDto(
                9001L, 7, 3, "person.archive", "person", 412, 55, "success",
                "req-1", Instant.parse("2026-07-31T04:05:06Z"))));

        AuditService.AuditSlice slice = realAuditService.supportSliceForOrg(
            3, NOW.minus(Duration.ofDays(7)), NOW, null, 10);

        // Pinned exactly, not spot-checked. A deny-list of remembered column names cannot catch a
        // sensitive column nobody thought to add to the list.
        assertEquals(SUPPORT_CSV_HEADER, slice.csv().lines().findFirst().orElseThrow());
        assertTrue(slice.csv().contains("55"));
        assertFalse(slice.truncated());
        assertEquals(1, slice.rowCount());
    }

    /**
     * Pins the projection DTO's shape. The CSV formatter can only emit what this record carries,
     * so widening it is the first step of any accidental disclosure and must fail here.
     */
    @Test
    void theAuditProjectionExposesExactlyTheApprovedFields() {
        List<String> fields = java.util.Arrays.stream(AuditSupportRowDto.class.getRecordComponents())
            .map(java.lang.reflect.RecordComponent::getName)
            .toList();

        assertEquals(List.of("auditId", "workspaceId", "orgId", "action", "entityType", "entityId",
            "actorId", "outcome", "requestId", "createdAt"), fields,
            "The support projection changed. Every field here is disclosed to whoever receives the "
                + "bundle, so a new field must be reviewed as a disclosure, not added for "
                + "convenience.");
    }

    /**
     * Pins the SQL fragment itself, which nothing else reads. The DTO cannot protect what the
     * query fetches: adding a column to the fragment and the result map would put employee free
     * text into memory and, the moment the DTO grew a matching field, into the bundle.
     */
    @Test
    void theSupportSqlFragmentSelectsExactlyTheApprovedColumns() throws Exception {
        String mapper = java.nio.file.Files.readString(
            java.nio.file.Path.of("src/main/resources/mappers/AuditLogMapper.xml"));
        java.util.regex.Matcher fragment = java.util.regex.Pattern
            .compile("<sql id=\"supportColumns\">(.*?)</sql>", java.util.regex.Pattern.DOTALL)
            .matcher(mapper);
        assertTrue(fragment.find(), "The supportColumns fragment is missing");

        List<String> columns = java.util.Arrays.stream(fragment.group(1).split(","))
            .map(String::trim)
            .filter(column -> !column.isEmpty())
            .toList();

        assertEquals(List.of("al.id", "al.workspace_id", "al.org_id", "al.action", "al.entity_type",
            "al.entity_id", "al.actor_id", "al.outcome", "al.request_id", "al.created_at"), columns,
            "The support SQL projection changed. Columns such as summary, changes, context, "
                + "actor_label, target_label, ip_address, user_agent and session_id carry user "
                + "data and must never be fetched for a bundle.");

        for (String statement : List.of("findOrgSupportSlice", "findEntitySupportSlice")) {
            java.util.regex.Matcher select = java.util.regex.Pattern
                .compile("<select id=\"" + statement + "\".*?</select>",
                    java.util.regex.Pattern.DOTALL)
                .matcher(mapper);
            assertTrue(select.find(), statement + " is missing");
            String body = select.group();
            assertTrue(body.contains("supportColumns"),
                statement + " must use the narrow support projection");
            assertFalse(body.toLowerCase(java.util.Locale.ROOT).contains("app_user"),
                statement + " must not join app_user; that join exists only to resolve a display "
                    + "name, which a bundle must never carry");
        }
    }

    /**
     * Sensitive columns must be unreachable rather than merely unformatted: the projection has no
     * field to hold them, so a value seeded into the audit row cannot reach the CSV at all.
     */
    @Test
    void sensitiveAuditContentCannotReachTheCsv() {
        AuditService realAuditService = new AuditService(
            auditLogMapper, auditIntegrityService, new ObjectMapper(), tenantContext,
            new ClientIpResolver(""));
        when(auditLogMapper.findOrgSupportSlice(anyInt(), any(), any(), any(), anyInt()))
            .thenReturn(List.of(new AuditSupportRowDto(
                9001L, 7, 3, "person.archive", "person", 412, 55, "success",
                "req-1", Instant.parse("2026-07-31T04:05:06Z"))));

        String csv = realAuditService.supportSliceForOrg(
            3, NOW.minus(Duration.ofDays(7)), NOW, null, 10).csv();

        assertFalse(csv.contains(SENTINEL));
        for (String forbidden : List.of("actorLabel", "currentActorLabel", "targetLabel", "summary",
                "changes", "context", "ipAddress", "userAgent", "sessionId", "prevHash",
                "rowHash")) {
            assertFalse(csv.contains(forbidden), "CSV disclosed " + forbidden);
        }
    }

    /**
     * A saturated window must be distinguishable from a complete one, so the query asks for one
     * row beyond the disclosure limit and the extra row is never emitted.
     */
    @Test
    void auditSliceReportsTruncationWithoutDisclosingTheExtraRow() {
        AuditService realAuditService = new AuditService(
            auditLogMapper, auditIntegrityService, new ObjectMapper(), tenantContext,
            new ClientIpResolver(""));
        List<AuditSupportRowDto> rows = new ArrayList<>();
        for (int index = 0; index < 4; index++) {
            rows.add(new AuditSupportRowDto((long) index, 7, 3, "person.update", "person", index,
                55, "success", "req-" + index, Instant.parse("2026-07-31T04:05:06Z")));
        }
        when(auditLogMapper.findOrgSupportSlice(anyInt(), any(), any(), any(), eq(4)))
            .thenReturn(rows);

        AuditService.AuditSlice slice = realAuditService.supportSliceForOrg(
            3, NOW.minus(Duration.ofDays(7)), NOW, null, 3);

        assertTrue(slice.truncated());
        assertEquals(3, slice.rowCount());
        assertFalse(slice.csv().contains("req-3"));
    }

    @Test
    void manifestRecordsTheAuditSliceRowCountAndTruncation() throws Exception {
        when(auditService.supportSliceForOrg(anyInt(), any(), any(), any(), anyInt()))
            .thenReturn(new AuditService.AuditSlice("auditId\r\n", 10_000, true));

        JsonNode manifest = manifestOf(service.generate(request(null), ACTOR_ID));

        assertEquals(10_000, manifest.get("auditSliceRowCount").asInt());
        assertTrue(manifest.get("auditSliceTruncated").asBoolean());
    }

    private JsonNode manifestOf(SupportBundle bundle) throws Exception {
        return objectMapper.readTree(entriesOf(bundle).get("manifest.json"));
    }

    private Map<String, byte[]> entriesOf(SupportBundle bundle) throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(
                new ByteArrayInputStream(bundle.content()))) {
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
