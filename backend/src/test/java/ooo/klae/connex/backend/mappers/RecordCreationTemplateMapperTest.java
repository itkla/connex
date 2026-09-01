package ooo.klae.connex.backend.mappers;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import ooo.klae.connex.backend.beans.RecordCreationTemplate;
import ooo.klae.connex.backend.beans.RecordCreationTemplateVersion;
import ooo.klae.connex.backend.beans.Workspace;

class RecordCreationTemplateMapperTest extends AbstractMapperTest {

    @Autowired private RecordCreationTemplateMapper mapper;
    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    void workspacePredicatesAndCurrentVersionJoinFailClosed() throws Exception {
        Workspace other = newWorkspace();
        RecordCreationTemplate root = insertTemplate(workspace.getId(), "person", 0, 101);
        RecordCreationTemplateVersion first = insertVersion(root, 1, "First", 101);
        assertEquals(1, mapper.installCurrentVersion(
            workspace.getId(), root.getId(), first.getId(), 0, 101));

        assertNotNull(mapper.getSet(workspace.getId(), "person"));
        assertNull(mapper.getSet(other.getId(), "person"));
        assertNotNull(mapper.getRoot(workspace.getId(), root.getId()));
        assertNull(mapper.getRoot(other.getId(), root.getId()));
        assertNotNull(mapper.getCurrentVersion(workspace.getId(), root.getId()));
        assertNull(mapper.getCurrentVersion(other.getId(), root.getId()));
        assertNull(mapper.getVersion(other.getId(), root.getId(), 1));
        assertEquals(List.of(root.getId()), mapper.listRoots(
            workspace.getId(), "person", false).stream().map(RecordCreationTemplate::getId).toList());
        assertEquals(List.of(), mapper.listRoots(other.getId(), "person", true));
    }

    @Test
    void setAndRootCompareAndSwapReturnExactRowCounts() throws Exception {
        RecordCreationTemplate root = insertTemplate(workspace.getId(), "company", 0, 101);
        RecordCreationTemplateVersion version = insertVersion(root, 1, "Company", 101);

        assertNotNull(mapper.getSetForUpdate(workspace.getId(), "company"));
        assertNotNull(mapper.getRootForUpdate(workspace.getId(), root.getId()));
        assertEquals(0, mapper.installCurrentVersion(
            workspace.getId(), root.getId(), version.getId(), 99, 101));
        assertEquals(1, mapper.installCurrentVersion(
            workspace.getId(), root.getId(), version.getId(), 0, 101));
        assertEquals(0, mapper.updateStatus(
            workspace.getId(), root.getId(), "enabled", null, 0, 101));
        assertEquals(1, mapper.updateStatus(
            workspace.getId(), root.getId(), "enabled", null, 1, 101));
        assertEquals(0, mapper.advanceSetRevision(workspace.getId(), "company", 7));
        assertEquals(1, mapper.advanceSetRevision(workspace.getId(), "company", 0));
        assertEquals(0, mapper.setDefault(workspace.getId(), "company", root.getId(), 0));
        assertEquals(1, mapper.setDefault(workspace.getId(), "company", root.getId(), 1));
        assertEquals(root.getId(), mapper.getSet(workspace.getId(), "company").getDefaultTemplateId());
    }

    @Test
    void rootsAreOrderedAndArchivedRowsAreOptional() throws Exception {
        RecordCreationTemplate last = insertTemplate(workspace.getId(), "deal", 5, 101);
        RecordCreationTemplate first = insertTemplate(workspace.getId(), "deal", 1, 101);
        RecordCreationTemplate archived = insertTemplate(workspace.getId(), "deal", 3, 101);
        assertEquals(1, mapper.updateStatus(
            workspace.getId(), archived.getId(), "archived",
            java.time.LocalDateTime.now(), 0, 101));

        assertEquals(
            List.of(first.getId(), last.getId()),
            mapper.listRoots(workspace.getId(), "deal", false).stream()
                .map(RecordCreationTemplate::getId).toList());
        assertEquals(
            List.of(first.getId(), archived.getId(), last.getId()),
            mapper.listRoots(workspace.getId(), "deal", true).stream()
                .map(RecordCreationTemplate::getId).toList());
        assertEquals(
            List.of(last.getId(), first.getId(), archived.getId()).stream().sorted().toList(),
            mapper.listRootsForUpdate(workspace.getId(), "deal").stream()
                .map(RecordCreationTemplate::getId).toList());
    }

