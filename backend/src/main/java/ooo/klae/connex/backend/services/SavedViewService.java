package ooo.klae.connex.backend.services;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.beans.SavedView;
import ooo.klae.connex.backend.beans.SavedViewPin;
import ooo.klae.connex.backend.dto.SavedViewCreateRequest;
import ooo.klae.connex.backend.dto.SavedViewUpdateRequest;
import ooo.klae.connex.backend.dto.SegmentDefinition;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.DuplicateResourceException;
import ooo.klae.connex.backend.exceptions.RequestBodyTooLargeException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.SavedViewMapper;
import ooo.klae.connex.backend.mappers.SavedViewPreferenceMapper;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Business policy for versioned, shareable saved views and caller-scoped preferences. */
@Service
@RequiredArgsConstructor
public class SavedViewService {
    private static final int MAX_NAME_LENGTH = 128;
    private static final int MAX_CONFIG_BYTES = 16_384;
    private static final int MAX_CONFIG_DEPTH = 8;
    private static final int MAX_OBJECT_PROPERTIES = 100;
    private static final int MAX_ARRAY_ITEMS = 100;
    private static final int MAX_STRING_LENGTH = 2_048;
    private static final int MAX_QUERY_LENGTH = 1_024;
    private static final int MAX_IDENTIFIER_LENGTH = 128;
    private static final int MAX_FILTERS = 64;
    private static final int MAX_FILTER_VALUES = 100;
    private static final int MAX_FILTER_VALUE_LENGTH = 255;
    private static final int MAX_SAVED_VIEWS_PER_OWNER_RECORD_TYPE = 100;
    private static final String PRIVATE = "private";
    private static final String WORKSPACE = "workspace";
    private static final String NOT_FOUND = "Saved view not found";
    private static final String QUOTA_REACHED =
        "A user cannot have more than 100 saved views per record type in a workspace";

    private final SavedViewMapper viewMapper;
    private final SavedViewPreferenceMapper preferenceMapper;
    private final WorkspaceService workspaceService;
    private final AuthService authService;
    private final ObjectMapper objectMapper;
    private final Validator validator;
    private final SegmentCatalog segmentCatalog;
    private final SegmentService segmentService;

    /** Returns every accessible view for one required, supported record type. */
    public List<SavedView> list(String recordType) {
        String type = requireRecordType(recordType);
        return viewMapper.getAccessibleByRecordType(currentWorkspaceId(), currentUserId(), type);
    }

    /** Resolves an owner-private or workspace-visible view in the active workspace. */
    public SavedView getById(int id) {
        return requireAccessible(currentWorkspaceId(), currentUserId(), id);
    }

    /** Creates a private or workspace-visible view owned by the authenticated caller. */
    @Transactional
    public SavedView create(SavedViewCreateRequest request) {
        int workspaceId = currentWorkspaceId();
        int userId = currentUserId();
        workspaceService.lockAndRequireMember(workspaceId, userId);
        String recordType = requireRecordType(request.getRecordType());
        String name = validateName(request.getName());
        JsonNode config = validateConfig(recordType, request.getConfig());
        if (viewMapper.countOwnedByRecordType(workspaceId, userId, recordType)
                >= MAX_SAVED_VIEWS_PER_OWNER_RECORD_TYPE) {
            throw new BadRequestException(QUOTA_REACHED);
        }
        String visibility = request.getVisibility() == null ? PRIVATE : requireVisibility(request.getVisibility());
        int position = request.getPosition() == null ? 0 : request.getPosition();
        rejectDuplicateName(workspaceId, userId, recordType, name, null);

        SavedView view = new SavedView();
        view.setWorkspaceId(workspaceId);
        view.setUserId(userId);
        view.setRecordType(recordType);
        view.setName(name);
        view.setConfig(config);
        view.setVisibility(visibility);
        view.setPosition(position);
        try {
            viewMapper.insert(view);
        } catch (DuplicateKeyException exception) {
            throw duplicateName(name);
        }
        return requireAccessible(workspaceId, userId, view.getId());
    }

