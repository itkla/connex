package ooo.klae.connex.backend.services;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import ooo.klae.connex.backend.beans.CustomFieldDefinition;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.dto.CustomFieldOption;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.DuplicateResourceException;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
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

    @Test
    void update_keepsEntityTypeKeyAndFieldType() {
        CustomFieldDefinition created = service.create(def("person", "text", "nickname"), null);
        CustomFieldDefinition edit = def("deal", "number", "hacked");
        edit.setLabel("Renamed");

        CustomFieldDefinition updated = service.update(created.getId(), edit, null);

        assertEquals("person", updated.getEntityType());
        assertEquals("nickname", updated.getFieldKey());
        assertEquals("text", updated.getFieldType());
        assertEquals("Renamed", updated.getLabel());
        assertEquals("person", service.getById(created.getId()).getEntityType());
    }

    @Test
    void selectOptions_roundTripThroughParse() {
        CustomFieldDefinition created = service.create(def("deal", "select", "band"),
            List.of(new CustomFieldOption("hot", "Hot"), new CustomFieldOption("cold", "Cold")));

        List<CustomFieldOption> parsed = service.parseOptions(created.getOptionsJson());

        assertNotNull(parsed);
        assertEquals(2, parsed.size());
        assertEquals("hot", parsed.get(0).getKey());
        assertEquals("Hot", parsed.get(0).getLabel());
    }

    @Test
    void parseOptions_handlesNullBlankAndMalformed() {
        assertNull(service.parseOptions(null));
        assertNull(service.parseOptions("   "));
        assertNull(service.parseOptions("{ not json"));
    }

    @Test
    void create_duplicateOptionKeys_throws() {
        assertThrows(BadRequestException.class, () -> service.create(def("deal", "select", "band"),
            List.of(new CustomFieldOption("x", "X"), new CustomFieldOption("x", "Y"))));
    }

    @Test
    void create_blankOptionKeyOrLabel_throws() {
        assertThrows(BadRequestException.class, () -> service.create(def("deal", "select", "b1"),
            List.of(new CustomFieldOption(" ", "X"))));
        assertThrows(BadRequestException.class, () -> service.create(def("deal", "select", "b2"),
            List.of(new CustomFieldOption("x", " "))));
    }

    @Test
    void manage_asMember_isForbidden() {
        User member = newUser();
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(member, null, member.getAuthorities()));

        assertThrows(ForbiddenException.class, () -> service.create(def("company", "text", "blocked"), null));
        assertThrows(ForbiddenException.class, () -> service.getAll());
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
