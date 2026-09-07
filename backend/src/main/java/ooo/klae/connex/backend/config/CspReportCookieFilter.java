package ooo.klae.connex.backend.config;

import java.io.IOException;

import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Hides every request cookie from the browser CSP violation collector.
 *
 * <p>A same-origin report carries the session cookie, and merely resolving that session is what
 * Spring Session counts as access: it rewrites the last-accessed time, so a page that keeps
 * violating the policy would keep an otherwise idle login alive past the idle timeout. Confining
 * the collector to a session-free Spring Security chain is not sufficient, because the resolution
 * happens outside any chain's reach — {@code AnonymousAuthenticationFilter} builds
 * {@code WebAuthenticationDetails} from the session id, and the {@code DispatcherServlet} looks up
 * flash maps and publishes a request-handled event, all through {@code getSession(false)}.
 *
 * <p>Removing the cookies is what makes those calls harmless rather than merely unlikely: Spring
 * Session resolves the session id from the request cookies, so with none present there is no
 * session to load, touch, save or expire, and the response carries no {@code Set-Cookie}. The
 * collector reads no cookie of any kind — it is unauthenticated, CSRF-exempt and tenant-agnostic —
 * so nothing downstream loses anything it was entitled to.
 *
 * <p>Registered ahead of Spring Session's own filter, and matched with the same matcher that
 * selects {@link CspReportSecurityConfig}'s chain so the two can never disagree about which
 * requests are reports.
 */
public class CspReportCookieFilter extends OncePerRequestFilter {
    private static final Cookie[] NO_COOKIES = new Cookie[0];

    private final RequestMatcher matcher;

    public CspReportCookieFilter(RequestMatcher matcher) {
        this.matcher = matcher;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !matcher.matches(request);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        filterChain.doFilter(new CookielessRequest(request), response);
    }

    /** A view of the request that carries no cookies and names no requested session. */
    private static final class CookielessRequest extends HttpServletRequestWrapper {
        private CookielessRequest(HttpServletRequest request) {
            super(request);
        }

        @Override
        public Cookie[] getCookies() {
            return NO_COOKIES;
        }

        @Override
        public String getRequestedSessionId() {
            return null;
        }

        @Override
        public boolean isRequestedSessionIdValid() {
            return false;
        }
    }
}
