package ooo.klae.connex.backend.services;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.dto.CustomFieldOption;
import ooo.klae.connex.backend.dto.CustomFieldSchemaDto;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.DuplicateResourceException;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.RecordCreationTemplateMapper;

class CustomFieldServiceTest extends AbstractServiceTest {

    @Autowired CustomFieldDefinitionService service;
    @Autowired RecordCreationTemplateMapper templateMapper;

    @Test
    void schemaMutationsAdvanceOnlyTheAffectedTemplateSetRevision() {
        CustomFieldDefinition created = service.create(def("person", "text", "revision_probe"), null);

        assertEquals(1, templateMapper.getSet(workspace.getId(), "person").getRevision());
        assertNull(templateMapper.getSet(workspace.getId(), "company"));
        assertNull(templateMapper.getSet(workspace.getId(), "deal"));

        CustomFieldDefinition edit = def("person", "text", "revision_probe");
        edit.setLabel("Updated label");
        service.update(created.getId(), edit, null);

        assertEquals(2, templateMapper.getSet(workspace.getId(), "person").getRevision());
        assertNull(templateMapper.getSet(workspace.getId(), "company"));
        assertNull(templateMapper.getSet(workspace.getId(), "deal"));

        service.delete(created.getId());

        assertEquals(3, templateMapper.getSet(workspace.getId(), "person").getRevision());
        assertNull(templateMapper.getSet(workspace.getId(), "company"));
        assertNull(templateMapper.getSet(workspace.getId(), "deal"));
    }

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
    void create_defaultsClassificationToStandard() {
        CustomFieldDefinition created = service.create(def("person", "text", "hobby"), null);

        assertEquals("standard", service.getById(created.getId()).getDataClassification());
    }

    @Test
    void create_acceptsSpecialCareClassification() {
        CustomFieldDefinition def = def("person", "text", "health_note");
        def.setDataClassification("special_care");

        CustomFieldDefinition created = service.create(def, null);

        assertEquals("special_care", service.getById(created.getId()).getDataClassification());
    }

    @Test
    void create_unsupportedClassification_throws() {
        CustomFieldDefinition def = def("person", "text", "bogus");
        def.setDataClassification("top_secret");

        assertThrows(BadRequestException.class, () -> service.create(def, null));
    }

    @Test
    void update_canReclassifyExistingField() {
        CustomFieldDefinition created = service.create(def("person", "text", "notes_field"), null);
        CustomFieldDefinition edit = def("person", "text", "notes_field");
        edit.setDataClassification("special_care");

        CustomFieldDefinition updated = service.update(created.getId(), edit, null);

        assertEquals("special_care", updated.getDataClassification());
        assertEquals("special_care", service.getById(created.getId()).getDataClassification());
    }

    @Test
    void update_omittingClassification_preservesSpecialCare() {
        CustomFieldDefinition def = def("person", "text", "diagnosis");
        def.setDataClassification("special_care");
        CustomFieldDefinition created = service.create(def, null);

        CustomFieldDefinition edit = def("person", "text", "diagnosis");
        edit.setLabel("Diagnosis notes");

        service.update(created.getId(), edit, null);

        assertEquals("special_care", service.getById(created.getId()).getDataClassification());
    }

    @Test
    void update_explicitStandard_downgradesFromSpecialCare() {
        CustomFieldDefinition def = def("person", "text", "sensitive_note");
        def.setDataClassification("special_care");
        CustomFieldDefinition created = service.create(def, null);

        CustomFieldDefinition edit = def("person", "text", "sensitive_note");
        edit.setDataClassification("standard");
        service.update(created.getId(), edit, null);

        assertEquals("standard", service.getById(created.getId()).getDataClassification());
    }

    @Test
    void manage_asMember_isForbidden() {
        User member = newUser();
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(member, null, member.getAuthorities()));

        assertThrows(ForbiddenException.class, () -> service.create(def("company", "text", "blocked"), null));
    }

    @Test
    void catalog_asMember_staysForbidden() {
        CustomFieldDefinition created = service.create(def("person", "text", "admin_only"), null);
        User member = newUser();
        authenticateAs(member, workspace.getId());

        assertEquals("member", tenantContext.getRole());
        assertThrows(ForbiddenException.class, () -> service.getAll());
        assertThrows(ForbiddenException.class, () -> service.getByEntityType("person"));
        assertThrows(ForbiddenException.class, () -> service.getById(created.getId()));
    }

    @Test
    void visibleSchema_asMember_returnsTheFieldsThatDrawColumns() {
        CustomFieldDefinition text = service.create(def("person", "text", "member_visible"), null);
        CustomFieldDefinition select = service.create(
            def("person", "select", "member_choice"),
            List.of(new CustomFieldOption("a", "A"), new CustomFieldOption("b", "B")));
        User member = newUser();
        authenticateAs(member, workspace.getId());

        assertEquals("member", tenantContext.getRole());
        List<CustomFieldSchemaDto> schema = service.getVisibleSchema("person");

        CustomFieldSchemaDto textColumn = schema.stream()
            .filter(entry -> entry.definitionId() == text.getId()).findFirst().orElseThrow();
        assertEquals("Label", textColumn.label());
        assertEquals("text", textColumn.fieldType());
        assertNull(textColumn.options());

        CustomFieldSchemaDto selectColumn = schema.stream()
            .filter(entry -> entry.definitionId() == select.getId()).findFirst().orElseThrow();
        assertEquals(2, selectColumn.options().size());
    }

    @Test
    void visibleSchema_omitsArchivedFieldsAndOtherEntityTypes() {
        CustomFieldDefinition live = service.create(def("company", "text", "live_field"), null);
        CustomFieldDefinition retired = service.create(def("company", "text", "retired_field"), null);
        CustomFieldDefinition edit = def("company", "text", "retired_field");
        edit.setArchived(true);
        service.update(retired.getId(), edit, null);
        CustomFieldDefinition onPerson = service.create(def("person", "text", "person_field"), null);

        User member = newUser();
        authenticateAs(member, workspace.getId());
        List<Integer> visible = service.getVisibleSchema("company").stream()
            .map(CustomFieldSchemaDto::definitionId).toList();

        assertTrue(visible.contains(live.getId()));
        assertFalse(visible.contains(retired.getId()));
        assertFalse(visible.contains(onPerson.getId()));
    }

    @Test
    void visibleSchema_rejectsAnUnsupportedEntityType() {
        assertThrows(BadRequestException.class, () -> service.getVisibleSchema("invoice"));
    }

    @Test
    void visibleSchema_doesNotReachAnotherWorkspace() {
        CustomFieldDefinition otherWorkspaceField = service.create(def("company", "text", "other_ws_only"), null);
        Workspace other = newWorkspaceInSameOrg();
        User member = newUser();
        workspaceMapper.addMember(other.getId(), member.getId(), "member");
        authenticateAs(member, other.getId());

        assertTrue(service.getVisibleSchema("company").stream()
            .noneMatch(entry -> entry.definitionId() == otherWorkspaceField.getId()));
    }

    private Workspace newWorkspaceInSameOrg() {
        Workspace other = new Workspace();
        other.setName("Custom Field Peer Workspace");
        other.setSlug("custom-field-peer-" + unique());
        other.setOrgId(workspaceMapper.getOrgId(workspace.getId()));
        workspaceMapper.insert(other);
        return other;
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
