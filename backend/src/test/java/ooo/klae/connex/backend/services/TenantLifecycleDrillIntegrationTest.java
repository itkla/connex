package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import ooo.klae.connex.backend.beans.AiOutputCache;
import ooo.klae.connex.backend.beans.Attachment;
import ooo.klae.connex.backend.beans.AuditLog;
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
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.mappers.AiOutputCacheMapper;
import ooo.klae.connex.backend.mappers.AttachmentMapper;
import ooo.klae.connex.backend.mappers.AuditLogMapper;
import ooo.klae.connex.backend.mappers.CustomFieldDefinitionMapper;
import ooo.klae.connex.backend.mappers.CustomFieldValueMapper;
import ooo.klae.connex.backend.mappers.OrganizationMapper;
import ooo.klae.connex.backend.mappers.TenantLifecycleControlMapper;
import ooo.klae.connex.backend.storage.ManagedObjectService;
import ooo.klae.connex.backend.storage.ManagedObjectService.StoredBinary;
import ooo.klae.connex.backend.storage.ScannedUpload;
import ooo.klae.connex.backend.storage.UploadContentInspector;
import ooo.klae.connex.backend.storage.UploadMalwareScanner;
import ooo.klae.connex.backend.storage.UploadPolicy.UploadPurpose;
import ooo.klae.connex.backend.storage.UploadSource;
import ooo.klae.connex.backend.storage.ObjectStorageProperties;
import ooo.klae.connex.backend.tenant.ControlWorkspaceLifecycleRegistry;
import ooo.klae.connex.backend.tenant.TenantLifecycleRegistry;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
        "connex.tenant-lifecycle.teardown-settle-delay=0s",
    })
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class TenantLifecycleDrillIntegrationTest extends AbstractServiceTest {
    private static final byte[] BINARY = "tenant-lifecycle-binary".getBytes(StandardCharsets.UTF_8);

    @Autowired private OrganizationMapper organizationMapper;
    @Autowired private OrgMemberService orgMemberService;
    @Autowired private TenantLifecycleControlMapper controlMapper;
    @Autowired private TenantLifecycleControlOperations controlOperations;
    @Autowired private TenantExportService exportService;
    @Autowired private TenantTeardownService teardownService;
    @Autowired private ManagedObjectService managedObjectService;
    @Autowired private UploadContentInspector uploadContentInspector;
    @Autowired private UploadMalwareScanner uploadMalwareScanner;
    @Autowired private ObjectStorageProperties objectStorageProperties;
    @Autowired private AttachmentMapper attachmentMapper;
    @Autowired private CustomFieldDefinitionMapper customFieldDefinitionMapper;
    @Autowired private CustomFieldValueMapper customFieldValueMapper;
    @Autowired private AiOutputCacheMapper aiOutputCacheMapper;
    @Autowired private AuditLogMapper auditLogMapper;
    @Autowired private AuditIntegrityService auditIntegrityService;
    @Autowired private IdentityBackfillTransaction identityBackfillTransaction;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbcTemplate;

    private Organization drillOrganization;
    private Organization otherOrganization;
    private Workspace drillWorkspace;

    @AfterEach
    void cleanCommittedDrillRoots() {
        if (otherOrganization != null) {
            jdbcTemplate.update(
                "DELETE FROM organization WHERE id = ?",
                otherOrganization.getId());
        }
        if (drillOrganization != null) {
            jdbcTemplate.update(
                "DELETE FROM tenant_operation_lease WHERE org_id = ?",
                drillOrganization.getId());
            jdbcTemplate.update(
                "UPDATE data_subject_request SET status = 'closed', closed_at = NOW()"
                    + " WHERE org_id = ? AND status NOT IN ('closed', 'refused')",
                drillOrganization.getId());
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
        assertTrue(entries.containsKey("data/person_identity.jsonl"));
        assertTrue(entries.containsKey("data/company.jsonl"));
        assertTrue(entries.containsKey("data/company_identity.jsonl"));
        assertTrue(entries.containsKey("data/identity_collision.jsonl"));
        assertTrue(entries.containsKey("data/deal.jsonl"));
        assertTrue(entries.containsKey("data/activity.jsonl"));
        assertTrue(entries.containsKey("data/note.jsonl"));
        assertTrue(entries.containsKey("data/task.jsonl"));
        assertTrue(entries.containsKey("data/custom_field_definition.jsonl"));
        assertTrue(entries.containsKey("data/custom_field_value.jsonl"));
        assertTrue(entries.containsKey("data/notification.jsonl"));
        assertTrue(entries.containsKey("data/saved_view.jsonl"));
        assertTrue(entries.containsKey("data/ai_output_cache.jsonl"));
        assertTrue(entries.containsKey("data/ai_chat_session.jsonl"));
        assertTrue(entries.containsKey("data/ai_chat_session_participant.jsonl"));
        assertTrue(entries.containsKey("data/ai_chat_message.jsonl"));
        assertTrue(entries.containsKey("data/ai_chat_tool_call.jsonl"));
        assertTrue(entries.containsKey("data/ai_chat_turn.jsonl"));
        String personJsonl = text(entries, "data/person.jsonl");
        assertTrue(personJsonl.contains(fixture.restrictedPersonName()));
        assertTrue(personJsonl.contains("suspended_at"));
        assertTrue(personJsonl.contains("provision_ceased_at"));
        assertTrue(text(entries, "data/person_identity.jsonl").contains("backfill"));
        assertTrue(text(entries, "data/ai_chat_session.jsonl").contains(fixture.chatTitle()));
        assertTrue(text(entries, "data/ai_chat_message.jsonl").contains(fixture.chatMessage()));
        assertTrue(text(entries, "data/ai_chat_tool_call.jsonl").contains(fixture.toolName()));
        assertTrue(text(entries, "data/ai_chat_turn.jsonl").contains(fixture.terminalReason()));
        assertArrayEquals(BINARY, entries.get("objects/" + fixture.objectKey()));
        String manifest = text(entries, "manifest.json");
        assertTrue(manifest.contains("\"schemaVersion\":1"));
        assertTrue(manifest.contains("\"objectCount\":1"));
        assertTrue(Files.exists(fixture.objectPath()));
        AuditLog retained = new AuditLog();
        retained.setWorkspaceId(drillWorkspace.getId());
        retained.setOrgId(drillOrganization.getId());
        retained.setAction("test.lifecycle.retained");
        retained.setEntityType("workspace");
        retained.setEntityId(drillWorkspace.getId());
        retained.setTargetLabel(drillWorkspace.getSlug());
        retained.setOutcome("success");
        retained.setSummary("Workspace audit integrity survives teardown");
        auditIntegrityService.appendIndependent(retained);

        teardownService.teardownWorkspace(
            drillOrganization.getId(),
            drillWorkspace.getId(),
            currentUser.getId(),
            drillWorkspace.getSlug());

        TenantResidualReport residual = teardownService.verifyWorkspaceDeleted(
            target,
            currentUser.getId());
        assertTrue(residual.clean());
        Set<String> lifecycleTables = new HashSet<>(
            TenantLifecycleRegistry.declarations().keySet());
        lifecycleTables.addAll(ControlWorkspaceLifecycleRegistry.declarations().keySet());
        assertEquals(lifecycleTables, residual.tableRows().keySet());
        assertEquals(0, residual.totalRows());
        assertTrue(residual.tableRows().values().stream().allMatch(count -> count == 0));
        assertEquals(0, rowCount("ai_chat_session"));
        assertEquals(0, rowCount("ai_chat_session_participant"));
        assertEquals(0, rowCount("ai_chat_message"));
        assertEquals(0, rowCount("ai_chat_tool_call"));
        assertEquals(0, rowCount("ai_chat_turn"));
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
        Map<String, Object> retainedReferences = jdbcTemplate.queryForMap(
            "SELECT workspace_id, org_id, integrity_workspace_id, integrity_org_id"
                + " FROM audit_log WHERE id = ?",
            retained.getId());
        assertNull(retainedReferences.get("workspace_id"));
        assertEquals(drillOrganization.getId(), retainedReferences.get("org_id"));
        assertEquals(
            drillWorkspace.getId(),
            retainedReferences.get("integrity_workspace_id"));
        assertEquals(
            drillOrganization.getId(),
            retainedReferences.get("integrity_org_id"));
        retained.setWorkspaceId(null);
        assertTrue(auditIntegrityService.hasValidIntegrity(retained));
        assertTrue(auditLogMapper.findOrgExport(
            drillOrganization.getId(),
            500,
            0).stream().noneMatch(
                entry -> "test.lifecycle.retained".equals(entry.getAction())));
    }

    @Test
    void tearsDownOrganizationAndAllWorkspaceRoots() {
        createDedicatedDrillRoots();
        int orgId = drillOrganization.getId();
        int workspaceId = drillWorkspace.getId();

        teardownService.teardownOrganization(
            orgId,
            currentUser.getId(),
            drillOrganization.getSlug());

        assertEquals(0, jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM workspace WHERE id = ?",
            Integer.class,
            workspaceId));
        assertEquals(0, jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM organization WHERE id = ?",
            Integer.class,
            orgId));
        assertTrue(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM audit_log"
                + " WHERE action = 'tenant.organization.teardown'"
                + " AND org_id IS NULL"
                + " AND chain_scope_type = 'organization'"
                + " AND chain_scope_id = ?",
            Integer.class,
            orgId) > 0);
    }

    @Test
    void openSubjectRequestBlocksTeardownAndTerminalDeletionClearsTheRetainedLink() {
        createDedicatedDrillRoots();
        int orgId = drillOrganization.getId();
        int workspaceId = drillWorkspace.getId();
        Person subject = newPerson(newCompany());
        long requestId = insertOpenSubjectRequest(orgId, workspaceId, subject.getId());

        assertThrows(
            ConflictException.class,
            () -> teardownService.teardownWorkspace(
                orgId,
                workspaceId,
                currentUser.getId(),
                drillWorkspace.getSlug()));
        assertEquals(1, jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM workspace WHERE id = ?",
            Integer.class,
            workspaceId));
        assertEquals(1, jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM person WHERE id = ?",
            Integer.class,
            subject.getId()));

        jdbcTemplate.update(
            "UPDATE data_subject_request SET status = 'closed', closed_at = NOW() WHERE id = ?",
            requestId);
        teardownService.teardownWorkspace(
            orgId,
            workspaceId,
            currentUser.getId(),
            drillWorkspace.getSlug());

        Map<String, Object> retained = jdbcTemplate.queryForMap(
            "SELECT org_id, subject_workspace_id, subject_person_id"
                + " FROM data_subject_request WHERE id = ?",
            requestId);
        assertEquals(orgId, retained.get("org_id"));
        assertNull(retained.get("subject_workspace_id"));
        assertNull(retained.get("subject_person_id"));
    }

    @Test
    void aSubjectRequestOpenedAfterTheUnlockedCheckStillRefusesTheLifecycleFence() {
        createDedicatedDrillRoots();
        int orgId = drillOrganization.getId();
        int workspaceId = drillWorkspace.getId();
        Person subject = newPerson(newCompany());
        insertOpenSubjectRequest(orgId, workspaceId, subject.getId());

        assertThrows(
            ConflictException.class,
            () -> controlOperations.acquireWorkspaceTeardown(
                orgId,
                workspaceId,
                currentUser.getId()));
        assertEquals("active", jdbcTemplate.queryForObject(
            "SELECT lifecycle_state FROM workspace WHERE id = ?",
            String.class,
            workspaceId));
        assertEquals(0, jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM tenant_operation_lease WHERE workspace_id = ?",
            Integer.class,
            workspaceId));

        assertThrows(
            ConflictException.class,
            () -> controlOperations.markOrganizationTearingDown(orgId, currentUser.getId()));
        assertEquals("active", jdbcTemplate.queryForObject(
            "SELECT lifecycle_state FROM organization WHERE id = ?",
            String.class,
            orgId));
    }

    @Test
    void persistedExportLeasesNeverAgeOutAndRemainFailClosed() {
        createDedicatedDrillRoots();
        int orgId = drillOrganization.getId();
        int workspaceId = drillWorkspace.getId();
        otherOrganization = new Organization();
        otherOrganization.setName("Lifecycle Neighbour " + unique());
        otherOrganization.setSlug("lifecycle-neighbour-" + unique());
        organizationMapper.insert(otherOrganization);
        String fresh = insertOperationLease(orgId, workspaceId, "export", 0);
        String stale = insertOperationLease(orgId, workspaceId, "export", 60);
        String teardown = insertOperationLease(orgId, workspaceId, "teardown", 60);
        String neighbour = insertOperationLease(
            otherOrganization.getId(),
            workspaceId + 1,
            "export",
            0);

        assertEquals(2, controlMapper.countOperationLeases(workspaceId, "export"));
        assertEquals(3, controlMapper.countOperationLeasesInOrg(orgId));
        assertEquals(1, controlMapper.countOperationLeasesInOrg(otherOrganization.getId()));

        assertTrue(leaseExists(fresh));
        assertTrue(leaseExists(stale));
        assertTrue(leaseExists(teardown));
        assertTrue(leaseExists(neighbour));
    }

    private String insertOperationLease(
            int orgId,
            int workspaceId,
            String leaseKind,
            int ageMinutes) {
        String token = UUID.randomUUID().toString();
        jdbcTemplate.update(
            "INSERT INTO tenant_operation_lease"
                + " (org_id, workspace_id, lease_kind, lease_token, created_at)"
                + " VALUES (?, ?, ?, ?, NOW(6) - INTERVAL ? MINUTE)",
            orgId,
            workspaceId,
            leaseKind,
            token,
            ageMinutes);
        return token;
    }

    private boolean leaseExists(String leaseToken) {
        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM tenant_operation_lease WHERE lease_token = ?",
            Integer.class,
            leaseToken);
        assertNotNull(count);
        return count == 1;
    }

    private long insertOpenSubjectRequest(int orgId, int workspaceId, int personId) {
        jdbcTemplate.update(
            "INSERT INTO data_subject_request"
                + " (org_id, request_type, status, requester_name, subject_name,"
                + " subject_workspace_id, subject_person_id)"
                + " VALUES (?, 'disclosure', 'received', ?, ?, ?, ?)",
            orgId,
            "Requester " + unique(),
            "Subject " + unique(),
            workspaceId,
            personId);
        Long requestId = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        assertNotNull(requestId);
        return requestId;
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
        newPerson(company);
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
        String chatTitle = "Lifecycle assistant session " + unique();
        String chatMessage = "Lifecycle assistant message " + unique();
        String toolName = "lifecycle_lookup_" + unique();
        String terminalReason = "Lifecycle timeout " + unique();
        KeyHolder chatSessionKeyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO ai_chat_session"
                    + " (workspace_id, created_by_user_id, title, visibility, status)"
                    + " VALUES (?, ?, ?, 'shared', 'active')",
                Statement.RETURN_GENERATED_KEYS);
            statement.setInt(1, drillWorkspace.getId());
            statement.setInt(2, currentUser.getId());
            statement.setString(3, chatTitle);
            return statement;
        }, chatSessionKeyHolder);
        Number chatSessionKey = chatSessionKeyHolder.getKey();
        assertNotNull(chatSessionKey);
        int chatSessionId = chatSessionKey.intValue();
        jdbcTemplate.update(
            "INSERT INTO ai_chat_session_participant"
                + " (workspace_id, session_id, user_id) VALUES (?, ?, ?)",
            drillWorkspace.getId(),
            chatSessionId,
            currentUser.getId());
        KeyHolder chatMessageKeyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO ai_chat_message"
                    + " (workspace_id, session_id, seq, author_kind, author_user_id, content)"
                    + " VALUES (?, ?, 1, 'user', ?, ?)",
                Statement.RETURN_GENERATED_KEYS);
            statement.setInt(1, drillWorkspace.getId());
            statement.setInt(2, chatSessionId);
            statement.setInt(3, currentUser.getId());
            statement.setString(4, chatMessage);
            return statement;
        }, chatMessageKeyHolder);
        Number chatMessageKey = chatMessageKeyHolder.getKey();
        assertNotNull(chatMessageKey);
        int chatMessageId = chatMessageKey.intValue();
        jdbcTemplate.update(
            "INSERT INTO ai_chat_tool_call"
                + " (workspace_id, message_id, tool_name, status, arguments_json, result_json)"
                + " VALUES (?, ?, ?, 'executed', JSON_OBJECT('recognizable', true),"
                + " JSON_OBJECT('retained', true))",
            drillWorkspace.getId(),
            chatMessageId,
            toolName);
        jdbcTemplate.update(
            "INSERT INTO ai_chat_turn"
                + " (workspace_id, session_id, requested_by_user_id, status, terminal_reason)"
                + " VALUES (?, ?, ?, 'timed_out', ?)",
            drillWorkspace.getId(),
            chatSessionId,
            currentUser.getId(),
            terminalReason);
        identityBackfillTransaction.backfillPersonPage(
            null, drillWorkspace.getId(), 0, 500);
        identityBackfillTransaction.backfillCompanyPage(
            null, drillWorkspace.getId(), 0, 500);
        int collisionMemberships = identityBackfillTransaction.rebuildCollisionReport(
            null, drillWorkspace.getId());
        assertTrue(rowCount("person_identity") > 0);
        assertTrue(rowCount("company_identity") > 0);
        assertTrue(collisionMemberships > 0);
        StoredBinary binary = storeAttachment(company);
        String token = binary.url().substring("/api/attachments/content/".length());
        String objectKey = "workspaces/" + drillWorkspace.getId()
            + "/attachments/" + token;
        Path objectPath = objectStorageProperties.filesystemRootPath()
            .resolve(objectKey + ".object");
        return new Fixture(
            restricted.getName(),
            objectKey,
            objectPath,
            chatTitle,
            chatMessage,
            toolName,
            terminalReason);
    }

    private int rowCount(String table) {
        return jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM " + table + " WHERE workspace_id = ?",
            Integer.class,
            drillWorkspace.getId());
    }

    private StoredBinary storeAttachment(Company company) {
        AtomicReference<StoredBinary> stored = new AtomicReference<>();
        ScannedUpload scanned = uploadMalwareScanner.scan(uploadContentInspector.inspect(
            UploadPurpose.ATTACHMENT,
            UploadSource.from("lifecycle.txt", "text/plain", BINARY)));
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.executeWithoutResult(status -> {
            StoredBinary binary = managedObjectService.storeInspectedAttachment(
                drillWorkspace.getId(), scanned);
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
            Path objectPath,
            String chatTitle,
            String chatMessage,
            String toolName,
            String terminalReason) {
    }
}
