package ooo.klae.connex.backend.mappers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import ooo.klae.connex.backend.beans.SavedView;
import ooo.klae.connex.backend.beans.SavedViewDefault;
import ooo.klae.connex.backend.beans.SavedViewPin;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class SavedViewMapperTest extends AbstractMapperTest {

    @Autowired private SavedViewMapper viewMapper;
    @Autowired private SavedViewPreferenceMapper preferenceMapper;
    @Autowired private ObjectMapper objectMapper;

    @Test
    void insertAssignsGeneratedIdAndRoundTripsJsonNode() {
        SavedView view = newView(workspace, newUser(), "company", "My views", "private", 0);

        SavedView found = viewMapper.getAccessibleById(workspace.getId(), view.getUserId(), view.getId());

        assertNotEquals(0, view.getId());
        assertNotNull(found);
        assertEquals(1, found.getConfig().path("version").asInt());
        assertEquals("kept", found.getConfig().path("unknown").path("nested").asString());
    }

    @Test
    void accessibleReadsEnforceOwnerVisibilityAndWorkspace() {
        User owner = newUser();
        User recipient = newUser();
        Workspace other = newWorkspace();
        SavedView ownerPrivate = newView(workspace, owner, "company", "Private", "private", 2);
        SavedView ownerShared = newView(workspace, owner, "company", "Shared", "workspace", 1);
        SavedView recipientPrivate = newView(workspace, recipient, "company", "Mine", "private", 5);
        SavedView elsewhere = newView(other, owner, "company", "Elsewhere", "workspace", 0);

        assertNotNull(viewMapper.getAccessibleById(workspace.getId(), owner.getId(), ownerPrivate.getId()));
        assertNotNull(viewMapper.getAccessibleById(workspace.getId(), recipient.getId(), recipientPrivate.getId()));
        assertNotNull(viewMapper.getAccessibleById(workspace.getId(), recipient.getId(), ownerShared.getId()));
        assertNull(viewMapper.getAccessibleById(workspace.getId(), recipient.getId(), ownerPrivate.getId()));
        assertNull(viewMapper.getAccessibleById(workspace.getId(), recipient.getId(), elsewhere.getId()));

        List<SavedView> visible = viewMapper.getAccessibleByRecordType(
            workspace.getId(), recipient.getId(), "company");
        assertEquals(recipientPrivate.getId(), visible.getFirst().getId());
        assertTrue(visible.stream().anyMatch(view -> view.getId() == ownerShared.getId()));
        assertFalse(visible.stream().anyMatch(view -> view.getId() == ownerPrivate.getId()));
    }

    @Test
    void ownerMutationLookupsAndWritesAreWorkspaceScoped() {
        User owner = newUser();
        User otherUser = newUser();
        SavedView view = newView(workspace, owner, "person", "Contacts", "workspace", 0);

        assertNull(viewMapper.getOwnedByIdForUpdate(workspace.getId(), otherUser.getId(), view.getId()));
        view.setWorkspaceId(newWorkspace().getId());
        view.setName("Blocked");
        assertEquals(0, viewMapper.update(view));
        assertEquals(0, viewMapper.delete(workspace.getId(), otherUser.getId(), view.getId()));

        view.setWorkspaceId(workspace.getId());
        view.setName("Renamed");
        view.setPosition(4);
        assertEquals(1, viewMapper.update(view));
        SavedView updated = viewMapper.getAccessibleById(workspace.getId(), owner.getId(), view.getId());
        assertEquals("Renamed", updated.getName());
        assertEquals(4, updated.getPosition());
    }

    @Test
    void pinPersistenceIsIdempotentByKeyAndOrderedByPositionThenId() {
        User owner = newUser();
        SavedView first = newView(workspace, owner, "company", "First", "private", 0);
        SavedView second = newView(workspace, owner, "deal", "Second", "private", 0);

        preferenceMapper.insertPin(workspace.getId(), owner.getId(), second.getId(), 2);
        preferenceMapper.insertPin(workspace.getId(), owner.getId(), first.getId(), 2);
        assertThrows(DataIntegrityViolationException.class,
            () -> preferenceMapper.insertPin(workspace.getId(), owner.getId(), first.getId(), 0));

        List<SavedView> pins = preferenceMapper.getAccessiblePins(workspace.getId(), owner.getId());
        assertEquals(List.of(first.getId(), second.getId()), pins.stream().map(SavedView::getId).toList());
        assertTrue(pins.getFirst().isPinned());
        assertEquals(2, pins.getFirst().getPinPosition());

        preferenceMapper.updatePinPosition(workspace.getId(), owner.getId(), second.getId(), 1);
        SavedViewPin updated = preferenceMapper.getPin(workspace.getId(), owner.getId(), second.getId());
        assertEquals(1, updated.getPosition());
        preferenceMapper.deletePin(workspace.getId(), owner.getId(), second.getId());
        assertNull(preferenceMapper.getPin(workspace.getId(), owner.getId(), second.getId()));
    }

    @Test
    void defaultUpsertReplacesOnePreferencePerRecordType() {
        User owner = newUser();
        SavedView first = newView(workspace, owner, "company", "First default", "private", 0);
        SavedView second = newView(workspace, owner, "company", "Second default", "private", 0);

        preferenceMapper.upsertDefault(workspace.getId(), owner.getId(), "company", first.getId());
        preferenceMapper.upsertDefault(workspace.getId(), owner.getId(), "company", second.getId());

        SavedViewDefault selected = preferenceMapper.getDefault(workspace.getId(), owner.getId(), "company");
        assertEquals(second.getId(), selected.getSavedViewId());
        SavedView projected = preferenceMapper.getAccessibleDefault(workspace.getId(), owner.getId(), "company");
        assertTrue(projected.isDefaultView());
    }

    @Test
    void preferenceCompositeForeignKeysRejectCrossWorkspaceAndWrongTypeTargets() {
        User owner = newUser();
        Workspace other = newWorkspace();
        SavedView elsewhere = newView(other, owner, "company", "Elsewhere target", "workspace", 0);
        SavedView company = newView(workspace, owner, "company", "Company target", "private", 0);

        assertThrows(DataIntegrityViolationException.class,
            () -> preferenceMapper.insertPin(workspace.getId(), owner.getId(), elsewhere.getId(), 0));
        assertThrows(DataIntegrityViolationException.class,
            () -> preferenceMapper.upsertDefault(workspace.getId(), owner.getId(), "deal", company.getId()));
    }

    @Test
    void deletingViewCascadesPinsAndDefaults() {
        User owner = newUser();
        User recipient = newUser();
        SavedView shared = newView(workspace, owner, "company", "Cascading", "workspace", 0);
        preferenceMapper.insertPin(workspace.getId(), recipient.getId(), shared.getId(), 0);
        preferenceMapper.upsertDefault(workspace.getId(), recipient.getId(), "company", shared.getId());

        assertEquals(1, viewMapper.delete(workspace.getId(), owner.getId(), shared.getId()));

        assertNull(preferenceMapper.getPin(workspace.getId(), recipient.getId(), shared.getId()));
        assertNull(preferenceMapper.getDefault(workspace.getId(), recipient.getId(), "company"));
    }

    private SavedView newView(
            Workspace targetWorkspace, User user, String recordType, String name,
            String visibility, int position) {
        ObjectNode unknown = objectMapper.createObjectNode();
        unknown.put("nested", "kept");
        ObjectNode config = objectMapper.createObjectNode();
        config.put("version", 1);
        config.set("unknown", unknown);
        SavedView view = new SavedView();
        view.setWorkspaceId(targetWorkspace.getId());
        view.setUserId(user.getId());
        view.setRecordType(recordType);
        view.setName(name);
        view.setConfig(config);
        view.setVisibility(visibility);
        view.setPosition(position);
        viewMapper.insert(view);
        return view;
    }

    private Workspace newWorkspace() {
        Workspace created = new Workspace();
        created.setName("WS " + unique());
        created.setSlug("ws-" + unique());
        workspaceMapper.insert(created);
        return created;
    }
}
