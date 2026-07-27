package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import ooo.klae.connex.backend.beans.Organization;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.exceptions.ServiceUnavailableException;
import ooo.klae.connex.backend.mappers.OrganizationMapper;
import ooo.klae.connex.backend.storage.ObjectStorage;
import ooo.klae.connex.backend.storage.ObjectStorageException;

@SpringBootTest(properties = {
    "connex.tenant-lifecycle.teardown-settle-delay=0s",
    "connex.object-storage.delete-retry-delay-ms=1000"
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class TenantTeardownStorageResumeIntegrationTest extends AbstractServiceTest {
    @Autowired private OrganizationMapper organizationMapper;
    @Autowired private OrgMemberService orgMemberService;
    @Autowired private TenantTeardownService teardownService;
    @Autowired private JdbcTemplate jdbcTemplate;
    @MockitoBean private ObjectStorage objectStorage;

    private Organization organization;
    private Workspace lifecycleWorkspace;

    @AfterEach
    void cleanCommittedRoots() {
        if (lifecycleWorkspace != null) {
            int workspaceId = lifecycleWorkspace.getId();
            jdbcTemplate.update(
                "DELETE FROM object_deletion_queue WHERE workspace_id = ?",
                workspaceId);
            jdbcTemplate.update(
                "DELETE FROM managed_object_usage WHERE workspace_id = ?",
                workspaceId);
            jdbcTemplate.update(
                "DELETE FROM object_storage_quota WHERE workspace_id = ?",
                workspaceId);
            jdbcTemplate.update(
                "DELETE FROM attachment WHERE workspace_id = ?",
                workspaceId);
            jdbcTemplate.update(
                "DELETE FROM tenant_operation_lease WHERE workspace_id = ?",
                workspaceId);
            jdbcTemplate.update("DELETE FROM workspace WHERE id = ?", workspaceId);
        }
        if (organization != null) {
            jdbcTemplate.update(
                "DELETE FROM organization WHERE id = ?",
                organization.getId());
        }
        if (currentUser != null) {
            jdbcTemplate.update(
                "DELETE FROM app_user WHERE id = ?",
                currentUser.getId());
        }
    }

    @Test
    void storageProviderFailureLeavesDurableQueueAndRetryCompletesTeardown()
            throws Exception {
        createRoots();
        String objectKey = seedManagedAttachment();
        doThrow(new ObjectStorageException("provider unavailable"))
            .doNothing()
            .when(objectStorage)
            .delete(objectKey);

        assertThrows(
            ServiceUnavailableException.class,
            () -> teardownService.teardownWorkspace(
                organization.getId(),
                lifecycleWorkspace.getId(),
                currentUser.getId(),
                lifecycleWorkspace.getSlug()));

        assertEquals("tearing_down", jdbcTemplate.queryForObject(
            "SELECT lifecycle_state FROM workspace WHERE id = ?",
            String.class,
            lifecycleWorkspace.getId()));
        assertEquals(1, jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM object_deletion_queue WHERE workspace_id = ?",
            Integer.class,
            lifecycleWorkspace.getId()));

        Thread.sleep(1_100);
        teardownService.teardownWorkspace(
            organization.getId(),
            lifecycleWorkspace.getId(),
            currentUser.getId(),
            lifecycleWorkspace.getSlug());

        assertEquals(0, jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM workspace WHERE id = ?",
            Integer.class,
            lifecycleWorkspace.getId()));
        assertEquals(0, jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM object_deletion_queue WHERE workspace_id = ?",
            Integer.class,
            lifecycleWorkspace.getId()));
        verify(objectStorage, times(2)).delete(objectKey);
    }

    private void createRoots() {
        organization = new Organization();
        organization.setName("Lifecycle retry " + unique());
        organization.setSlug("lifecycle-retry-" + unique());
        organizationMapper.insert(organization);
        orgMemberService.addFoundingOwner(
            organization.getId(),
            currentUser.getId());
        lifecycleWorkspace = new Workspace();
        lifecycleWorkspace.setOrgId(organization.getId());
        lifecycleWorkspace.setName("Lifecycle retry " + unique());
        lifecycleWorkspace.setSlug("lifecycle-retry-" + unique());
        workspaceMapper.insert(lifecycleWorkspace);
        workspaceMapper.addMember(
            lifecycleWorkspace.getId(),
            currentUser.getId(),
            "owner");
        workspace = lifecycleWorkspace;
        authenticateAs(currentUser, lifecycleWorkspace.getId());
    }

    private String seedManagedAttachment() {
        int workspaceId = lifecycleWorkspace.getId();
        String token = UUID.randomUUID() + ".txt";
        String objectKey = "workspaces/" + workspaceId + "/attachments/" + token;
        jdbcTemplate.update(
            "INSERT INTO managed_object_usage (workspace_id, object_key, size_bytes)"
                + " VALUES (?, ?, 1)",
            workspaceId,
            objectKey);
        jdbcTemplate.update(
            "INSERT INTO object_storage_quota (workspace_id, used_bytes, object_count)"
                + " VALUES (?, 1, 1)",
            workspaceId);
        jdbcTemplate.update(
            "INSERT INTO attachment"
                + " (workspace_id, entity_type, entity_id, file_name, url, content_type, size)"
                + " VALUES (?, 'workspace', ?, 'retry.txt', ?, 'text/plain', 1)",
            workspaceId,
            workspaceId,
            "/api/attachments/content/" + token);
        return objectKey;
    }
}