    /** Replaces an owned view while retaining omitted visibility and position values. */
    @Transactional
    public SavedView update(int id, SavedViewUpdateRequest request) {
        int workspaceId = currentWorkspaceId();
        int userId = currentUserId();
        workspaceService.lockAndRequireMember(workspaceId, userId);
        SavedView view = requireOwnedForUpdate(workspaceId, userId, id);
        String recordType = requireRecordType(request.getRecordType());
        if (!view.getRecordType().equals(recordType)) {
            throw new BadRequestException("Saved view record type cannot be changed");
        }
        String name = validateName(request.getName());
        JsonNode config = validateConfig(recordType, request.getConfig());
        String visibility = request.getVisibility() == null
            ? view.getVisibility()
            : requireVisibility(request.getVisibility());
        int position = request.getPosition() == null ? view.getPosition() : request.getPosition();
        rejectDuplicateName(workspaceId, userId, recordType, name, id);
        boolean becamePrivate = WORKSPACE.equals(view.getVisibility()) && PRIVATE.equals(visibility);

        view.setName(name);
        view.setConfig(config);
        view.setVisibility(visibility);
        view.setPosition(position);
        try {
            viewMapper.update(view);
        } catch (DuplicateKeyException exception) {
            throw duplicateName(name);
        }
        if (becamePrivate) {
            preferenceMapper.deleteNonOwnerPinsForView(workspaceId, id, userId);
            preferenceMapper.deleteNonOwnerDefaultsForView(workspaceId, id, userId);
        }
        return requireAccessible(workspaceId, userId, id);
    }

    /** Deletes an owned saved view and its cascading same-plane preferences. */
    @Transactional
    public void delete(int id) {
        int workspaceId = currentWorkspaceId();
        int userId = currentUserId();
        workspaceService.lockAndRequireMember(workspaceId, userId);
        requireOwnedForUpdate(workspaceId, userId, id);
        if (viewMapper.delete(workspaceId, userId, id) == 0) {
            throw notFound();
        }
    }

    /** Returns the caller's accessible pinned views across all record types. */
    public List<SavedView> listPins() {
        return preferenceMapper.getAccessiblePins(currentWorkspaceId(), currentUserId());
    }

    /** Idempotently creates or repositions the caller's pin for an accessible view. */
    @Transactional
    public SavedView pin(int id, Integer requestedPosition) {
        int workspaceId = currentWorkspaceId();
        int userId = currentUserId();
        workspaceService.lockAndRequireMember(workspaceId, userId);
        requireAccessibleForUpdate(workspaceId, userId, id);
        SavedViewPin pin = preferenceMapper.getPin(workspaceId, userId, id);
        if (pin == null) {
            int position = requestedPosition == null
                ? nextPinPosition(workspaceId, userId)
                : requestedPosition;
            preferenceMapper.insertPin(workspaceId, userId, id, position);
        } else if (requestedPosition != null && requestedPosition != pin.getPosition()) {
            preferenceMapper.updatePinPosition(workspaceId, userId, id, requestedPosition);
        }
        return requireAccessible(workspaceId, userId, id);
    }

    private int nextPinPosition(int workspaceId, int userId) {
        int maxPosition = preferenceMapper.maxPinPosition(workspaceId, userId);
        if (maxPosition < Integer.MAX_VALUE) {
            return maxPosition + 1;
        }
        preferenceMapper.resequencePins(workspaceId, userId);
        return preferenceMapper.maxPinPosition(workspaceId, userId) + 1;
    }

    /** Idempotently removes a pin after proving the target remains accessible. */
    @Transactional
    public void unpin(int id) {
        int workspaceId = currentWorkspaceId();
        int userId = currentUserId();
        workspaceService.lockAndRequireMember(workspaceId, userId);
        requireAccessibleForUpdate(workspaceId, userId, id);
        preferenceMapper.deletePin(workspaceId, userId, id);
    }

    /** Returns the caller's accessible default for a supported record type, or null. */
    public SavedView getDefault(String recordType) {
        String type = requireRecordType(recordType);
        return preferenceMapper.getAccessibleDefault(currentWorkspaceId(), currentUserId(), type);
    }

    /** Atomically replaces the caller's default with an accessible same-type view. */
    @Transactional
    public SavedView setDefault(String recordType, int savedViewId) {
        int workspaceId = currentWorkspaceId();
        int userId = currentUserId();
        workspaceService.lockAndRequireMember(workspaceId, userId);
        String type = requireRecordType(recordType);
        SavedView view = requireAccessibleForUpdate(workspaceId, userId, savedViewId);
        if (!type.equals(view.getRecordType())) {
            throw new BadRequestException("Saved view record type does not match " + type);
        }
        preferenceMapper.upsertDefault(workspaceId, userId, type, savedViewId);
        return requireAccessible(workspaceId, userId, savedViewId);
    }

    /** Idempotently resets the caller's default for a supported record type. */
    @Transactional
    public void resetDefault(String recordType) {
        int workspaceId = currentWorkspaceId();
        int userId = currentUserId();
        workspaceService.lockAndRequireMember(workspaceId, userId);
        preferenceMapper.deleteDefault(workspaceId, userId, requireRecordType(recordType));
    }

