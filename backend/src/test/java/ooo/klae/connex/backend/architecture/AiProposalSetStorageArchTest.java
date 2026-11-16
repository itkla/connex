package ooo.klae.connex.backend.architecture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;

import ooo.klae.connex.backend.mappers.AiAssistantProposalSetMapper;
import ooo.klae.connex.backend.tenant.ProcessingRestrictionRegistry;
import ooo.klae.connex.backend.tenant.TablePlaneRegistry;
import ooo.klae.connex.backend.tenant.TenantLifecycleRegistry;
import ooo.klae.connex.backend.tenant.TenantScopeInterceptor;

/** Keeps slice-3 storage dormant and enrolled without granting later privacy or decision semantics. */
class AiProposalSetStorageArchTest {
    private static final String NAMESPACE = AiAssistantProposalSetMapper.class.getName();

    @Test
    void productionHasNoProposalSetMapperConsumer() throws Exception {
        try (var paths = Files.walk(Path.of("src/main/java"))) {
            for (Path path : paths.filter(value -> value.toString().endsWith(".java")).toList()) {
                if (!path.getFileName().toString().equals("AiAssistantProposalSetMapper.java")) {
                    String source = Files.readString(path);
                    if (path.endsWith("tenant/TenantScopeInterceptor.java")) {
                        source = source.replace("MAPPERS + \"AiAssistantProposalSetMapper\"", "");
                    }
                    assertFalse(source.contains("AiAssistantProposalSetMapper"),
                            "Proposal-set storage must remain dormant: " + path);
                }
            }
        }
    }

    @Test
    void proposalSetMetadataHasNoUpdateOrReplacementStatement() throws Exception {
        Document mapper = mapper("AiAssistantProposalSetMapper");
        assertEquals(0, mapper.getElementsByTagName("update").getLength());
        assertEquals(0, mapper.getElementsByTagName("delete").getLength());
        assertEquals(2, mapper.getElementsByTagName("insert").getLength());
        String sql = mapper.getDocumentElement().getTextContent().toLowerCase();
        assertFalse(sql.contains("on duplicate key"));
        assertFalse(sql.contains("replace into"));
        var selects = mapper.getElementsByTagName("select");
        int lockingReads = 0;
        for (int index = 0; index < selects.getLength(); index++) {
            var select = (org.w3c.dom.Element) selects.item(index);
            if (select.getTextContent().toLowerCase(java.util.Locale.ROOT).contains("for update")) {
                assertEquals("lockByKey", select.getAttribute("id"));
                assertEquals("true", select.getAttribute("flushCache"));
                assertEquals("false", select.getAttribute("useCache"));
                lockingReads++;
            }
        }
        assertEquals(1, lockingReads);
        assertFalse(sql.contains(" limit "));
        Document chat = mapper("AiChatMapper");
        var inserts = chat.getElementsByTagName("insert");
        boolean foundLegacyInsert = false;
        for (int index = 0; index < inserts.getLength(); index++) {
            var insert = (org.w3c.dom.Element) inserts.item(index);
            if (insert.getAttribute("id").equals("insertToolCall")) {
                foundLegacyInsert = true;
                assertFalse(insert.getTextContent().contains("proposal_key"));
            }
        }
        assertTrue(foundLegacyInsert);
    }

    @Test
    void storageUsesTenantChatLifecycleAndDoesNotClaimPersonReaderRestrictions() {
        assertTrue(TablePlaneRegistry.ORG_DATA_TABLES.contains("ai_assistant_proposal_set"));
        assertFalse(TablePlaneRegistry.CONTROL_PLANE_TABLES.contains("ai_assistant_proposal_set"));
        assertTrue(TenantScopeInterceptor.SCOPED_NAMESPACES.contains(NAMESPACE));
        assertFalse(ProcessingRestrictionRegistry.enrollments().containsKey(NAMESPACE));
        assertFalse(ProcessingRestrictionRegistry.personReaderAllowlist().containsKey(NAMESPACE));
        var declaration = TenantLifecycleRegistry.require("ai_assistant_proposal_set");
        assertTrue(declaration.reach() instanceof TenantLifecycleRegistry.Cascade);
        var cascade = (TenantLifecycleRegistry.Cascade) declaration.reach();
        assertEquals("ai_chat_message", cascade.parentTable());
        assertEquals("fk_ai_assistant_proposal_set_message", cascade.constraintName());
        assertEquals(Set.of("workspace_id", "session_id", "message_id"), cascade.columns().stream()
                .map(TenantLifecycleRegistry.ColumnLink::childColumn).collect(java.util.stream.Collectors.toSet()));
        assertEquals(TablePlaneRegistry.ORG_DATA_TABLES, TenantLifecycleRegistry.declarations().keySet());
        for (String table : Set.of("ai_chat_message", "ai_chat_tool_call", "ai_chat_turn")) {
            assertTrue(TenantLifecycleRegistry.require(table).direct(), table);
        }
    }

    @Test
    void accountErasureRetainsSessionsContainingOnlyProposalSetRequesterProvenance() throws Exception {
        var selects = mapper("AiChatMapper").getElementsByTagName("select");
        boolean found = false;
        for (int index = 0; index < selects.getLength(); index++) {
            var select = (org.w3c.dom.Element) selects.item(index);
            if (select.getAttribute("id").equals("lockSessionsForUserErasureAnywhere")) {
                found = true;
                String sql = select.getTextContent().replaceAll("\\s+", " ");
                assertTrue(sql.contains("OR EXISTS ( SELECT 1 FROM ai_assistant_proposal_set p"
                        + " WHERE p.workspace_id = s.workspace_id AND p.session_id = s.id"
                        + " AND p.requested_by_user_id = #{userId} )"));
                assertTrue(sql.contains("ORDER BY s.workspace_id ASC, s.id ASC FOR UPDATE OF s"));
            }
        }
        assertTrue(found);
    }

    private Document mapper(String name) throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        try (InputStream input = getClass().getClassLoader().getResourceAsStream("mappers/" + name + ".xml")) {
            assertNotNull(input);
            return factory.newDocumentBuilder().parse(input);
        }
    }
}
