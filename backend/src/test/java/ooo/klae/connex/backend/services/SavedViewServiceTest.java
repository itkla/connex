package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import ooo.klae.connex.backend.beans.SavedView;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.dto.SavedViewCreateRequest;
import ooo.klae.connex.backend.dto.SavedViewUpdateRequest;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.DuplicateResourceException;
import ooo.klae.connex.backend.exceptions.RequestBodyTooLargeException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.SavedViewMapper;
import ooo.klae.connex.backend.mappers.SavedViewPreferenceMapper;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

class SavedViewServiceTest extends AbstractServiceTest {

    @Autowired private SavedViewService service;
    @Autowired private SavedViewMapper viewMapper;
    @Autowired private SavedViewPreferenceMapper preferenceMapper;
    @Autowired private ObjectMapper objectMapper;

    @Test
    void createPersistsV1ConfigAndPreservesUnknownFields() {
        ObjectNode config = config();
        config.put("query", "acme");
        ObjectNode unknown = objectMapper.createObjectNode();
        unknown.put("future", true);
        config.set("unrecognized", unknown);

        SavedView created = service.create(createRequest("company", "Hot prospects", "workspace", config, 2));
        SavedView found = service.getById(created.getId());

        assertNotEquals(0, created.getId());
        assertEquals(config, found.getConfig());
        assertEquals("workspace", found.getVisibility());
        assertEquals(2, found.getPosition());
        assertTrue(found.isOwnedByCurrentUser());
        assertFalse(found.isPinned());
    }

    @Test
    void unversionedCreateAndUpdateAreStampedAndRoundTrip() {
        ObjectNode createConfig = objectMapper.createObjectNode();
        createConfig.set("filters", objectMapper.createObjectNode());
        createConfig.put("query", "legacy create");
        createConfig.put("futureCreateField", true);

        SavedView created = service.create(
            createRequest("company", "Legacy compatible", null, createConfig, null));

        ObjectNode expectedCreateConfig = createConfig.deepCopy();
        expectedCreateConfig.put("version", 1);
        assertEquals(expectedCreateConfig, created.getConfig());
        assertFalse(createConfig.has("version"));

        ObjectNode updateConfig = objectMapper.createObjectNode();
        updateConfig.set("filters", objectMapper.createObjectNode());
        updateConfig.put("query", "legacy update");
        updateConfig.put("futureUpdateField", "preserved");

        SavedView updated = service.update(created.getId(),
            updateRequest("company", "Legacy compatible", null, updateConfig, null));

        ObjectNode expectedUpdateConfig = updateConfig.deepCopy();
        expectedUpdateConfig.put("version", 1);
        assertEquals(expectedUpdateConfig, updated.getConfig());
        assertEquals(expectedUpdateConfig, service.getById(created.getId()).getConfig());
        assertFalse(updateConfig.has("version"));
    }

    @Test
    void allSegmentCatalogRecordTypesAreAccepted() {
        for (String recordType : List.of("company", "person", "deal")) {
            assertNotEquals(0,
                service.create(createRequest(recordType, recordType + " view", null, config(), null)).getId());
        }
        assertThrows(BadRequestException.class,
            () -> service.create(createRequest("widget", "Widget", null, config(), null)));
    }

    @Test
    void duplicateOwnerTypeNameConflictsButOtherTypeAndOwnerAreAllowed() {
        service.create(createRequest("company", "Prospects", null, config(), null));
        assertThrows(DuplicateResourceException.class,
            () -> service.create(createRequest("company", "Prospects", null, config(), null)));
        assertNotEquals(0,
            service.create(createRequest("deal", "Prospects", null, config(), null)).getId());

        User other = newUser();
        authenticateAs(other, workspace.getId());
        assertNotEquals(0,
            service.create(createRequest("company", "Prospects", null, config(), null)).getId());
    }

    @Test
    void createQuotaIsPerOwnerAndRecordType() {
        for (int index = 0; index < 100; index++) {
            seedView(currentUser, "company", "Quota " + index);
        }

        BadRequestException quotaFailure = assertThrows(BadRequestException.class,
            () -> service.create(createRequest(
                "company", "Over quota", "workspace", config(), null)));
        assertEquals(
            "A user cannot have more than 100 saved views per record type in a workspace",
            quotaFailure.getMessage());
        assertNotEquals(0,
            service.create(createRequest("deal", "Deal view", "workspace", config(), null)).getId());

        User other = newUser();
        authenticateAs(other, workspace.getId());
        assertNotEquals(0,
            service.create(createRequest("company", "Other owner", "workspace", config(), null)).getId());
    }

