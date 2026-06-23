package ooo.klae.connex.backend.tenant;

import jakarta.servlet.http.HttpServletResponse;

/**
 * Writes the non-HttpOnly {@code connex_workspace} cookie the frontend reads to
 * pin the active workspace (also forwarded by SSR pages). Not HttpOnly by design;
 * it is a non-sensitive id and the server always re-validates membership.
 */
public final class WorkspaceCookie {

    public static final String NAME = "connex_workspace";
    private static final int ONE_YEAR_SECONDS = 31_536_000;

    private WorkspaceCookie() {}

    public static void set(HttpServletResponse response, int workspaceId) {
        response.addHeader("Set-Cookie",
            NAME + "=" + workspaceId + "; Path=/; Max-Age=" + ONE_YEAR_SECONDS + "; SameSite=Lax");
    }

    public static void clear(HttpServletResponse response) {
        response.addHeader("Set-Cookie", NAME + "=; Path=/; Max-Age=0; SameSite=Lax");
    }
}
