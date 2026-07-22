package ooo.klae.connex.backend.mappers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;

class WorkspaceMapperTest extends AbstractMapperTest {

    @Test
    void membershipQueriesAreWorkspaceSpecific() {
        User member = newUser();
        Workspace other = new Workspace();
        other.setName("Other Workspace");
        other.setSlug("other-" + unique());
        workspaceMapper.insert(other);

        assertTrue(workspaceMapper.isMember(workspace.getId(), member.getId()));
        assertFalse(workspaceMapper.isMember(other.getId(), member.getId()));
        assertEquals(1, workspaceMapper.getWorkspacesForUser(member.getId()).size());
    }

    @Test
    void getMembersHydratesLocale() {
        User member = newUser();
        userMapper.updateLocale(member.getId(), "ja");

        User hydrated = workspaceMapper.getMembers(workspace.getId()).stream()
                .filter(user -> user.getId() == member.getId())
                .findFirst()
                .orElseThrow();

        assertEquals("ja", hydrated.getLocale());
    }

    @Test
    void countActiveMembersChecksOnlySelectedWorkspaceMemberships() {
        User active = newUser();
        User pending = insertUser();
        workspaceMapper.addPendingMember(workspace.getId(), pending.getId(), "member");
        User foreign = insertUser();
        Workspace other = new Workspace();
        other.setName("Foreign Workspace");
        other.setSlug("foreign-" + unique());
        workspaceMapper.insert(other);
        workspaceMapper.addMember(other.getId(), foreign.getId(), "member");

        assertEquals(1, workspaceMapper.countActiveMembers(
            workspace.getId(), List.of(active.getId(), pending.getId(), foreign.getId())));
    }

    private User insertUser() {
        String suffix = unique();
        User user = new User();
        user.setUsername("workspace_" + suffix);
        user.setDisplayName("Workspace " + suffix);
        user.setEmail(suffix + "@workspace.example");
        user.setPasswordHash("hash_" + suffix);
        user.setTimezone("UTC");
        userMapper.insert(user);
        return user;
    }
}