    @Test
    void sharedReadIsVisibleButOwnerMutationsStayNonLeaking() {
        SavedView shared = service.create(
            createRequest("company", "Shared", "workspace", config(), null));
        SavedView privateView = service.create(
            createRequest("company", "Private", "private", config(), null));
        User recipient = newUser();
        authenticateAs(recipient, workspace.getId());

        assertEquals(shared.getId(), service.getById(shared.getId()).getId());
        assertThrows(ResourceNotFoundException.class, () -> service.getById(privateView.getId()));
        ResourceNotFoundException updateFailure = assertThrows(ResourceNotFoundException.class,
            () -> service.update(shared.getId(),
                updateRequest("company", "Changed", null, config(), null)));
        ResourceNotFoundException deleteFailure = assertThrows(ResourceNotFoundException.class,
            () -> service.delete(shared.getId()));
        assertEquals("Saved view not found", updateFailure.getMessage());
        assertEquals("Saved view not found", deleteFailure.getMessage());
    }

    @Test
    void updateRetainsOmittedPreferenceFieldsAndRejectsRecordTypeChanges() {
        SavedView created = service.create(
            createRequest("person", "Contacts", "workspace", config(), 3));
        ObjectNode replacement = config();
        replacement.put("query", "updated");

        SavedView updated = service.update(created.getId(),
            updateRequest("person", "Renamed", null, replacement, null));

        assertEquals("Renamed", updated.getName());
        assertEquals("workspace", updated.getVisibility());
        assertEquals(3, updated.getPosition());
        assertEquals("updated", updated.getConfig().path("query").asString());
        assertThrows(BadRequestException.class, () -> service.update(created.getId(),
            updateRequest("deal", "Renamed", null, replacement, null)));
    }

    @Test
    void pinsAppendRepositionAndRemainPerUser() {
        SavedView first = service.create(
            createRequest("company", "First", "workspace", config(), null));
        SavedView second = service.create(
            createRequest("deal", "Second", "workspace", config(), null));
        User recipient = newUser();
        authenticateAs(recipient, workspace.getId());

        assertEquals(0, service.pin(second.getId(), null).getPinPosition());
        assertEquals(1, service.pin(first.getId(), null).getPinPosition());
        assertEquals(1, service.pin(first.getId(), null).getPinPosition());
        assertEquals(0, service.pin(first.getId(), 0).getPinPosition());
        assertEquals(List.of(first.getId(), second.getId()),
            service.listPins().stream().map(SavedView::getId).toList());

        authenticateAs(currentUser, workspace.getId());
        assertTrue(service.listPins().isEmpty());
        authenticateAs(recipient, workspace.getId());
        service.unpin(first.getId());
        service.unpin(first.getId());
        assertEquals(List.of(second.getId()), service.listPins().stream().map(SavedView::getId).toList());
    }

    @Test
    void positionlessPinsResequenceAndAppendWhenTheExistingMaximumIsIntMax() {
        SavedView older = service.create(
            createRequest("company", "Older", "workspace", config(), null));
        SavedView newer = service.create(
            createRequest("company", "Newer", "workspace", config(), null));

        assertEquals(Integer.MAX_VALUE, service.pin(newer.getId(), Integer.MAX_VALUE).getPinPosition());
        assertEquals(1, service.pin(older.getId(), null).getPinPosition());
        assertEquals(List.of(newer.getId(), older.getId()),
            service.listPins().stream().map(SavedView::getId).toList());
    }

