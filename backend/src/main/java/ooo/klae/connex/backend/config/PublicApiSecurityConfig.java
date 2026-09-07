package ooo.klae.connex.backend.config;

import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.PathContainer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityCustomizer;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configurers.RequestCacheConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.ExceptionTranslationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.firewall.HttpStatusRequestRejectedHandler;
import org.springframework.security.web.firewall.RequestRejectedHandler;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;
import org.springframework.web.util.pattern.PathPatternParser;

import jakarta.servlet.http.HttpServletRequest;

import ooo.klae.connex.backend.publicapi.ApiCredentialAuthenticationFilter;
import ooo.klae.connex.backend.publicapi.ApiCredentialAuthenticationFilter.RouteTransactionMode;
import ooo.klae.connex.backend.publicapi.ApiCredentialService;
import ooo.klae.connex.backend.publicapi.ApiRateLimiter;
import ooo.klae.connex.backend.publicapi.PublicApiErrorAdvice;
import ooo.klae.connex.backend.publicapi.PublicApiCorsProcessor;
import ooo.klae.connex.backend.publicapi.PublicApiPaths;
import ooo.klae.connex.backend.services.PrivilegedAccountService;
import ooo.klae.connex.backend.tenant.TenantCatalogResolver;
import ooo.klae.connex.backend.tenant.TenantContext;
import ooo.klae.connex.backend.util.ClientIpResolver;
import ooo.klae.connex.backend.webauthn.WebAuthnService;
import tools.jackson.databind.ObjectMapper;

/** Isolated stateless Spring Security chain for the versioned public API. */
@Configuration
@ConditionalOnProperty(name = "connex.public-api.enabled", havingValue = "true")
public class PublicApiSecurityConfig {
    private static final List<RouteRule> ROUTE_RULES = List.of(
        new RouteRule(HttpMethod.GET, "/api/v1/me",
            ApiCredentialAuthenticationFilter.PUBLIC_API_AUTHORITY,
            RouteTransactionMode.READ));

    /** Returns the complete explicit public route-to-scope registry for architecture checks. */
    public static List<RouteRule> routeRules() {
        return ROUTE_RULES;
    }

    /** Resolves the transaction boundary for one public request from the explicit route registry. */
    public static RouteTransactionMode transactionMode(HttpServletRequest request) {
        HttpMethod requestMethod;
        try {
            requestMethod = HttpMethod.valueOf(request.getMethod());
        } catch (IllegalArgumentException exception) {
            return RouteTransactionMode.WRITE;
        }
        if (HttpMethod.OPTIONS.equals(requestMethod)) {
            return RouteTransactionMode.READ;
        }
        HttpMethod registryMethod = HttpMethod.HEAD.equals(requestMethod)
            ? HttpMethod.GET
            : requestMethod;
        String path = requestPath(request);
        for (RouteRule rule : ROUTE_RULES) {
            if (rule.method().equals(registryMethod)
                    && PathPatternParser.defaultInstance.parse(rule.path())
                        .matches(PathContainer.parsePath(path))) {
                return rule.transactionMode();
            }
        }
        return RouteTransactionMode.WRITE;
    }

    /** Builds the public chain ahead of the browser-session application chain. */
    @Bean
    @Order(1)
    SecurityFilterChain publicApiChain(
            HttpSecurity http,
            ApiCredentialService apiCredentialService,
            ApiRateLimiter apiRateLimiter,
            ObjectMapper objectMapper,
            PrivilegedMfaProperties privilegedMfaProperties,
            PrivilegedAccountService privilegedAccountService,
            WebAuthnService webAuthnService,
            ClientIpResolver clientIpResolver,
            TenantCatalogResolver tenantCatalogResolver,
            TenantContext tenantContext,
            CorsConfigurationSource corsConfigurationSource,
            PlatformTransactionManager transactionManager) throws Exception {
        ApiCredentialAuthenticationFilter authenticationFilter =
            new ApiCredentialAuthenticationFilter(
                apiCredentialService,
                apiRateLimiter,
                objectMapper,
                privilegedMfaProperties,
                privilegedAccountService,
                webAuthnService,
                clientIpResolver,
                tenantCatalogResolver,
                tenantContext,
                PublicApiSecurityConfig::transactionMode,
                transactionManager);
        CorsFilter corsFilter = new CorsFilter(corsConfigurationSource);
        corsFilter.setCorsProcessor(new PublicApiCorsProcessor(objectMapper));
        http.securityMatcher("/api/v1/**");
        http.addFilterBefore(authenticationFilter, ExceptionTranslationFilter.class);
        http.addFilterBefore(corsFilter, SecurityContextHolderFilter.class);
        http.cors(AbstractHttpConfigurer::disable);
        http.csrf(AbstractHttpConfigurer::disable);
        http.authorizeHttpRequests(auth -> {
            for (RouteRule rule : ROUTE_RULES) {
                for (HttpMethod method : rule.authorizationMethods()) {
                    auth.requestMatchers(method, rule.path()).hasAuthority(rule.authority());
                }
            }
            auth.anyRequest().denyAll();
        });
        http.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
        http.requestCache(RequestCacheConfigurer::disable);
        http.exceptionHandling(exceptions -> exceptions
            .authenticationEntryPoint((request, response, exception) -> PublicApiErrorAdvice.write(
                objectMapper,
                request,
                response,
                HttpStatus.UNAUTHORIZED,
                "invalid_token",
                "A valid bearer credential is required"))
            .accessDeniedHandler((request, response, exception) -> PublicApiErrorAdvice.write(
                objectMapper,
                request,
                response,
                HttpStatus.FORBIDDEN,
                "insufficient_scope",
                "The credential cannot access this resource")));
        http.headers(headers -> headers
            .httpStrictTransportSecurity(hsts -> hsts.includeSubDomains(true).maxAgeInSeconds(31536000))
            .referrerPolicy(referrer -> referrer.policy(ReferrerPolicy.NO_REFERRER))
            .contentSecurityPolicy(csp -> csp.policyDirectives(SecurityResponseHeaders.CONTENT_SECURITY_POLICY)));
        return http.build();
    }