    private int currentWorkspaceId() {
        return workspaceService.getCurrentWorkspaceId();
    }

    private int currentUserId() {
        return authService.getCurrentUser().getId();
    }

    private SavedView requireAccessible(int workspaceId, int userId, int id) {
        SavedView view = viewMapper.getAccessibleById(workspaceId, userId, id);
        if (view == null) {
            throw notFound();
        }
        return view;
    }

    private SavedView requireAccessibleForUpdate(int workspaceId, int userId, int id) {
        SavedView view = viewMapper.getAccessibleByIdForUpdate(workspaceId, userId, id);
        if (view == null) {
            throw notFound();
        }
        return view;
    }

    private SavedView requireOwnedForUpdate(int workspaceId, int userId, int id) {
        SavedView view = viewMapper.getOwnedByIdForUpdate(workspaceId, userId, id);
        if (view == null) {
            throw notFound();
        }
        return view;
    }

    private ResourceNotFoundException notFound() {
        return new ResourceNotFoundException(NOT_FOUND);
    }

    private String requireRecordType(String recordType) {
        String normalized = normalize(recordType);
        if (normalized == null || !segmentCatalog.recordTypes().contains(normalized)) {
            throw new BadRequestException("Invalid record type: " + recordType);
        }
        return normalized;
    }

    private String requireVisibility(String visibility) {
        String normalized = normalize(visibility);
        if (!PRIVATE.equals(normalized) && !WORKSPACE.equals(normalized)) {
            throw new BadRequestException("Invalid saved view visibility");
        }
        return normalized;
    }

    private String validateName(String name) {
        if (name == null || name.isBlank()) {
            throw new BadRequestException("View name is required");
        }
        String trimmed = name.trim();
        if (trimmed.length() > MAX_NAME_LENGTH) {
            throw new BadRequestException("View name is too long");
        }
        return trimmed;
    }

    private void rejectDuplicateName(
            int workspaceId, int userId, String recordType, String name, Integer currentId) {
        SavedView clash = viewMapper.getByName(workspaceId, userId, recordType, name);
        if (clash != null && (currentId == null || clash.getId() != currentId)) {
            throw duplicateName(name);
        }
    }

    private DuplicateResourceException duplicateName(String name) {
        return new DuplicateResourceException("name", "A saved view named '" + name + "' already exists");
    }

    private JsonNode validateConfig(String recordType, JsonNode config) {
        if (config == null || !config.isObject()) {
            throw new BadRequestException("View config must be a JSON object");
        }
        requireConfigSize(config);
        validateNodeBounds(config, 1);
        JsonNode version = config.get("version");
        if (version == null || !version.isIntegralNumber() || !version.canConvertToInt() || version.intValue() != 1) {
            throw new BadRequestException("View config version must be 1");
        }
        validateFilters(config.get("filters"));
        validateOptionalString(config.get("query"), "query", MAX_QUERY_LENGTH, true);
        validateSort(config.get("sort"));
        validateOptionalString(config.get("sortKey"), "sortKey", MAX_IDENTIFIER_LENGTH, true);
        validateSortDirection(config.get("sortDirection"));
        validateOptionalString(config.get("displayMode"), "displayMode", 32, false);
        validateStringArray(config.get("visibleColumns"), "visibleColumns", MAX_ARRAY_ITEMS, MAX_IDENTIFIER_LENGTH);
        validateStringArray(config.get("columnOrder"), "columnOrder", MAX_ARRAY_ITEMS, MAX_IDENTIFIER_LENGTH);
        validatePageSize(config.get("pageSize"));
        validateSegments(recordType, config.get("segments"));
        return config;
    }

    private void requireConfigSize(JsonNode config) {
        try {
            if (objectMapper.writeValueAsString(config).getBytes(StandardCharsets.UTF_8).length > MAX_CONFIG_BYTES) {
                throw new RequestBodyTooLargeException(MAX_CONFIG_BYTES);
            }
        } catch (RequestBodyTooLargeException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new BadRequestException("Invalid view config");
        }
    }

