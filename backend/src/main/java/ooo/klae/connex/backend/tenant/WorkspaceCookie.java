package ooo.klae.connex.backend.tenant;

import java.time.Duration;

import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/**
 * Writes the non-HttpOnly {@code connex_workspace} cookie the frontend reads to
 * pin the active workspace (also forwarded by SSR pages). Not HttpOnly by design;
 * it is a non-sensitive id and the server always re-validates membership.
 */
@Component
public class WorkspaceCookie {

    public static final String NAME = "connex_workspace";
    private static final Duration ONE_YEAR = Duration.ofDays(365);

    private final WorkspaceCookieProperties properties;

    public WorkspaceCookie(WorkspaceCookieProperties properties) {
        this.properties = properties;
    }

    public void set(HttpServletResponse response, int workspaceId) {
        response.addHeader(HttpHeaders.SET_COOKIE, builder(Integer.toString(workspaceId))
            .maxAge(ONE_YEAR)
            .build()
            .toString());
    }

    public void clear(HttpServletResponse response) {
        response.addHeader(HttpHeaders.SET_COOKIE, builder("")
            .maxAge(Duration.ZERO)
            .build()
            .toString());
    }

    private ResponseCookie.ResponseCookieBuilder builder(String value) {
        ResponseCookie.ResponseCookieBuilder builder = ResponseCookie.from(NAME, value)
            .path("/")
            .sameSite(properties.sameSiteHeaderValue());
        if (properties.isSecure()) {
            builder.secure(true);
        }
        return builder;
    }
}
