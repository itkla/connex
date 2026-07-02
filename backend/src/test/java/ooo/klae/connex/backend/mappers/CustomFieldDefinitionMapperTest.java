package ooo.klae.connex.backend.mappers;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import ooo.klae.connex.backend.beans.CustomFieldDefinition;
import ooo.klae.connex.backend.beans.Workspace;

class CustomFieldDefinitionMapperTest extends AbstractMapperTest {

    @Autowired CustomFieldDefinitionMapper definitionMapper;

    @Test
    void insert_assignsGeneratedId() {
        CustomFieldDefinition def = newDefinition();
        assertNotEquals(0, def.getId());
    }

    @Test
    void getById_returnsInsertedRow() {
        CustomFieldDefinition def = newDefinition();

        CustomFieldDefinition found = definitionMapper.getById(workspace.getId(), def.getId());

        assertNotNull(found);
        assertEquals(workspace.getId(), found.getWorkspaceId());
        assertEquals(def.getFieldKey(), found.getFieldKey());
        assertEquals("company", found.getEntityType());
        assertEquals("text", found.getFieldType());
    }

    @Test
    void getByKey_returnsRow() {
        CustomFieldDefinition def = newDefinition();

        CustomFieldDefinition found = definitionMapper.getByKey(workspace.getId(), "company", def.getFieldKey());

        assertNotNull(found);
        assertEquals(def.getId(), found.getId());
    }

    @Test
    void getByEntityType_filtersByType() {
        CustomFieldDefinition company = newDefinition();
        CustomFieldDefinition deal = new CustomFieldDefinition();
        deal.setWorkspaceId(workspace.getId());
        deal.setEntityType("deal");
        deal.setFieldKey("key_" + unique());
        deal.setLabel("Deal field");
        deal.setFieldType("number");
        definitionMapper.insert(deal);

        List<CustomFieldDefinition> companyFields = definitionMapper.getByEntityType(workspace.getId(), "company");

        assertTrue(companyFields.stream().anyMatch(d -> d.getId() == company.getId()));
        assertTrue(companyFields.stream().noneMatch(d -> d.getId() == deal.getId()));
    }

    @Test
    void update_persistsNewValues() {
        CustomFieldDefinition def = newDefinition();
        def.setLabel("Renamed");
        def.setFieldType("textarea");
        def.setRequired(true);
        def.setPosition(3);

        definitionMapper.update(def);

        CustomFieldDefinition found = definitionMapper.getById(workspace.getId(), def.getId());
        assertEquals("Renamed", found.getLabel());
        assertEquals("textarea", found.getFieldType());
        assertTrue(found.isRequired());
        assertEquals(3, found.getPosition());
    }

    @Test
    void delete_removesRow() {
        CustomFieldDefinition def = newDefinition();

        definitionMapper.delete(workspace.getId(), def.getId());

        assertNull(definitionMapper.getById(workspace.getId(), def.getId()));
    }

    /**
     * A definition in another workspace is invisible and immutable from this workspace.
     */
    @Test
    void definitions_areIsolatedByWorkspace() {
        CustomFieldDefinition mine = newDefinition();
        Workspace other = newWorkspace();
        CustomFieldDefinition foreign = newDefinitionIn(other);

        assertNull(definitionMapper.getById(workspace.getId(), foreign.getId()));
        assertFalse(definitionMapper.exists(workspace.getId(), foreign.getId()));
        assertTrue(definitionMapper.getAll(workspace.getId()).stream().noneMatch(d -> d.getId() == foreign.getId()));
        assertTrue(definitionMapper.getAll(workspace.getId()).stream().anyMatch(d -> d.getId() == mine.getId()));

        assertEquals(0, definitionMapper.delete(workspace.getId(), foreign.getId()));
        assertTrue(definitionMapper.exists(other.getId(), foreign.getId()));
    }

    /**
     * The same (entity type, field key) can exist in two workspaces — uniqueness is per-tenant.
     */
    @Test
    void sameFieldKey_allowedInDifferentWorkspaces() {
        CustomFieldDefinition mine = newDefinition();
        Workspace other = newWorkspace();

        CustomFieldDefinition clone = new CustomFieldDefinition();
        clone.setWorkspaceId(other.getId());
        clone.setEntityType(mine.getEntityType());
        clone.setFieldKey(mine.getFieldKey());
        clone.setLabel("Clone");
        clone.setFieldType("text");
        definitionMapper.insert(clone);

        assertNotEquals(0, clone.getId());
        assertNotEquals(mine.getId(), clone.getId());
    }

    @Test
    void optionsJson_persistsAndReads() {
        CustomFieldDefinition def = new CustomFieldDefinition();
        def.setWorkspaceId(workspace.getId());
        def.setEntityType("deal");
        def.setFieldKey("key_" + unique());
        def.setLabel("Band");
        def.setFieldType("select");
        def.setOptionsJson("[{\"key\":\"hot\",\"label\":\"Hot\"}]");
        definitionMapper.insert(def);

        CustomFieldDefinition found = definitionMapper.getById(workspace.getId(), def.getId());

        assertNotNull(found.getOptionsJson());
        assertTrue(found.getOptionsJson().contains("hot"));
        assertTrue(found.getOptionsJson().contains("Hot"));
    }

    @Test
    void dataClassification_defaultsToStandardThenPersistsUpdate() {
        CustomFieldDefinition def = newDefinition();
        assertEquals("standard", definitionMapper.getById(workspace.getId(), def.getId()).getDataClassification());

        def.setDataClassification("special_care");
        definitionMapper.update(def);

        assertEquals("special_care",
            definitionMapper.getById(workspace.getId(), def.getId()).getDataClassification());
    }

    private CustomFieldDefinition newDefinition() {
        return newDefinitionIn(workspace);
    }

    private CustomFieldDefinition newDefinitionIn(Workspace ws) {
        CustomFieldDefinition def = new CustomFieldDefinition();
        def.setWorkspaceId(ws.getId());
        def.setEntityType("company");
        def.setFieldKey("key_" + unique());
        def.setLabel("Label " + unique());
        def.setFieldType("text");
        definitionMapper.insert(def);
        return def;
    }

    private Workspace newWorkspace() {
        Workspace ws = new Workspace();
        ws.setName("WS " + unique());
        ws.setSlug("ws_" + unique());
        workspaceMapper.insert(ws);
        return ws;
    }
}