    private void validateNodeBounds(JsonNode node, int depth) {
        if (depth > MAX_CONFIG_DEPTH) {
            throw new BadRequestException("View config is nested too deeply");
        }
        if (node.isString() && node.asString().length() > MAX_STRING_LENGTH) {
            throw new BadRequestException("View config contains a string that is too long");
        }
        if (node.isObject()) {
            if (node.size() > MAX_OBJECT_PROPERTIES) {
                throw new BadRequestException("View config contains too many object properties");
            }
            for (Map.Entry<String, JsonNode> property : node.properties()) {
                if (property.getKey().length() > MAX_IDENTIFIER_LENGTH) {
                    throw new BadRequestException("View config contains a property name that is too long");
                }
                validateNodeBounds(property.getValue(), depth + 1);
            }
        } else if (node.isArray()) {
            if (node.size() > MAX_ARRAY_ITEMS) {
                throw new BadRequestException("View config contains too many array items");
            }
            for (JsonNode item : node) {
                validateNodeBounds(item, depth + 1);
            }
        }
    }

    private void validateFilters(JsonNode filters) {
        if (filters == null || filters.isNull()) {
            return;
        }
        if (!filters.isObject() || filters.size() > MAX_FILTERS) {
            throw new BadRequestException("View config filters must be a bounded JSON object");
        }
        for (Map.Entry<String, JsonNode> filter : filters.properties()) {
            if (filter.getKey().length() > MAX_IDENTIFIER_LENGTH) {
                throw new BadRequestException("View config filter key is too long");
            }
            validateStringArray(filter.getValue(), "filters." + filter.getKey(),
                MAX_FILTER_VALUES, MAX_FILTER_VALUE_LENGTH);
        }
    }

    private void validateOptionalString(
            JsonNode value, String field, int maxLength, boolean allowNull) {
        if (value == null || (allowNull && value.isNull())) {
            return;
        }
        if (!value.isString() || value.asString().length() > maxLength) {
            throw new BadRequestException("View config " + field + " must be a bounded string");
        }
    }

    private void validateSortDirection(JsonNode value) {
        if (value == null || value.isNull()) {
            return;
        }
        if (!value.isString() || !("asc".equals(value.asString()) || "desc".equals(value.asString()))) {
            throw new BadRequestException("View config sortDirection must be 'asc' or 'desc'");
        }
    }

    private void validateSort(JsonNode sort) {
        if (sort == null || sort.isNull()) {
            return;
        }
        if (!sort.isObject()) {
            throw new BadRequestException("View config sort must be a JSON object");
        }
        validateOptionalString(sort.get("key"), "sort.key", MAX_IDENTIFIER_LENGTH, true);
        JsonNode direction = sort.get("direction");
        if (direction != null && !direction.isNull()
                && (!direction.isString()
                    || !("asc".equals(direction.asString()) || "desc".equals(direction.asString())))) {
            throw new BadRequestException("View config sort.direction must be 'asc' or 'desc'");
        }
    }

    private void validateStringArray(JsonNode value, String field, int maxItems, int maxItemLength) {
        if (value == null || value.isNull()) {
            return;
        }
        if (!value.isArray() || value.size() > maxItems) {
            throw new BadRequestException("View config " + field + " must be a bounded string array");
        }
        for (JsonNode item : value) {
            if (!item.isString() || item.asString().isBlank() || item.asString().length() > maxItemLength) {
                throw new BadRequestException("View config " + field + " contains an invalid value");
            }
        }
    }

    private void validatePageSize(JsonNode pageSize) {
        if (pageSize == null || pageSize.isNull()) {
            return;
        }
        if (!pageSize.isIntegralNumber() || !pageSize.canConvertToInt()
                || pageSize.intValue() < 1 || pageSize.intValue() > 100) {
            throw new BadRequestException("View config pageSize must be between 1 and 100");
        }
    }

    private void validateSegments(String recordType, JsonNode segments) {
        if (segments == null || segments.isNull()) {
            return;
        }
        if (!segments.isObject()) {
            throw new BadRequestException("View config segments must be a JSON object");
        }
        SegmentDefinition definition;
        try {
            definition = objectMapper.treeToValue(segments, SegmentDefinition.class);
        } catch (Exception exception) {
            throw new BadRequestException("View config segments are malformed");
        }
        Set<ConstraintViolation<SegmentDefinition>> violations = validator.validate(definition);
        if (!violations.isEmpty()) {
            throw new BadRequestException("View config segments are malformed");
        }
        List<?> conditions = definition.getConditions() == null ? List.of() : definition.getConditions();
        List<?> groups = definition.getGroups() == null ? List.of() : definition.getGroups();
        if (conditions.isEmpty() && groups.isEmpty()) {
            if (!"all".equals(normalize(definition.getMatch())) || definition.isNegate()) {
                throw new BadRequestException("View config segments are malformed");
            }
            return;
        }
        segmentService.validate(recordType, definition);
    }

    private static String normalize(String value) {
        return value == null ? null : value.trim().toLowerCase(Locale.ROOT);
    }
}