    private static String requestPath(HttpServletRequest request) {
        String path = request.getRequestURI();
        String contextPath = request.getContextPath();
        return contextPath == null || contextPath.isEmpty() || !path.startsWith(contextPath)
            ? path
            : path.substring(contextPath.length());
    }

    /** One exact public controller mapping, required authority, and transaction mode. */
    public record RouteRule(
            HttpMethod method,
            String path,
            String authority,
            RouteTransactionMode transactionMode) {
        /** Pins GET reads to snapshots and keeps every declared mutation outside that boundary. */
        public RouteRule {
            Objects.requireNonNull(method);
            Objects.requireNonNull(path);
            Objects.requireNonNull(authority);
            Objects.requireNonNull(transactionMode);
            RouteTransactionMode requiredMode = HttpMethod.GET.equals(method)
                ? RouteTransactionMode.READ
                : RouteTransactionMode.WRITE;
            if (!requiredMode.equals(transactionMode)) {
                throw new IllegalArgumentException(
                    "GET public routes must use READ mode and every other mapping must use WRITE mode");
            }
        }

        /** Expands every registered read to its framework-provided HEAD representation. */
        public Set<HttpMethod> authorizationMethods() {
            return RouteTransactionMode.READ.equals(transactionMode)
                ? Set.of(HttpMethod.GET, HttpMethod.HEAD)
                : Set.of(method);
        }
    }
}

/** Installs public-aware firewall handling regardless of public-plane availability. */
@Configuration(proxyBeanMethods = false)
class PublicApiFirewallConfig {
    private static final Set<String> FIREWALL_ALLOWED_METHODS = Set.of(
        "DELETE", "GET", "HEAD", "OPTIONS", "PATCH", "POST", "PUT");

    /** Handles firewall refusals without relaxing the strict firewall. */
    @Bean
    RequestRejectedHandler publicApiRequestRejectedHandler(
            ObjectMapper objectMapper,
            @Value("${connex.public-api.enabled:false}") boolean publicApiEnabled) {
        HttpStatusRequestRejectedHandler defaultHandler = new HttpStatusRequestRejectedHandler();
        return (request, response, rejection) -> {
            if (!PublicApiPaths.isPublicRequest(request)) {
                defaultHandler.handle(request, response, rejection);
                return;
            }
            if (!publicApiEnabled) {
                PublicApiErrorAdvice.write(
                    objectMapper,
                    request,
                    response,
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "public_api_unavailable",
                    "Public API is unavailable");
                return;
            }
            boolean methodRejected = !FIREWALL_ALLOWED_METHODS.contains(request.getMethod());
            PublicApiErrorAdvice.write(
                objectMapper,
                request,
                response,
                methodRejected ? HttpStatus.METHOD_NOT_ALLOWED : HttpStatus.BAD_REQUEST,
                methodRejected ? "method_not_allowed" : "bad_request",
                methodRejected ? "Request method is not supported" : "Request was rejected");
        };
    }

    /** Installs the public-aware handler at the filter-chain firewall boundary. */
    @Bean
    WebSecurityCustomizer publicApiRequestRejectedHandlerCustomizer(
            @Qualifier("publicApiRequestRejectedHandler") RequestRejectedHandler handler) {
        return web -> web.requestRejectedHandler(handler);
    }
}
