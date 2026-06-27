package ooo.klae.connex.backend.services;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.ObjectMapper;

import ooo.klae.connex.backend.beans.SavedView;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.DuplicateResourceException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.SavedViewMapper;

/**
 * Business logic for per-user saved views. Every operation is scoped to the active
 * workspace AND the current user, so a member can only ever read or mutate their own
 * views — that ownership is the access boundary, so no {@code @RequirePermission} applies.
 * The {@code config} is an opaque JSON value owned by the client; the service only checks
 * it is present and within a size bound, then stores it verbatim.
 */
@Service
@RequiredArgsConstructor
public class SavedViewService {
    private final SavedViewMapper viewMapper;
    private final WorkspaceService workspaceService;
    private final AuthService authService;
    private final ObjectMapper objectMapper;

    private static final int MAX_NAME_LENGTH = 128;
    private static final int MAX_CONFIG_BYTES = 16384;
    private static final Set<String> RECORD_TYPES = Set.of("company", "person", "deal");

    /**
     * The current user's saved views — all of them, or just one record type.
     */
    public List<SavedView> list(String recordType) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        int userId = currentUserId();
        String type = normalize(recordType);
        return (type == null || type.isBlank())
            ? viewMapper.getByUser(workspaceId, userId)
            : viewMapper.getByRecordType(workspaceId, userId, type);
    }

    public SavedView getById(int id) {
        SavedView view = viewMapper.getById(workspaceService.getCurrentWorkspaceId(), currentUserId(), id);
        if (view == null) {
            throw new ResourceNotFoundException("Saved view not found with id: " + id);
        }
        return view;
    }

    @Transactional
    public SavedView create(String recordType, String name, Object config) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        int userId = currentUserId();
        String type = normalize(recordType);
        if (!RECORD_TYPES.contains(type)) {
            throw new BadRequestException("Invalid record type: " + recordType);
        }
        String trimmed = validateName(name);
        if (viewMapper.getByName(workspaceId, userId, type, trimmed) != null) {
            throw new DuplicateResourceException("name", "A saved view named '" + trimmed + "' already exists");
        }
        SavedView view = new SavedView();
        view.setWorkspaceId(workspaceId);
        view.setUserId(userId);
        view.setRecordType(type);
        view.setName(trimmed);
        view.setConfigJson(serializeConfig(config));
        viewMapper.insert(view);
        return view;
    }

    @Transactional
    public SavedView update(int id, String name, Object config, Integer position) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        int userId = currentUserId();
        SavedView view = viewMapper.getById(workspaceId, userId, id);
        if (view == null) {
            throw new ResourceNotFoundException("Saved view not found with id: " + id);
        }
        if (name != null) {
            String trimmed = validateName(name);
            if (!trimmed.equals(view.getName())) {
                SavedView clash = viewMapper.getByName(workspaceId, userId, view.getRecordType(), trimmed);
                if (clash != null && clash.getId() != id) {
                    throw new DuplicateResourceException("name", "A saved view named '" + trimmed + "' already exists");
                }
            }
            view.setName(trimmed);
        }
        if (config != null) {
            view.setConfigJson(serializeConfig(config));
        }
        if (position != null) {
            view.setPosition(position);
        }
        viewMapper.update(view);
        return view;
    }

    @Transactional
    public void delete(int id) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        int userId = currentUserId();
        if (viewMapper.getById(workspaceId, userId, id) == null) {
            throw new ResourceNotFoundException("Saved view not found with id: " + id);
        }
        viewMapper.delete(workspaceId, userId, id);
    }

    /**
     * Parses a stored config blob back into the JSON value the client sent (null on failure).
     */
    public Object parseConfig(String configJson) {
        if (configJson == null || configJson.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(configJson, Object.class);
        } catch (Exception e) {
            return null;
        }
    }

    private int currentUserId() {
        return authService.getCurrentUser().getId();
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

    private String serializeConfig(Object config) {
        if (config == null) {
            throw new BadRequestException("View config is required");
        }
        String json;
        try {
            json = objectMapper.writeValueAsString(config);
        } catch (Exception e) {
            throw new BadRequestException("Invalid view config");
        }
        if (json.getBytes(StandardCharsets.UTF_8).length > MAX_CONFIG_BYTES) {
            throw new BadRequestException("View config is too large");
        }
        return json;
    }

    private static String normalize(String recordType) {
        return recordType == null ? null : recordType.trim().toLowerCase();
    }
}
