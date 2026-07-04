package ooo.klae.connex.backend.services;

import java.nio.charset.StandardCharsets;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.ObjectMapper;

import ooo.klae.connex.backend.beans.UserDashboard;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.mappers.UserDashboardMapper;

/**
 * Business logic for a user's per-workspace dashboard layout. Every operation is scoped to the
 * active workspace AND the current user, so a member can only ever read or mutate their own
 * layout — that ownership is the access boundary, so no {@code @RequirePermission} applies. The
 * {@code layout} is an opaque JSON value owned by the client; the service only checks it is
 * present and within a size bound, then stores it verbatim.
 */
@Service
@RequiredArgsConstructor
public class DashboardLayoutService {
    private final UserDashboardMapper dashboardMapper;
    private final WorkspaceService workspaceService;
    private final AuthService authService;
    private final ObjectMapper objectMapper;
    private static final Logger log = LoggerFactory.getLogger(DashboardLayoutService.class);

    private static final int MAX_LAYOUT_BYTES = 16384;

    /**
     * The current user's saved layout for the active workspace, or {@code null} if they have
     * never customized their dashboard.
     */
    public UserDashboard getLayout() {
        return dashboardMapper.getByWorkspaceAndUser(workspaceService.getCurrentWorkspaceId(), currentUserId());
    }

    /**
     * Stores the current user's layout for the active workspace, replacing any existing one.
     */
    @Transactional
    public UserDashboard saveLayout(Object layout) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        int userId = currentUserId();
        UserDashboard dashboard = new UserDashboard();
        dashboard.setWorkspaceId(workspaceId);
        dashboard.setUserId(userId);
        dashboard.setLayoutJson(serializeLayout(layout));
        dashboardMapper.upsert(dashboard);
        return dashboardMapper.getByWorkspaceAndUser(workspaceId, userId);
    }

    /**
     * Removes the current user's saved layout for the active workspace so their dashboard reverts
     * to the default. Idempotent: a no-op when nothing is stored.
     */
    @Transactional
    public void resetLayout() {
        dashboardMapper.deleteByWorkspaceAndUser(workspaceService.getCurrentWorkspaceId(), currentUserId());
    }

    /**
     * Parses a stored layout blob back into the JSON value the client sent (null on failure).
     */
    public Object parseLayout(String layoutJson) {
        if (layoutJson == null || layoutJson.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(layoutJson, Object.class);
        } catch (Exception e) {
            log.warn("Failed to parse dashboard layout JSON", e);
            return null;
        }
    }

    private int currentUserId() {
        return authService.getCurrentUser().getId();
    }

    private String serializeLayout(Object layout) {
        if (layout == null) {
            throw new BadRequestException("Dashboard layout is required");
        }
        String json;
        try {
            json = objectMapper.writeValueAsString(layout);
        } catch (Exception e) {
            throw new BadRequestException("Invalid dashboard layout");
        }
        if (json.getBytes(StandardCharsets.UTF_8).length > MAX_LAYOUT_BYTES) {
            throw new BadRequestException("Dashboard layout is too large");
        }
        return json;
    }
}
