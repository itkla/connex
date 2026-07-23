package ooo.klae.connex.backend.mappers;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/** Pins the tenant-only SQL boundary for hydration-sensitive attachment reads. */
class AttachmentMapperXmlTest {
    private static final Pattern BLOCK = Pattern.compile(
        "<(select|sql) id=\"([^\"]+)\"[^>]*>(.*?)</\\1>", Pattern.DOTALL);

    @Test
    void hydrationSensitiveReadsUseOnlyTenantJoins() throws IOException {
        String xml = mapperXml();
        String tenantJoins = block(xml, "tenantAttachJoins");

        assertFalse(tenantJoins.contains("app_user"));
        for (String statement : List.of(
                "getByEntity", "getAll", "getById", "getCreatedById", "getByUrl")) {
            String sql = block(xml, statement);
            assertTrue(sql.contains("tenantAttachJoins"), statement);
            assertFalse(sql.contains("attachJoins"), statement);
            assertFalse(sql.contains("app_user"), statement);
            assertFalse(sql.contains("uploaded_by_name"), statement);
        }
    }

    @Test
    void predicateSensitiveReadsRetainTheirExplicitLegacyCrossings() throws IOException {
        String xml = mapperXml();

        assertTrue(block(xml, "search").contains("attachmentColumnsWithUploader"));
        assertTrue(block(xml, "getPage").contains("attachJoins"));
        assertTrue(block(xml, "countPage").contains("attachJoins"));
        assertTrue(block(xml, "countOrphaned").contains("attachJoins"));
        assertTrue(block(xml, "attachJoins").contains("app_user"));
    }

    @Test
    void metadataReadsRemainWorkspaceScopedAndIdentityFree() throws IOException {
        String xml = mapperXml();

        for (String statement : List.of("getMetadataById", "getMetadataByUrl")) {
            String sql = block(xml, statement);
            assertTrue(sql.contains("a.workspace_id = #{workspaceId}"), statement);
            assertFalse(sql.contains("app_user"), statement);
        }
    }

    private static String block(String xml, String id) {
        Matcher matcher = BLOCK.matcher(xml);
        while (matcher.find()) {
            if (id.equals(matcher.group(2))) {
                return matcher.group(3);
            }
        }
        throw new IllegalStateException("Missing AttachmentMapper block " + id);
    }

    private static String mapperXml() throws IOException {
        try (InputStream input = AttachmentMapperXmlTest.class.getClassLoader()
                .getResourceAsStream("mappers/AttachmentMapper.xml")) {
            if (input == null) {
                throw new IllegalStateException("AttachmentMapper.xml is missing");
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
