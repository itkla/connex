package ooo.klae.connex.backend.mappers;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Pins indexed URL lookup eligibility while retaining exact managed-object identity. */
class AttachmentUrlMapperXmlTest {
    @ParameterizedTest
    @CsvSource({
        "AttachmentMapper,getByUrl", "AttachmentMapper,getMetadataByUrl",
        "AttachmentMapper,countUrl", "AttachmentMapper,lockIdsByUrl",
        "AttachmentScanMapper,isReadable", "AttachmentScanMapper,isClaimable",
        "AttachmentScanMapper,claim", "AttachmentScanMapper,decide",
        "AttachmentScanMapper,retry", "AttachmentScanMapper,refuse"
    })
    void exactObjectStatementsKeepIndexableEqualityAlongsideBinaryResidual(String mapper, String statement)
            throws Exception {
        String sql = sql(mapper, statement);

        assertTrue(sql.contains("workspace_id = ?"), sql);
        assertTrue(sql.contains("url = ? AND BINARY"), sql);
        assertTrue(sql.contains("BINARY url = BINARY ?") || sql.contains("BINARY a.url = BINARY ?"), sql);
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(
                "db/migration/tenant/V82__object_storage_hardening.sql")) {
            assertNotNull(input);
            assertTrue(new String(input.readAllBytes(), StandardCharsets.UTF_8)
                .contains("idx_attachment_workspace_url (workspace_id, url(191))"));
        }
    }

    @Test
    void scanJoinsAndPrefixSelectionKeepIndexablePredicates() throws Exception {
        for (String statement : List.of("quarantine", "enqueue")) {
            assertTrue(sql("AttachmentScanMapper", statement)
                .contains("target.url = a.url AND BINARY target.url = BINARY a.url"));
        }
        assertTrue(sql("AttachmentScanMapper", "findDue")
            .contains("url LIKE ? AND BINARY url LIKE BINARY ?"));
    }

    @Test
    void externalDuplicateGuardPreservesColumnCollation() throws Exception {
        String sql = sql("AttachmentMapper", "countUrlInOtherWorkspaces");

        assertTrue(sql.contains("url = ? AND workspace_id != ?"));
        assertFalse(sql.contains("BINARY"));
    }

    private static String sql(String mapper, String statement) throws Exception {
        Configuration configuration = new Configuration();
        configuration.getTypeAliasRegistry().registerAliases("ooo.klae.connex.backend.beans");
        for (String name : List.of("AttachmentMapper", "AttachmentScanMapper")) {
            String resource = "mappers/" + name + ".xml";
            try (InputStream input = AttachmentUrlMapperXmlTest.class.getClassLoader()
                    .getResourceAsStream(resource)) {
                assertNotNull(input);
                new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
            }
        }
        return configuration.getMappedStatement("ooo.klae.connex.backend.mappers." + mapper + "." + statement)
            .getBoundSql(Map.of("workspaceId", 7, "url", "/api/attachments/content/token.png", "userId", 3,
                "id", 11, "owner", "claim-owner", "allowDisabledProof", false, "limit", 1))
            .getSql().replaceAll("\\s+", " ").trim();
    }
}