    @Test
    void versionRowsRemainImmutableWhenCurrentVersionAdvances() throws Exception {
        RecordCreationTemplate root = insertTemplate(workspace.getId(), "person", 0, 101);
        RecordCreationTemplateVersion first = insertVersion(root, 1, "First", 101);
        assertEquals(1, mapper.installCurrentVersion(
            workspace.getId(), root.getId(), first.getId(), 0, 101));
        RecordCreationTemplateVersion second = insertVersion(root, 2, "Second", 102);
        assertEquals(1, mapper.installCurrentVersion(
            workspace.getId(), root.getId(), second.getId(), 1, 102));

        assertEquals(3, mapper.nextVersionNumber(workspace.getId(), root.getId()));
        assertEquals("Second", mapper.getCurrentVersion(
            workspace.getId(), root.getId()).getNameEn());
        RecordCreationTemplateVersion retained = mapper.getVersion(
            workspace.getId(), root.getId(), 1);
        assertEquals("First", retained.getNameEn());
        assertArrayEquals(first.getDefinitionHash(), retained.getDefinitionHash());
    }

    @Test
    void offboardingClearRemovesAllActorReferencesAcrossWorkspaces() throws Exception {
        Workspace other = newWorkspace();
        int actorId = 876543;
        RecordCreationTemplate firstRoot = insertTemplate(workspace.getId(), "person", 0, actorId);
        insertVersion(firstRoot, 1, "First", actorId);
        RecordCreationTemplate secondRoot = insertTemplate(other.getId(), "company", 0, actorId);
        insertVersion(secondRoot, 1, "Second", actorId);

        assertTrue(mapper.clearUserReferencesAnywhere(actorId) > 0);

        assertEquals(0, jdbcTemplate.queryForObject("""
            SELECT COUNT(*)
            FROM record_creation_template
            WHERE created_by_id = ? OR updated_by_id = ?
            """, Integer.class, actorId, actorId));
        assertEquals(0, jdbcTemplate.queryForObject("""
            SELECT COUNT(*)
            FROM record_creation_template_version
            WHERE created_by_id = ?
            """, Integer.class, actorId));
    }

    private RecordCreationTemplate insertTemplate(
            int workspaceId, String recordType, int position, int actorId) {
        mapper.insertSetIfAbsent(workspaceId, recordType);
        RecordCreationTemplate root = new RecordCreationTemplate();
        root.setWorkspaceId(workspaceId);
        root.setRecordType(recordType);
        root.setStatus("disabled");
        root.setPosition(position);
        root.setCreatedById(actorId);
        root.setUpdatedById(actorId);
        mapper.insertRoot(root);
        return root;
    }

    private RecordCreationTemplateVersion insertVersion(
            RecordCreationTemplate root, int number, String name, int actorId) throws Exception {
        String definition = "{\"schemaVersion\":1,\"groups\":[]}";
        RecordCreationTemplateVersion version = new RecordCreationTemplateVersion();
        version.setWorkspaceId(root.getWorkspaceId());
        version.setTemplateId(root.getId());
        version.setVersionNumber(number);
        version.setNameEn(name);
        version.setNameJa("テンプレート");
        version.setDefinitionJson(definition);
        version.setDefinitionHash(MessageDigest.getInstance("SHA-256")
            .digest(definition.getBytes(StandardCharsets.UTF_8)));
        version.setCreatedById(actorId);
        mapper.insertVersion(version);
        return version;
    }

    private Workspace newWorkspace() {
        Workspace created = new Workspace();
        created.setName("Template test " + unique());
        created.setSlug("template-test-" + unique());
        workspaceMapper.insert(created);
        return created;
    }
}
