package ooo.klae.connex.backend.config;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.web.servlet.RegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configurers.RequestCacheConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.session.web.http.SessionRepositoryFilter;

/**
 * Session-neutral Spring Security chain for the browser CSP violation collector.
 *
 * <p>A same-origin report carries the session cookie, and in the application chain the epoch,
 * absolute-timeout and privileged-MFA filters all resolve that session before any per-path
 * exemption can run. Resolving it is what Spring Session counts as access: it rewrites the
 * session's last-accessed time, so a page that keeps violating the policy keeps an otherwise idle
 * login alive past the idle timeout. This chain owns {@code POST /api/csp-reports} exclusively,
 * precedes both the public-API and the application chain, carries none of their session filters,
 * and never calls {@code getSession()} — Spring Session's request wrapper only loads, touches and
 * saves a session when something asks for it, so the cookie rides along unread.
 *
 * <p>{@code STATELESS} is what makes that hold rather than being merely observed: it swaps the
 * shared security-context repository for a request-attribute one, so a later filter or interceptor
 * that reads {@code SecurityContextHolder} cannot fault a session in. The request cache and logout
 * support are disabled for the same reason — both of their default implementations reach for the
 * session. Every other method on the path still falls through to the application chain, where it
 * stays unauthenticated and therefore write-only.
 *
 * <p>A chain alone cannot deliver session-neutrality, because the remaining
 * {@code getSession(false)} callers sit outside every chain: Spring Security's own
 * {@code AnonymousAuthenticationFilter} reads the session id into {@code WebAuthenticationDetails},
 * and the {@code DispatcherServlet} retrieves flash maps and publishes a request-handled event.
 * {@link CspReportCookieFilter}, registered ahead of Spring Session, is what closes those: with no
 * cookies on the request there is no session for any of them to resolve.
 */
@Configuration
public class CspReportSecurityConfig {
    static final String CSP_REPORT_PATH = "/api/csp-reports";

    /**
     * The single definition of "this request is a violation report".
     *
     * <p>Shared by the chain and the cookie filter so the two cannot disagree about which requests
     * are reports — a request one of them matched and the other did not would be exactly the gap
     * this configuration exists to close.
     *
     * @return a matcher for {@code POST} on the collector path
     */
    static RequestMatcher cspReportMatcher() {
        return PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, CSP_REPORT_PATH);
    }

    /**
     * Strips cookies from reports before Spring Session can resolve one.
     *
     * <p>The order is derived from Spring Session's own registration rather than from
     * {@link SessionRepositoryFilter#DEFAULT_ORDER}: {@code spring.session.servlet.filter-order}
     * moves that registration, and an operator who sets it below the library default would
     * otherwise leave Spring Session running first — resolving and refreshing the session before
     * the cookies were hidden, which is precisely the defect this filter exists to prevent and
     * would fail silently. Reading the session registration here cannot close a dependency cycle,
     * because it depends only on {@code SessionProperties}; it is read through an
     * {@link ObjectProvider} so that a context without Spring Session — where there is no filter to
     * precede — still starts, on the library default.
     *
     * @param sessionRepositoryFilterRegistration Spring Session's own filter registration
     * @return the registration, ordered immediately ahead of Spring Session's own filter
     */
    @Bean
    FilterRegistrationBean<CspReportCookieFilter> cspReportCookieFilterRegistration(
            @Qualifier("sessionRepositoryFilterRegistration")
            ObjectProvider<RegistrationBean> sessionRepositoryFilterRegistration) {
        FilterRegistrationBean<CspReportCookieFilter> registration =
            new FilterRegistrationBean<>(new CspReportCookieFilter(cspReportMatcher()));
        RegistrationBean springSession = sessionRepositoryFilterRegistration.getIfAvailable();
        registration.setOrder(immediatelyAheadOf(springSession == null
            ? SessionRepositoryFilter.DEFAULT_ORDER
            : springSession.getOrder()));
        return registration;
    }

    /**
     * The filter-registration order immediately ahead of another registration's.
     *
     * @param order the order to precede
     * @return {@code order - 1}
     * @throws IllegalStateException when {@code order} is {@link Integer#MIN_VALUE}, where no
     *     earlier order exists: subtracting would wrap to {@link Integer#MAX_VALUE} and register
     *     the cookie filter last, so the collector would silently go back to resolving and
     *     refreshing the sessions its reports carry. A configuration that cannot honour the
     *     guarantee must fail at startup rather than pretend to
     */
    static int immediatelyAheadOf(int order) {
        if (order == Integer.MIN_VALUE) {
            throw new IllegalStateException(
                "spring.session.servlet.filter-order is Integer.MIN_VALUE, leaving no order ahead "
                    + "of Spring Session for the CSP report cookie filter; raise it");
        }
        return order - 1;
    }

    /** Builds the stateless collector chain ahead of the public-API and application chains. */
    @Bean
    @Order(0)
    SecurityFilterChain cspReportChain(HttpSecurity http) throws Exception {
        http.securityMatcher(cspReportMatcher());
        http.csrf(AbstractHttpConfigurer::disable);
        http.cors(AbstractHttpConfigurer::disable);
        http.logout(AbstractHttpConfigurer::disable);
        http.requestCache(RequestCacheConfigurer::disable);
        http.sessionManagement(session -> session
            .sessionCreationPolicy(SessionCreationPolicy.STATELESS));
        http.authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        http.headers(headers -> headers
            .httpStrictTransportSecurity(hsts -> hsts
                .includeSubDomains(true)
                .maxAgeInSeconds(31536000)
            )
            .referrerPolicy(referrer -> referrer
                .policy(ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN)
            )
            .contentSecurityPolicy(csp -> csp
                .policyDirectives(SecurityResponseHeaders.CONTENT_SECURITY_POLICY)
            )
        );
        return http.build();
    }
}
