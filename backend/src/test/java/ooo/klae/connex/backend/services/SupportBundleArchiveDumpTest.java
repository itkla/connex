package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import ooo.klae.connex.backend.services.SupportBundleService.SupportBundleRequest;
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

        when(readinessService.readiness(anyInt())).thenReturn(Map.of(
            "source", "support_bundle_fallback",
            "profile", "on-prem"));
        when(configService.safeConfiguration())
            .thenReturn(Map.of("connex.deployment.profile", "on-prem"));
        when(migrationHistoryService.history()).thenReturn(List.of());
        when(productVersionService.version()).thenReturn("test");
        when(auditService.supportSliceForOrg(anyInt(), any(), any(), any(), anyInt()))
            .thenReturn("auditId,scope,workspaceId,orgId,action,entityType,entityId,actorId,"
                + "outcome,requestId,createdAt,contentFieldsOmitted\r\n"
                + "9001,workspace,7,3,person.archive,person,412,55,success,abcd1234efgh,"
                + "2026-07-31T04:05:06Z,true\r\n");

        SupportBundleService service = new SupportBundleService(
            orgMemberService,
            sessionSecurityService,
            readinessService,
            configService,
            migrationHistoryService,
            productVersionService,
            auditService,
            new ObjectMapper(),
            Clock.fixed(Instant.parse("2026-07-31T05:00:00Z"), ZoneOffset.UTC));

        Path directory = Path.of("build", "support-bundle");
        Files.createDirectories(directory);
        Path archive = directory.resolve("sample-bundle.zip");
        Files.deleteIfExists(archive);
        try (OutputStream output = Files.newOutputStream(archive)) {
            service.prepare(new SupportBundleRequest(3, null, null, null, null, null), 55)
                .writeTo(output);
        }

        assertTrue(Files.size(archive) > 0);
    }
}
