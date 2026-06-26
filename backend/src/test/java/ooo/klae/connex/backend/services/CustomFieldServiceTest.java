package ooo.klae.connex.backend.services;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import ooo.klae.connex.backend.beans.CustomFieldDefinition;
import ooo.klae.connex.backend.dto.CustomFieldOption;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.DuplicateResourceException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;

class CustomFieldServiceTest extends AbstractServiceTest {

    @Autowired CustomFieldDefinitionService service;

    @Test
    void create_persistsAndAssignsId() {
        CustomFieldDefinition created = service.create(def("company", "text", "industry_tier"), null);

        assertNotEquals(0, created.getId());
        assertEquals("industry_tier", service.getById(created.getId()).getFieldKey());
    }

    @Test
    void create_duplicateKeyInWorkspace_throws() {
        service.create(def("company", "text", "tier"), null);

        assertThrows(DuplicateResourceException.class,
            () -> service.create(def("company", "number", "tier"), null));
    }

    @Test
    void create_sameKeyDifferentEntityType_allowed() {
        service.create(def("company", "text", "tier"), null);

        CustomFieldDefinition onDeal = service.create(def("deal", "text", "tier"), null);

        assertNotEquals(0, onDeal.getId());
    }

    @Test
    void create_selectWithoutOptions_throws() {
        assertThrows(BadRequestException.class,
            () -> service.create(def("deal", "select", "stage_band"), null));
    }

    @Test
    void create_nonSelectWithOptions_throws() {
        assertThrows(BadRequestException.class,
            () -> service.create(def("deal", "text", "note"), List.of(new CustomFieldOption("a", "A"))));
    }

    @Test
    void create_unsupportedType_throws() {
        assertThrows(BadRequestException.class,
            () -> service.create(def("company", "rating", "score"), null));
    }

    @Test
    void create_selectWithOptions_persistsOptionsJson() {
        CustomFieldDefinition created = service.create(def("deal", "select", "band"),
            List.of(new CustomFieldOption("hot", "Hot"), new CustomFieldOption("cold", "Cold")));

        assertNotNull(created.getOptionsJson());
        assertTrue(created.getOptionsJson().contains("hot"));
    }

    @Test
    void getById_missing_throws() {
        assertThrows(ResourceNotFoundException.class, () -> service.getById(-1));
    }

    @Test
    void update_changesLabel_keepsKey() {
        CustomFieldDefinition created = service.create(def("person", "text", "nickname"), null);
        CustomFieldDefinition edit = def("person", "text", "nickname");
        edit.setLabel("Preferred name");

        CustomFieldDefinition updated = service.update(created.getId(), edit, null);

        assertEquals("Preferred name", updated.getLabel());
        assertEquals("nickname", updated.getFieldKey());
    }

    private CustomFieldDefinition def(String entityType, String fieldType, String key) {
        CustomFieldDefinition d = new CustomFieldDefinition();
        d.setEntityType(entityType);
        d.setFieldType(fieldType);
        d.setFieldKey(key);
        d.setLabel("Label");
        return d;
    }
}
