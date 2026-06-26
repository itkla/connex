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
        CustomFieldDefinition filled = companyField();
        CustomFieldDefinition empty = companyField();
        valueMapper.upsert(textValue(filled.getId(), ENTITY, "Gold"));

        List<CustomFieldValue> entries = valueMapper.getForEntity(workspace.getId(), "company", ENTITY);

        assertEquals("Gold", entryFor(entries, filled.getId()).getValueText());
        assertNull(entryFor(entries, empty.getId()).getValueText());
    }

    @Test
    void upsert_insertsThenUpdates() {
        CustomFieldDefinition def = companyField();
        valueMapper.upsert(textValue(def.getId(), ENTITY, "A"));
        valueMapper.upsert(textValue(def.getId(), ENTITY, "B"));

        assertEquals("B", entryFor(valueMapper.getForEntity(workspace.getId(), "company", ENTITY), def.getId()).getValueText());
    }

    @Test
    void deleteByDefinitionAndEntity_clearsOne() {
        CustomFieldDefinition def = companyField();
        valueMapper.upsert(textValue(def.getId(), ENTITY, "A"));

        valueMapper.deleteByDefinitionAndEntity(workspace.getId(), def.getId(), ENTITY);

        assertNull(entryFor(valueMapper.getForEntity(workspace.getId(), "company", ENTITY), def.getId()).getValueText());
    }

    @Test
    void deleteByEntity_clearsAll() {
        CustomFieldDefinition def = companyField();
        valueMapper.upsert(textValue(def.getId(), ENTITY, "A"));

        valueMapper.deleteByEntity(workspace.getId(), "company", ENTITY);

        assertNull(entryFor(valueMapper.getForEntity(workspace.getId(), "company", ENTITY), def.getId()).getValueText());
    }

    @Test
    void values_areIsolatedByWorkspace() {
        CustomFieldDefinition def = companyField();
        valueMapper.upsert(textValue(def.getId(), ENTITY, "Gold"));
        Workspace other = newWorkspace();

        List<CustomFieldValue> otherEntries = valueMapper.getForEntity(other.getId(), "company", ENTITY);

        assertTrue(otherEntries.stream().noneMatch(e -> "Gold".equals(e.getValueText())));
    }

    private CustomFieldValue entryFor(List<CustomFieldValue> entries, int definitionId) {
        return entries.stream().filter(e -> e.getDefinitionId() == definitionId).findFirst().orElseThrow();
    }

    private CustomFieldDefinition companyField() {
        CustomFieldDefinition def = new CustomFieldDefinition();
        def.setWorkspaceId(workspace.getId());
        def.setEntityType("company");
        def.setFieldKey("k_" + unique());
        def.setLabel("L");
        def.setFieldType("text");
        definitionMapper.insert(def);
        return def;
    }

    private CustomFieldValue textValue(int definitionId, int entityId, String text) {
        CustomFieldValue value = new CustomFieldValue();
        value.setWorkspaceId(workspace.getId());
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
