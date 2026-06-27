package ooo.klae.connex.backend.mappers;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import ooo.klae.connex.backend.beans.CustomFieldDefinition;
import ooo.klae.connex.backend.beans.CustomFieldValue;
import ooo.klae.connex.backend.beans.Workspace;

class CustomFieldValueMapperTest extends AbstractMapperTest {

    @Autowired CustomFieldDefinitionMapper definitionMapper;
    @Autowired CustomFieldValueMapper valueMapper;

    private static final int ENTITY = 100;

    @Test
    void getForEntity_mergesDefinitionsWithValues() {
        CustomFieldDefinition filled = companyField(workspace.getId());
        CustomFieldDefinition empty = companyField(workspace.getId());
        valueMapper.upsert(textValue(workspace.getId(), filled.getId(), ENTITY, "Gold"));

        List<CustomFieldValue> entries = valueMapper.getForEntity(workspace.getId(), "company", ENTITY);

        assertEquals("Gold", entryFor(entries, filled.getId()).getValueText());
        assertNull(entryFor(entries, empty.getId()).getValueText());
    }

    @Test
    void upsert_insertsThenUpdates() {
        CustomFieldDefinition def = companyField(workspace.getId());
        valueMapper.upsert(textValue(workspace.getId(), def.getId(), ENTITY, "A"));
        valueMapper.upsert(textValue(workspace.getId(), def.getId(), ENTITY, "B"));

        assertEquals("B", entryFor(valueMapper.getForEntity(workspace.getId(), "company", ENTITY), def.getId()).getValueText());
    }

    @Test
    void deleteByDefinitionAndEntity_clearsOne() {
        CustomFieldDefinition def = companyField(workspace.getId());
        valueMapper.upsert(textValue(workspace.getId(), def.getId(), ENTITY, "A"));

        valueMapper.deleteByDefinitionAndEntity(workspace.getId(), def.getId(), ENTITY);

        assertNull(entryFor(valueMapper.getForEntity(workspace.getId(), "company", ENTITY), def.getId()).getValueText());
    }

    @Test
    void deleteByEntity_clearsAll() {
        CustomFieldDefinition def = companyField(workspace.getId());
        valueMapper.upsert(textValue(workspace.getId(), def.getId(), ENTITY, "A"));

        valueMapper.deleteByEntity(workspace.getId(), "company", ENTITY);

        assertNull(entryFor(valueMapper.getForEntity(workspace.getId(), "company", ENTITY), def.getId()).getValueText());
    }

    @Test
    void getForEntities_returnsFilledValuesForGivenEntities() {
        CustomFieldDefinition def = companyField(workspace.getId());
        valueMapper.upsert(textValue(workspace.getId(), def.getId(), 100, "Gold"));
        valueMapper.upsert(textValue(workspace.getId(), def.getId(), 200, "Silver"));

        List<CustomFieldValue> rows = valueMapper.getForEntities(workspace.getId(), "company", List.of(100, 200, 300));

        assertEquals(2, rows.size());
        assertTrue(rows.stream().anyMatch(r -> r.getEntityId() == 100 && "Gold".equals(r.getValueText())));
        assertTrue(rows.stream().anyMatch(r -> r.getEntityId() == 200 && "Silver".equals(r.getValueText())));
    }

    @Test
    void getForEntities_isIsolatedByWorkspace() {
        CustomFieldDefinition defA = companyField(workspace.getId());
        valueMapper.upsert(textValue(workspace.getId(), defA.getId(), ENTITY, "Gold"));

        Workspace other = newWorkspace();
        CustomFieldDefinition defB = companyField(other.getId());
        valueMapper.upsert(textValue(other.getId(), defB.getId(), ENTITY, "Blue"));

        List<CustomFieldValue> rows = valueMapper.getForEntities(workspace.getId(), "company", List.of(ENTITY));

        assertTrue(rows.stream().anyMatch(r -> r.getDefinitionId() == defA.getId() && "Gold".equals(r.getValueText())));
        assertTrue(rows.stream().noneMatch(r -> "Blue".equals(r.getValueText())));
    }

    @Test
    void values_areIsolatedByWorkspace() {
        CustomFieldDefinition defA = companyField(workspace.getId());
        valueMapper.upsert(textValue(workspace.getId(), defA.getId(), ENTITY, "Gold"));

        Workspace other = newWorkspace();
        CustomFieldDefinition defB = companyField(other.getId());
        valueMapper.upsert(textValue(other.getId(), defB.getId(), ENTITY, "Blue"));

        List<CustomFieldValue> aEntries = valueMapper.getForEntity(workspace.getId(), "company", ENTITY);
        assertEquals("Gold", entryFor(aEntries, defA.getId()).getValueText());
        assertTrue(aEntries.stream().noneMatch(e -> e.getDefinitionId() == defB.getId()));

        List<CustomFieldValue> bEntries = valueMapper.getForEntity(other.getId(), "company", ENTITY);
        assertEquals("Blue", entryFor(bEntries, defB.getId()).getValueText());
        assertTrue(bEntries.stream().noneMatch(e -> e.getDefinitionId() == defA.getId()));
    }

    private CustomFieldValue entryFor(List<CustomFieldValue> entries, int definitionId) {
        return entries.stream().filter(e -> e.getDefinitionId() == definitionId).findFirst().orElseThrow();
    }

    private CustomFieldDefinition companyField(int workspaceId) {
        CustomFieldDefinition def = new CustomFieldDefinition();
        def.setWorkspaceId(workspaceId);
        def.setEntityType("company");
        def.setFieldKey("k_" + unique());
        def.setLabel("L");
        def.setFieldType("text");
        definitionMapper.insert(def);
        return def;
    }

    private CustomFieldValue textValue(int workspaceId, int definitionId, int entityId, String text) {
        CustomFieldValue value = new CustomFieldValue();
        value.setWorkspaceId(workspaceId);
        value.setDefinitionId(definitionId);
        value.setEntityType("company");
        value.setEntityId(entityId);
        value.setValueText(text);
        return value;
    }

    private Workspace newWorkspace() {
        Workspace ws = new Workspace();
        ws.setName("WS " + unique());
        ws.setSlug("ws_" + unique());
        workspaceMapper.insert(ws);
        return ws;
    }
}
