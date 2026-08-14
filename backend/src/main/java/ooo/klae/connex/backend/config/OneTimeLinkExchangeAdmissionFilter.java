package ooo.klae.connex.backend.config;

import java.io.IOException;
import java.util.Set;

import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import ooo.klae.connex.backend.services.LoginRateLimiter;
import ooo.klae.connex.backend.util.ClientIpResolver;

/** Rejects excessive unauthenticated one-time-link exchanges before controller database access. */
public class OneTimeLinkExchangeAdmissionFilter extends OncePerRequestFilter {

    private static final Set<String> EXCHANGE_PATHS = Set.of(
        "/api/auth/reset-password/exchange",
        "/api/auth/verify-email/exchange",
        "/api/auth/email-change/exchange",
        "/api/invites/exchange",
        "/api/invite-links/exchange");

    private final LoginRateLimiter rateLimiter;
    private final ClientIpResolver clientIpResolver;

    public OneTimeLinkExchangeAdmissionFilter(
            LoginRateLimiter rateLimiter, ClientIpResolver clientIpResolver) {
        this.rateLimiter = rateLimiter;
        this.clientIpResolver = clientIpResolver;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        if (isExchange(request)
                && !rateLimiter.tryAcquireOneTimeLinkExchange(
                    clientIpResolver.resolveWithProvenance(request), System.currentTimeMillis())) {
            response.sendError(HttpStatus.TOO_MANY_REQUESTS.value(),
                "Too many attempts. Please try again later.");
            return;
        }
        filterChain.doFilter(request, response);
    }

    private static boolean isExchange(HttpServletRequest request) {
        return HttpMethod.POST.matches(request.getMethod())
            && EXCHANGE_PATHS.contains(request.getServletPath());
    }
}
