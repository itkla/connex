package ooo.klae.connex.backend.mappers;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import ooo.klae.connex.backend.beans.SavedView;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;

class SavedViewMapperTest extends AbstractMapperTest {

    @Autowired SavedViewMapper viewMapper;

    @Test
    void insert_assignsGeneratedId() {
        SavedView view = newView(workspace, newUser(), "company", "My views");
        assertNotEquals(0, view.getId());
    }

    @Test
    void getById_returnsRowForOwner() {
        User user = newUser();
        SavedView view = newView(workspace, user, "company", "Hot");
        SavedView found = viewMapper.getById(workspace.getId(), user.getId(), view.getId());
        assertNotNull(found);
        assertEquals(user.getId(), found.getUserId());
        assertEquals("company", found.getRecordType());
        assertEquals("{}", found.getConfigJson());
    }

    @Test
    void getByRecordType_filtersByType() {
        User user = newUser();
        newView(workspace, user, "company", "co");
        newView(workspace, user, "deal", "dl");

        List<SavedView> companyViews = viewMapper.getByRecordType(workspace.getId(), user.getId(), "company");
        assertTrue(companyViews.stream().allMatch(v -> "company".equals(v.getRecordType())));
        assertTrue(companyViews.stream().anyMatch(v -> "co".equals(v.getName())));
        assertTrue(companyViews.stream().noneMatch(v -> "dl".equals(v.getName())));
    }

    @Test
    void getByName_findsExact() {
        User user = newUser();
        SavedView view = newView(workspace, user, "person", "Champions");
        SavedView found = viewMapper.getByName(workspace.getId(), user.getId(), "person", "Champions");
        assertNotNull(found);
        assertEquals(view.getId(), found.getId());
        assertNull(viewMapper.getByName(workspace.getId(), user.getId(), "person", "Nope"));
    }

    @Test
    void update_persistsNewValues() {
        User user = newUser();
        SavedView view = newView(workspace, user, "person", "Contacts");
        view.setName("Renamed");
        view.setConfigJson("{\"query\":\"x\"}");
        view.setPosition(5);
        viewMapper.update(view);

        SavedView found = viewMapper.getById(workspace.getId(), user.getId(), view.getId());
        assertEquals("Renamed", found.getName());
        assertEquals(5, found.getPosition());
        assertTrue(found.getConfigJson().contains("\"query\""));
    }

    @Test
    void delete_removesRow() {
        User user = newUser();
        SavedView view = newView(workspace, user, "deal", "My deals");
        viewMapper.delete(workspace.getId(), user.getId(), view.getId());
        assertNull(viewMapper.getById(workspace.getId(), user.getId(), view.getId()));
    }

    @Test
    void views_areIsolatedByWorkspaceAndUser() {
        User userA = newUser();
        User userB = newUser();
        Workspace other = newWorkspace();

        SavedView mine = newView(workspace, userA, "company", "tier-a");
        SavedView theirs = newView(workspace, userB, "company", "tier-b");
        SavedView elsewhere = newView(other, userA, "company", "tier-c");

        assertNotNull(viewMapper.getById(workspace.getId(), userA.getId(), mine.getId()));
        assertNull(viewMapper.getById(workspace.getId(), userA.getId(), theirs.getId()));
        assertNull(viewMapper.getById(workspace.getId(), userA.getId(), elsewhere.getId()));
        assertTrue(viewMapper.getByUser(workspace.getId(), userA.getId()).stream().noneMatch(v -> v.getId() == theirs.getId()));
    }

    private SavedView newView(Workspace ws, User user, String recordType, String name) {
        SavedView view = new SavedView();
        view.setWorkspaceId(ws.getId());
        view.setUserId(user.getId());
        view.setRecordType(recordType);
        view.setName(name);
        view.setConfigJson("{}");
        view.setPosition(0);
        viewMapper.insert(view);
        return view;
    }

    private Workspace newWorkspace() {
        Workspace ws = new Workspace();
        ws.setName("WS " + unique());
        ws.setSlug("ws_" + unique());
        workspaceMapper.insert(ws);
        return ws;
    }
}
