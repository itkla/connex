package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import ooo.klae.connex.backend.beans.AiOutputCache;
import ooo.klae.connex.backend.beans.Attachment;
import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.CustomFieldDefinition;
import ooo.klae.connex.backend.beans.CustomFieldValue;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.Organization;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.Pipeline;
import ooo.klae.connex.backend.beans.Stage;
import ooo.klae.connex.backend.beans.Tag;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.dto.TenantResidualReport;
import ooo.klae.connex.backend.dto.WorkspaceLifecycleRef;
import ooo.klae.connex.backend.mappers.AiOutputCacheMapper;
import ooo.klae.connex.backend.mappers.AttachmentMapper;
import ooo.klae.connex.backend.mappers.CustomFieldDefinitionMapper;
import ooo.klae.connex.backend.mappers.CustomFieldValueMapper;
import ooo.klae.connex.backend.mappers.OrganizationMapper;
import ooo.klae.connex.backend.mappers.TenantLifecycleControlMapper;
import ooo.klae.connex.backend.storage.ManagedObjectService;
import ooo.klae.connex.backend.storage.ManagedObjectService.StoredBinary;
import ooo.klae.connex.backend.storage.ObjectStorageProperties;
import ooo.klae.connex.backend.tenant.TenantLifecycleRegistry;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
        "connex.tenant-lifecycle.teardown-settle-delay=0s",
        "connex.tenant-lifecycle.export-lease-wait-timeout=1s"
    })
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class TenantLifecycleDrillIntegrationTest extends AbstractServiceTest {
    private static final byte[] BINARY = "tenant-lifecycle-binary".getBytes(StandardCharsets.UTF_8);

    @Autowired private OrganizationMapper organizationMapper;
    @Autowired private OrgMemberService orgMemberService;
    @Autowired private TenantLifecycleControlMapper controlMapper;
    @Autowired private TenantExportService exportService;
    @Autowired private TenantTeardownService teardownService;
    @Autowired private ManagedObjectService managedObjectService;
    @Autowired private ObjectStorageProperties objectStorageProperties;
    @Autowired private AttachmentMapper attachmentMapper;
    @Autowired private CustomFieldDefinitionMapper customFieldDefinitionMapper;
    @Autowired private CustomFieldValueMapper customFieldValueMapper;
    @Autowired private AiOutputCacheMapper aiOutputCacheMapper;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbcTemplate;

    private Organization drillOrganization;
    private Workspace drillWorkspace;

    @AfterEach
    void cleanCommittedDrillRoots() {
        if (drillOrganization != null) {
            WorkspaceLifecycleRef remaining = drillWorkspace == null
                ? null
                : controlMapper.findWorkspaceInOrg(
                    drillOrganization.getId(),
                    drillWorkspace.getId());
            if (remaining != null) {
                try {
                    teardownService.teardownWorkspace(
                        drillOrganization.getId(),
                        drillWorkspace.getId(),
                        currentUser.getId(),
                        drillWorkspace.getSlug());
                } catch (RuntimeException ignored) {
                }
            }
            jdbcTemplate.update(
                "DELETE FROM organization WHERE id = ?",
                drillOrganization.getId());
        }
        if (currentUser != null) {
            jdbcTemplate.update(
                "DELETE FROM workspace_member WHERE user_id = ?",
                currentUser.getId());
            jdbcTemplate.update(
                "DELETE FROM app_user WHERE id = ?",
                currentUser.getId());
        }
    }

    @Test
    void exportsRestrictedHoldingsAndBinaryThenTeardownLeavesNoResiduals() throws Exception {
        createDedicatedDrillRoots();
        Fixture fixture = seedRepresentativeTenant();
        WorkspaceLifecycleRef target = controlMapper.findWorkspaceInOrg(
            drillOrganization.getId(),
            drillWorkspace.getId());
        assertNotNull(target);

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        exportService.prepare(
            drillOrganization.getId(),
            drillWorkspace.getId(),
            currentUser.getId()).writeTo(output);
        Map<String, byte[]> entries = zipEntries(output.toByteArray());

        assertTrue(entries.containsKey("manifest.json"));
        assertTrue(entries.containsKey("data/person.jsonl"));
        assertTrue(entries.containsKey("data/company.jsonl"));
        assertTrue(entries.containsKey("data/deal.jsonl"));
        assertTrue(entries.containsKey("data/activity.jsonl"));
        assertTrue(entries.containsKey("data/note.jsonl"));
        assertTrue(entries.containsKey("data/task.jsonl"));
        assertTrue(entries.containsKey("data/custom_field_definition.jsonl"));
        assertTrue(entries.containsKey("data/custom_field_value.jsonl"));
        assertTrue(entries.containsKey("data/notification.jsonl"));
        assertTrue(entries.containsKey("data/saved_view.jsonl"));
        assertTrue(entries.containsKey("data/ai_output_cache.jsonl"));
        String personJsonl = text(entries, "data/person.jsonl");
        assertTrue(personJsonl.contains(fixture.restrictedPersonName()));
        assertTrue(personJsonl.contains("suspended_at"));
        assertTrue(personJsonl.contains("provision_ceased_at"));
        assertArrayEquals(BINARY, entries.get("objects/" + fixture.objectKey()));
        String manifest = text(entries, "manifest.json");
        assertTrue(manifest.contains("\"schemaVersion\":1"));
        assertTrue(manifest.contains("\"objectCount\":1"));
        assertTrue(Files.exists(fixture.objectPath()));

        teardownService.teardownWorkspace(
            drillOrganization.getId(),
            drillWorkspace.getId(),
            currentUser.getId(),
            drillWorkspace.getSlug());

        TenantResidualReport residual = teardownService.verifyWorkspaceDeleted(
            target,
            currentUser.getId());
        assertTrue(residual.clean());
        assertEquals(
            TenantLifecycleRegistry.declarations().size(),
            residual.tableRows().size());
        assertEquals(0, residual.totalRows());
        assertTrue(residual.tableRows().values().stream().allMatch(count -> count == 0));
        assertFalse(Files.exists(fixture.objectPath()));
        assertEquals(0, jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM workspace WHERE id = ?",
            Integer.class,
            drillWorkspace.getId()));
        assertTrue(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM audit_log WHERE action = 'tenant.workspace.teardown'"
                + " AND workspace_id IS NULL AND org_id = ?",
            Integer.class,
            drillOrganization.getId()) > 0);
    }

    private void createDedicatedDrillRoots() {
        drillOrganization = new Organization();
        drillOrganization.setName("Lifecycle Org " + unique());
        drillOrganization.setSlug("lifecycle-org-" + unique());
        organizationMapper.insert(drillOrganization);
        orgMemberService.addFoundingOwner(
            drillOrganization.getId(),
            currentUser.getId());
        drillWorkspace = new Workspace();
        drillWorkspace.setOrgId(drillOrganization.getId());
        drillWorkspace.setName("Lifecycle Workspace " + unique());
        drillWorkspace.setSlug("lifecycle-workspace-" + unique());
        workspaceMapper.insert(drillWorkspace);
        workspaceMapper.addMember(
            drillWorkspace.getId(),
            currentUser.getId(),
            "owner");
        workspace = drillWorkspace;
        authenticateAs(currentUser, drillWorkspace.getId());
    }

    private Fixture seedRepresentativeTenant() {
        Company company = newCompany();
        Person visible = newPerson(company);
        Person restricted = newPerson(company);
        personMapper.updateProcessingRestrictions(
            drillWorkspace.getId(),
            restricted.getId(),
            true,
            true);
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);
        Deal deal = newDeal(pipeline, stage, company);
        newActivity(currentUser, visible, deal);
        newNote(currentUser, visible, deal);
        newTask(currentUser, visible, deal);
        Tag tag = newTag();
        personMapper.addTag(
            drillWorkspace.getId(),
            visible.getId(),
            tag.getId());
        CustomFieldDefinition definition = new CustomFieldDefinition();
        definition.setWorkspaceId(drillWorkspace.getId());
        definition.setEntityType("person");
        definition.setFieldKey("lifecycle_" + unique());
        definition.setLabel("Lifecycle field");
        definition.setFieldType("text");
        definition.setDataClassification("standard");
        customFieldDefinitionMapper.insert(definition);
        CustomFieldValue value = new CustomFieldValue();
        value.setWorkspaceId(drillWorkspace.getId());
        value.setDefinitionId(definition.getId());
        value.setEntityType("person");
        value.setEntityId(visible.getId());
        value.setValueText("retained");
        customFieldValueMapper.upsert(value);
        newNotification(drillWorkspace.getId(), currentUser.getId());
        jdbcTemplate.update(
            "INSERT INTO saved_view"
                + " (workspace_id, user_id, record_type, name, config_json)"
                + " VALUES (?, ?, 'person', ?, JSON_OBJECT())",
            drillWorkspace.getId(),
            currentUser.getId(),
            "Lifecycle view " + unique());
        AiOutputCache cache = new AiOutputCache();
        cache.setWorkspaceId(drillWorkspace.getId());
        cache.setFeature("lifecycle_drill");
        cache.setSubjectAId(deal.getId());
        cache.setSubjectBId(0);
        cache.setContentHash("a".repeat(64));
        cache.setPayload("{\"retained\":true}");
        cache.setWarnings(0);
        cache.setGeneratedAt("2026-07-25T00:00:00Z");
        aiOutputCacheMapper.upsert(cache);
        StoredBinary binary = storeAttachment(company);
        String token = binary.url().substring("/api/attachments/content/".length());
        String objectKey = "workspaces/" + drillWorkspace.getId()
            + "/attachments/" + token;
        Path objectPath = objectStorageProperties.filesystemRootPath()
            .resolve(objectKey + ".object");
        return new Fixture(restricted.getName(), objectKey, objectPath);
    }

    private StoredBinary storeAttachment(Company company) {
        AtomicReference<StoredBinary> stored = new AtomicReference<>();
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.executeWithoutResult(status -> {
            StoredBinary binary = managedObjectService.storeAttachment(
                drillWorkspace.getId(),
                "lifecycle.txt",
                "text/plain",
                BINARY);
            Attachment attachment = new Attachment();
            attachment.setWorkspaceId(drillWorkspace.getId());
            attachment.setEntityType("company");
            attachment.setEntityId(company.getId());
            attachment.setFileName(binary.fileName());
            attachment.setUrl(binary.url());
            attachment.setContentType(binary.contentType());
            attachment.setSize(binary.size());
            attachment.setUploadedBy(currentUser);
            attachmentMapper.insert(attachment);
            stored.set(binary);
        });
        return stored.get();
    }

    private static Map<String, byte[]> zipEntries(byte[] zipBytes) throws Exception {
        Map<String, byte[]> entries = new HashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                entries.put(entry.getName(), zip.readAllBytes());
                zip.closeEntry();
            }
        }
        return entries;
    }

    private static String text(Map<String, byte[]> entries, String path) {
        return new String(entries.get(path), StandardCharsets.UTF_8);
    }

    private record Fixture(
            String restrictedPersonName,
            String objectKey,
            Path objectPath) {
    }
}
