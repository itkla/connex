package ooo.klae.connex.backend.mappers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.storage.WorkspaceObjectStorageQuota;

class ObjectStorageQuotaMapperTest extends AbstractMapperTest {
    @Autowired ObjectStorageQuotaMapper quotaMapper;

    @Test
    void usageAndAggregateMutationsStayWorkspaceScoped() {
        Workspace sibling = newWorkspaceInSameOrg();
        String key = "workspaces/" + workspace.getId() + "/attachments/object.pdf";

        quotaMapper.ensureQuota(workspace.getId());
        quotaMapper.ensureQuota(sibling.getId());
        assertEquals(1, quotaMapper.insertUsage(workspace.getId(), key, 123));
        assertEquals(1, quotaMapper.addToQuota(workspace.getId(), 123));

        WorkspaceObjectStorageQuota mine = quotaMapper.lockQuota(workspace.getId());
        WorkspaceObjectStorageQuota theirs = quotaMapper.lockQuota(sibling.getId());
        assertEquals(123, mine.usedBytes());
        assertEquals(1, mine.objectCount());
        assertEquals(0, theirs.usedBytes());
        assertEquals(0, theirs.objectCount());
        assertEquals(123, quotaMapper.lockUsageSize(workspace.getId(), key));
        assertNull(quotaMapper.lockUsageSize(sibling.getId(), key));

        assertEquals(0, quotaMapper.deleteUsage(sibling.getId(), key));
        assertEquals(1, quotaMapper.deleteUsage(workspace.getId(), key));
        assertEquals(1, quotaMapper.subtractFromQuota(workspace.getId(), 123));
        assertEquals(0, quotaMapper.lockQuota(workspace.getId()).usedBytes());
    }

    private Workspace newWorkspaceInSameOrg() {
        Workspace sibling = new Workspace();
        sibling.setName("Quota Sibling");
        sibling.setSlug("quota-sibling-" + unique());
        sibling.setOrgId(workspaceMapper.getOrgId(workspace.getId()));
        workspaceMapper.insert(sibling);
        return sibling;
    }
}