    @Test
    void defaultsReplacePerUserAndDoNotImplicitlyPin() {
        SavedView first = service.create(
            createRequest("company", "First", "workspace", config(), null));
        SavedView second = service.create(
            createRequest("company", "Second", "workspace", config(), null));
        User recipient = newUser();
        authenticateAs(recipient, workspace.getId());

        assertEquals(first.getId(), service.setDefault("company", first.getId()).getId());
        assertEquals(second.getId(), service.setDefault("company", second.getId()).getId());
        SavedView selected = service.getDefault("company");
        assertEquals(second.getId(), selected.getId());
        assertTrue(selected.isDefaultView());
        assertFalse(selected.isPinned());

        service.resetDefault("company");
        service.resetDefault("company");
        assertNull(service.getDefault("company"));
        assertThrows(BadRequestException.class, () -> service.setDefault("deal", first.getId()));
    }

    @Test
    void workspaceToPrivateDeletesEveryNonOwnerPreference() {
        SavedView shared = service.create(
            createRequest("company", "Downgrade", "workspace", config(), null));
        User recipient = newUser();
        authenticateAs(recipient, workspace.getId());
        service.pin(shared.getId(), 4);
        service.setDefault("company", shared.getId());

        authenticateAs(currentUser, workspace.getId());
        service.update(shared.getId(),
            updateRequest("company", "Downgrade", "private", config(), null));

        assertNull(preferenceMapper.getPin(workspace.getId(), recipient.getId(), shared.getId()));
        assertNull(preferenceMapper.getDefault(workspace.getId(), recipient.getId(), "company"));
        authenticateAs(recipient, workspace.getId());
        assertThrows(ResourceNotFoundException.class, () -> service.getById(shared.getId()));
    }

    @Test
    void configValidatesKnownShapeBoundsAndDelegatesNonEmptySegments() {
        ObjectNode emptySegmentConfig = config();
        ObjectNode emptySegment = objectMapper.createObjectNode();
        emptySegment.put("match", "all");
        emptySegment.set("conditions", objectMapper.createArrayNode());
        emptySegmentConfig.set("segments", emptySegment);
        assertNotEquals(0,
            service.create(createRequest("company", "Empty segment", null, emptySegmentConfig, null)).getId());

        ObjectNode invalidEmptySegmentConfig = config();
        ObjectNode invalidEmptySegment = objectMapper.createObjectNode();
        invalidEmptySegment.put("match", "any");
        invalidEmptySegment.set("conditions", objectMapper.createArrayNode());
        invalidEmptySegmentConfig.set("segments", invalidEmptySegment);
        assertThrows(BadRequestException.class,
            () -> service.create(
                createRequest("company", "Invalid empty segment", null, invalidEmptySegmentConfig, null)));

        ObjectNode validSegmentConfig = config();
        validSegmentConfig.set("segments", segment("name"));
        assertNotEquals(0,
            service.create(createRequest("company", "Valid segment", null, validSegmentConfig, null)).getId());

        ObjectNode invalidSegmentConfig = config();
        invalidSegmentConfig.set("segments", segment("unknown_field"));
        assertThrows(BadRequestException.class,
            () -> service.create(createRequest("company", "Invalid segment", null, invalidSegmentConfig, null)));

        ObjectNode invalidPageSize = config();
        invalidPageSize.put("pageSize", 101);
        assertThrows(BadRequestException.class,
            () -> service.create(createRequest("company", "Page size", null, invalidPageSize, null)));

        ObjectNode invalidFilters = config();
        invalidFilters.put("filters", "not-an-object");
        assertThrows(BadRequestException.class,
            () -> service.create(createRequest("company", "Filters", null, invalidFilters, null)));

        ObjectNode invalidSort = config();
        ObjectNode sort = objectMapper.createObjectNode();
        sort.put("key", "name");
        sort.put("direction", "sideways");
        invalidSort.set("sort", sort);
        assertThrows(BadRequestException.class,
            () -> service.create(createRequest("company", "Sort", null, invalidSort, null)));

        ObjectNode tooManyColumns = config();
        ArrayNode columns = objectMapper.createArrayNode();
        for (int index = 0; index < 101; index++) {
            columns.add("column_" + index);
        }
        tooManyColumns.set("columnOrder", columns);
        assertThrows(BadRequestException.class,
            () -> service.create(createRequest("company", "Columns", null, tooManyColumns, null)));
    }

