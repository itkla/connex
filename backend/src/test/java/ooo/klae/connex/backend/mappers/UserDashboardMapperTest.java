package ooo.klae.connex.backend.mappers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.UserDashboard;
import ooo.klae.connex.backend.beans.Workspace;

class UserDashboardMapperTest extends AbstractMapperTest {

    @Autowired UserDashboardMapper dashboardMapper;

    @Test
    void upsert_assignsGeneratedId() {
        UserDashboard dashboard = save(workspace, newUser(), "{\"widgets\":[]}");
        assertNotEquals(0, dashboard.getId());
    }

    @Test
    void getByWorkspaceAndUser_returnsOwnRow() {
        User user = newUser();
        save(workspace, user, "{\"widgets\":[\"pipeline\"]}");
        UserDashboard found = dashboardMapper.getByWorkspaceAndUser(workspace.getId(), user.getId());
        assertNotNull(found);
        assertEquals(user.getId(), found.getUserId());
        assertTrue(found.getLayoutJson().contains("pipeline"));
    }

    @Test
    void getByWorkspaceAndUser_nullWhenAbsent() {
        assertNull(dashboardMapper.getByWorkspaceAndUser(workspace.getId(), newUser().getId()));
    }

    @Test
    void upsert_replacesLayoutOnSameRow() {
        User user = newUser();
        save(workspace, user, "{\"v\":\"a\"}");
        int firstId = dashboardMapper.getByWorkspaceAndUser(workspace.getId(), user.getId()).getId();

        save(workspace, user, "{\"v\":\"b\"}");
        UserDashboard after = dashboardMapper.getByWorkspaceAndUser(workspace.getId(), user.getId());
        assertEquals(firstId, after.getId());
        assertTrue(after.getLayoutJson().contains("\"b\""));
    }

    @Test
    void delete_removesRow() {
        User user = newUser();
        save(workspace, user, "{\"widgets\":[]}");
        dashboardMapper.deleteByWorkspaceAndUser(workspace.getId(), user.getId());
        assertNull(dashboardMapper.getByWorkspaceAndUser(workspace.getId(), user.getId()));
    }

    @Test
    void layouts_areIsolatedByWorkspaceAndUser() {
        User userA = newUser();
        User userB = newUser();
        Workspace other = newWorkspace();

        save(workspace, userA, "{\"who\":\"a-here\"}");
        save(workspace, userB, "{\"who\":\"b-here\"}");
        save(other, userA, "{\"who\":\"a-elsewhere\"}");

        assertTrue(dashboardMapper.getByWorkspaceAndUser(workspace.getId(), userA.getId())
            .getLayoutJson().contains("a-here"));
        assertTrue(dashboardMapper.getByWorkspaceAndUser(workspace.getId(), userB.getId())
            .getLayoutJson().contains("b-here"));
        assertTrue(dashboardMapper.getByWorkspaceAndUser(other.getId(), userA.getId())
            .getLayoutJson().contains("a-elsewhere"));
    }

    private UserDashboard save(Workspace ws, User user, String layoutJson) {
        UserDashboard dashboard = new UserDashboard();
        dashboard.setWorkspaceId(ws.getId());
        dashboard.setUserId(user.getId());
        dashboard.setLayoutJson(layoutJson);
        dashboardMapper.upsert(dashboard);
        return dashboard;
    }

    private Workspace newWorkspace() {
        Workspace ws = new Workspace();
        ws.setName("WS " + unique());
        ws.setSlug("ws_" + unique());
        workspaceMapper.insert(ws);
        return ws;
    }
}
