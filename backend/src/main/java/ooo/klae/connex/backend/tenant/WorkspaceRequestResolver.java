package ooo.klae.connex.backend.tenant;

import org.springframework.stereotype.Component;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.services.WorkspaceService;

/**
 * Resolves the requested workspace candidate from the header, cookie, or remembered membership.
 */
@Component
@RequiredArgsConstructor
public class WorkspaceRequestResolver {
    private static final String HEADER = "X-Workspace-Id";

    private final WorkspaceService workspaceService;

    public Integer resolve(HttpServletRequest request, int userId) {
        Integer fromHeader = parseId(request.getHeader(HEADER));
        if (fromHeader != null) {
            return fromHeader;
        }
        if (request.getCookies() != null) {
            for (Cookie cookie : request.getCookies()) {
                if (WorkspaceCookie.NAME.equals(cookie.getName())) {
                    Integer fromCookie = parseId(cookie.getValue());
                    if (fromCookie != null) {
                        return fromCookie;
                    }
                }
            }
        }
        return workspaceService.defaultWorkspaceIdFor(userId);
    }

    private static Integer parseId(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(value.trim());
        } catch (NumberFormatException exception) {
            return null;
        }
    }
}
