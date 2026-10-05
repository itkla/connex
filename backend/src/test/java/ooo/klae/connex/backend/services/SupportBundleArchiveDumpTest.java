package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import ooo.klae.connex.backend.config.AuditIntegrityProperties;
import ooo.klae.connex.backend.dto.ClientErrorSupportRowDto;
import ooo.klae.connex.backend.observability.ClientAssertedCorrelationPseudonymizer;
import ooo.klae.connex.backend.services.SupportBundleService.SupportBundleRequest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Writes a real bundle archive to {@code build/support-bundle/} so the operator tooling in
 * {@code deploy/support-bundle/} can be run against output produced by the actual writer.
 *
 * <p>The two halves of this feature are written in different languages and verify the same
 * manifest contract from opposite sides; this test is what keeps them honest about the archive
 * shape, entry naming, and inventory coverage.
 */
class SupportBundleArchiveDumpTest {

    @Test
    void writesAnArchiveTheOperatorToolingCanVerify() throws Exception {
        OrgMemberService orgMemberService = Mockito.mock(OrgMemberService.class);
        SessionSecurityService sessionSecurityService = Mockito.mock(SessionSecurityService.class);
        SupportBundleReadinessService readinessService =
            Mockito.mock(SupportBundleReadinessService.class);
        SupportBundleConfigService configService = Mockito.mock(SupportBundleConfigService.class);
        MigrationHistoryService migrationHistoryService =
            Mockito.mock(MigrationHistoryService.class);
        ProductVersionService productVersionService = Mockito.mock(ProductVersionService.class);
        AuditService auditService = Mockito.mock(AuditService.class);
        ClientErrorService clientErrorService = Mockito.mock(ClientErrorService.class);

        when(readinessService.readiness(anyInt())).thenReturn(Map.of(
            "source", "support_bundle_fallback",
            "profile", "on-prem"));
        when(configService.safeConfiguration()).thenReturn(
            new SupportBundleConfigService.SafeConfiguration(
                Map.of("connex.deployment.profile", "on-prem"), Map.of()));
        when(migrationHistoryService.history()).thenReturn(List.of());
        when(productVersionService.version()).thenReturn("test");
        when(auditService.supportSliceForOrg(anyInt(), any(), any(), any(), anyInt()))
            .thenReturn(new AuditService.AuditSlice(
                "auditId,scope,workspaceId,orgId,action,entityType,entityId,actorId,"
                    + "outcome,serverMintedRequestId,untrustedClientAssertedCorrelationHmac,"
                    + "createdAt,contentFieldsOmitted\r\n"
                    + "9001,workspace,7,3,person.archive,person,412,55,success,server-request-1,"
                    + "abcd1234efgh,"
                    + "2026-07-31T04:05:06Z,true\r\n", 1, false));
        when(clientErrorService.supportSliceForOrg(anyInt(), any(), any(), any(), anyInt()))
            .thenReturn(new ClientErrorService.ClientErrorSlice(List.of(
                new ClientErrorSupportRowDto(
                    71L,
                    7,
                    "disclosure-hmac",
                    "/records/contacts/{id}",
                    Instant.parse("2026-07-31T04:05:05Z"))), 1, false));

        AuditIntegrityProperties integrityProperties = new AuditIntegrityProperties();
        integrityProperties.setHmacSecret("archive-dump-test-secret-at-least-32-bytes");

        SupportBundleService service = new SupportBundleService(
            orgMemberService,
            sessionSecurityService,
            readinessService,
            configService,
            migrationHistoryService,
            productVersionService,
            auditService,
            clientErrorService,
            new ClientAssertedCorrelationPseudonymizer(integrityProperties),
            new ObjectMapper(),
            Clock.fixed(Instant.parse("2026-07-31T05:00:00Z"), ZoneOffset.UTC));

        Path directory = Path.of("build", "support-bundle");
        Files.createDirectories(directory);
        Path archive = directory.resolve("sample-bundle.zip");
        Files.deleteIfExists(archive);
        try (OutputStream output = Files.newOutputStream(archive)) {
            output.write(service
                .generate(new SupportBundleRequest(
                    3, null, null, null, null, null), 55)
                .content());
        }

        assertTrue(Files.size(archive) > 0);
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(archive))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                assertTrue(!entries.containsKey(entry.getName()), "Duplicate entry: " + entry.getName());
                entries.put(entry.getName(), zip.readAllBytes());
            }
        }
        assertEquals(Set.of("manifest.json", "readiness.json", "config.json", "migrations.json",
            "audit-slice.csv", "client-errors.json"), entries.keySet());
        assertEquals("manifest.json", entries.keySet().stream().toList().getLast());
        JsonNode manifest = new ObjectMapper().readTree(entries.get("manifest.json"));
        assertNotNull(manifest);
        assertTrue(manifest.path("schemaVersion").isInt(), "schemaVersion must be an integer");
        assertEquals(3, manifest.path("schemaVersion").intValue());
        assertEquals("test", manifest.path("productVersion").asString());
        assertEquals("job_run_not_available", manifest.path("omissions").path("job-runs.json").asString());
        assertInventory(manifest, entries);
    }

    @Test
    void inventoryMustBeAnArrayRatherThanAnObjectOfValidEntries() throws Exception {
        Map<String, byte[]> entries = Map.of(
            "readiness.json", new byte[0], "config.json", new byte[0], "migrations.json", new byte[0],
            "audit-slice.csv", new byte[0], "client-errors.json", new byte[0]);
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(new byte[0]));
        List<Map<String, Object>> inventory = entries.keySet().stream()
            .map(path -> Map.<String, Object>of("path", path, "byteLength", 0, "sha256", digest))
            .toList();
        ObjectMapper mapper = new ObjectMapper();
        assertInventory(mapper.valueToTree(Map.of("files", inventory)), entries);

        Map<String, Map<String, Object>> objectInventory = new LinkedHashMap<>();
        for (int i = 0; i < inventory.size(); i++) {
            objectInventory.put(Integer.toString(i), inventory.get(i));
        }
        JsonNode manifest = mapper.valueToTree(Map.of("files", objectInventory));
        assertThrows(AssertionError.class, () -> assertInventory(manifest, entries));
    }

    private static void assertInventory(JsonNode manifest, Map<String, byte[]> entries) throws Exception {
        assertTrue(manifest.path("files").isArray(), "files must be an array");
        assertEquals(5, manifest.path("files").size());
        Set<String> inventoried = new HashSet<>();
        for (JsonNode file : manifest.path("files")) {
            String path = file.path("path").asString();
            assertTrue(inventoried.add(path), "Duplicate inventory path: " + path);
            byte[] content = entries.get(path);
            assertNotNull(content, path);
            assertTrue(file.path("byteLength").isInt(), path + ": byteLength must be an integer");
            assertEquals(content.length, file.path("byteLength").intValue(), path);
            assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content)),
                file.path("sha256").asString(), path);
        }
        assertEquals(Set.of("readiness.json", "config.json", "migrations.json", "audit-slice.csv",
            "client-errors.json"), inventoried);
    }
}
