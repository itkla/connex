package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import ooo.klae.connex.backend.beans.Task;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.dto.MemberDto;
import ooo.klae.connex.backend.dto.WorkspaceMembershipDto;
import ooo.klae.connex.backend.exceptions.BadRequestException;

class MemberManagementTest extends AbstractServiceTest {

    @Autowired WorkspaceService workspaceService;

    @Test
    void listMembers_includesRoles() {
        WorkspaceMembershipDto ws = workspaceService.createWorkspace("Roster WS", currentUser.getId());
        User member = newUser();
        workspaceMapper.addMember(ws.getId(), member.getId(), "member");

        List<MemberDto> members = workspaceService.getMembersWithRoles(ws.getId(), currentUser.getId());

        assertEquals(2, members.size());
        assertEquals("owner", members.getFirst().getRole());
    }

    @Test
    void changeRole_promotesMember() {
        WorkspaceMembershipDto ws = workspaceService.createWorkspace("Promote WS", currentUser.getId());
        User member = newUser();
        workspaceMapper.addMember(ws.getId(), member.getId(), "member");

        MemberDto updated = workspaceService.changeMemberRole(ws.getId(), currentUser.getId(), member.getId(), "admin");

        assertEquals("admin", updated.getRole());
    }

    @Test
    void changeRole_cannotDemoteLastOwner() {
        WorkspaceMembershipDto ws = workspaceService.createWorkspace("Owner WS", currentUser.getId());

        assertThrows(BadRequestException.class,
            () -> workspaceService.changeMemberRole(ws.getId(), currentUser.getId(), currentUser.getId(), "member"));
    }

    @Test
    void removeMember_unassignsTasksAndPreservesThem() {
        WorkspaceMembershipDto ws = workspaceService.createWorkspace("Remove WS", currentUser.getId());
        User member = newUser();
        workspaceMapper.addMember(ws.getId(), member.getId(), "member");

        Task task = new Task();
        task.setWorkspaceId(ws.getId());
        task.setDescription("task_" + unique());
        task.setCompleted(false);
        task.setStatus("todo");
        task.setDueDate("2024-12-31");
        task.setAssignedTo(member);
        taskMapper.insert(task);

        workspaceService.removeMember(ws.getId(), currentUser.getId(), member.getId());

        assertFalse(workspaceMapper.isMember(ws.getId(), member.getId()));
        assertNotNull(taskMapper.getTaskById(ws.getId(), task.getId()));
        assertTrue(taskMapper.getTasksByAssignedToId(ws.getId(), member.getId()).isEmpty());
    }

    @Test
    void removeMember_cannotRemoveLastOwner() {
        WorkspaceMembershipDto ws = workspaceService.createWorkspace("Solo Owner WS", currentUser.getId());

        assertThrows(BadRequestException.class,
            () -> workspaceService.removeMember(ws.getId(), currentUser.getId(), currentUser.getId()));
    }
}