    @Test
    void maximumCatalogSegmentDepthCanBeSaved() {
        ObjectNode maxDepthConfig = config();
        maxDepthConfig.set("segments", nestedSegment(4));

        SavedView created = service.create(
            createRequest("company", "Maximum depth", null, maxDepthConfig, null));

        assertEquals(maxDepthConfig, created.getConfig());

        ObjectNode tooDeepConfig = config();
        tooDeepConfig.set("segments", nestedSegment(5));
        assertThrows(BadRequestException.class,
            () -> service.create(createRequest("company", "Too deep", null, tooDeepConfig, null)));
    }

    @Test
    void configRejectsUnsupportedVersionsNonObjectsAndOversizedUtf8() {
        ObjectNode unsupportedVersion = objectMapper.createObjectNode();
        unsupportedVersion.put("version", 2);
        assertThrows(BadRequestException.class,
            () -> service.create(
                createRequest("company", "Unsupported version", null, unsupportedVersion, null)));
        assertThrows(BadRequestException.class,
            () -> service.create(createRequest("company", "Array", null, objectMapper.createArrayNode(), null)));

        ObjectNode oversized = config();
        oversized.put("unknown", "界".repeat(6_000));
        assertThrows(RequestBodyTooLargeException.class,
            () -> service.create(createRequest("company", "Oversized", null, oversized, null)));
    }

    @Test
    void recordsAreIsolatedByActiveWorkspace() {
        SavedView inFirst = service.create(
            createRequest("company", "First workspace", "workspace", config(), null));
        Workspace other = newWorkspace();
        workspaceMapper.addMember(other.getId(), currentUser.getId(), "member");
        authenticateAs(currentUser, other.getId());

        assertThrows(ResourceNotFoundException.class, () -> service.getById(inFirst.getId()));
        SavedView inSecond = service.create(
            createRequest("company", "Second workspace", "workspace", config(), null));

        authenticateAs(currentUser, workspace.getId());
        assertThrows(ResourceNotFoundException.class, () -> service.getById(inSecond.getId()));
    }

    private SavedViewCreateRequest createRequest(
            String recordType, String name, String visibility, tools.jackson.databind.JsonNode config,
            Integer position) {
        SavedViewCreateRequest request = new SavedViewCreateRequest();
        request.setRecordType(recordType);
        request.setName(name);
        request.setVisibility(visibility);
        request.setConfig(config);
        request.setPosition(position);
        return request;
    }

    private SavedViewUpdateRequest updateRequest(
            String recordType, String name, String visibility, tools.jackson.databind.JsonNode config,
            Integer position) {
        SavedViewUpdateRequest request = new SavedViewUpdateRequest();
        request.setRecordType(recordType);
        request.setName(name);
        request.setVisibility(visibility);
        request.setConfig(config);
        request.setPosition(position);
        return request;
    }

    private ObjectNode config() {
        ObjectNode config = objectMapper.createObjectNode();
        config.put("version", 1);
        config.set("filters", objectMapper.createObjectNode());
        config.put("query", "");
        return config;
    }

    private SavedView seedView(User owner, String recordType, String name) {
        SavedView view = new SavedView();
        view.setWorkspaceId(workspace.getId());
        view.setUserId(owner.getId());
        view.setRecordType(recordType);
        view.setName(name);
        view.setConfig(config());
        view.setVisibility("workspace");
        view.setPosition(0);
        viewMapper.insert(view);
        return view;
    }

    private ObjectNode segment(String field) {
        ObjectNode condition = objectMapper.createObjectNode();
        condition.put("type", "field");
        condition.put("field", field);
        condition.put("op", "contains");
        condition.put("value", "Acme");
        ArrayNode conditions = objectMapper.createArrayNode();
        conditions.add(condition);
        ObjectNode segment = objectMapper.createObjectNode();
        segment.put("match", "all");
        segment.set("conditions", conditions);
        return segment;
    }

    private ObjectNode nestedSegment(int depth) {
        if (depth == 1) {
            return segment("name");
        }
        ObjectNode segment = objectMapper.createObjectNode();
        segment.put("match", "all");
        ArrayNode groups = objectMapper.createArrayNode();
        groups.add(nestedSegment(depth - 1));
        segment.set("groups", groups);
        return segment;
    }

    private Workspace newWorkspace() {
        Workspace created = new Workspace();
        created.setName("Workspace " + unique());
        created.setSlug("workspace-" + unique());
        workspaceMapper.insert(created);
        return created;
    }
}
