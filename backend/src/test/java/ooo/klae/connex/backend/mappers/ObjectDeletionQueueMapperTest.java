package ooo.klae.connex.backend.mappers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.storage.ObjectDeletionTask;

class ObjectDeletionQueueMapperTest extends AbstractMapperTest {
    @Autowired ObjectDeletionQueueMapper objectDeletionQueueMapper;
    @Autowired UserObjectDeletionQueueMapper userObjectDeletionQueueMapper;

    @Test
    void tenantQueueIsWorkspaceScopedAndIdempotent() {
        LocalDateTime now = LocalDateTime.of(2026, 7, 14, 12, 0);
        Workspace sibling = newWorkspaceInSameOrg();
        String key = "workspaces/" + workspace.getId() + "/attachments/object.pdf";
        String siblingKey = "workspaces/" + sibling.getId() + "/attachments/object.pdf";

        objectDeletionQueueMapper.enqueue(workspace.getId(), key, 1, now);
        objectDeletionQueueMapper.enqueue(workspace.getId(), key, 1, now.plusMinutes(1));
        objectDeletionQueueMapper.enqueue(sibling.getId(), siblingKey, 1, now);

        List<ObjectDeletionTask> mine = objectDeletionQueueMapper.findDue(
            workspace.getId(), now.plusSeconds(1), 10);
        assertEquals(1, mine.size());
        assertEquals(key, mine.getFirst().objectKey());
        assertEquals(2, mine.getFirst().attempts());
        assertEquals(1, mine.getFirst().deletePassesRemaining());
        assertEquals(1, objectDeletionQueueMapper.countPending(workspace.getId()));
        assertEquals(0, objectDeletionQueueMapper.deleteById(
            sibling.getId(), mine.getFirst().id()));
        assertEquals(1, objectDeletionQueueMapper.countPending(workspace.getId()));
        assertTrue(objectDeletionQueueMapper.workspaceIdsWithDueTasks(
            now.plusSeconds(1), 10).containsAll(List.of(workspace.getId(), sibling.getId())));
    }

    @Test
    void controlQueueStoresUserKeysOutsideTenantQueue() {
        LocalDateTime now = LocalDateTime.of(2026, 7, 14, 12, 0);
        String key = "users/7/profile-images/object.png";

        userObjectDeletionQueueMapper.enqueue(key, 1, now);

        List<ObjectDeletionTask> due = userObjectDeletionQueueMapper.findDue(
            now.plusSeconds(1), 10);
        assertEquals(1, due.size());
        assertEquals(0, due.getFirst().workspaceId());
        assertEquals(key, due.getFirst().objectKey());
        assertEquals(1, due.getFirst().deletePassesRemaining());
        assertEquals(1, userObjectDeletionQueueMapper.deleteById(due.getFirst().id()));
        assertEquals(0, userObjectDeletionQueueMapper.countPending());
    }

    @Test
    void ambiguousTombstoneDefersAndRequiresTwoDeletePasses() {
        LocalDateTime now = LocalDateTime.of(2026, 7, 14, 12, 0);
        LocalDateTime delayed = now.plusMinutes(1);
        LocalDateTime recheck = now.plusMinutes(2);
        String key = "workspaces/" + workspace.getId() + "/attachments/ambiguous.pdf";

        objectDeletionQueueMapper.enqueue(workspace.getId(), key, 1, now);
        objectDeletionQueueMapper.enqueue(workspace.getId(), key, 2, delayed);

        assertTrue(objectDeletionQueueMapper.findDue(
            workspace.getId(), now.plusSeconds(1), 10).isEmpty());
        ObjectDeletionTask task = objectDeletionQueueMapper.findDue(
            workspace.getId(), delayed.plusSeconds(1), 10).getFirst();
        assertEquals(2, task.deletePassesRemaining());
        assertEquals(1, objectDeletionQueueMapper.confirmDeletePass(
            workspace.getId(), task.id(), recheck));
        assertTrue(objectDeletionQueueMapper.findDue(
            workspace.getId(), delayed.plusSeconds(1), 10).isEmpty());
        assertEquals(1, objectDeletionQueueMapper.findDue(
            workspace.getId(), recheck.plusSeconds(1), 10).getFirst().deletePassesRemaining());
    }

    private Workspace newWorkspaceInSameOrg() {
        Workspace sibling = new Workspace();
        sibling.setName("Queue Sibling");
        sibling.setSlug("queue-sibling-" + unique());
        sibling.setOrgId(workspaceMapper.getOrgId(workspace.getId()));
        workspaceMapper.insert(sibling);
        return sibling;
    }
}
