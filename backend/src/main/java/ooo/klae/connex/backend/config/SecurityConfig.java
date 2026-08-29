package ooo.klae.connex.backend.config;

import static org.springframework.security.config.Customizer.withDefaults;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.servlet.filter.OrderedFormContentFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.oauth2.client.oidc.authentication.OidcIdTokenDecoderFactory;
import org.springframework.security.oauth2.client.oidc.authentication.OidcIdTokenValidator;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.saml2.core.Saml2Error;
import org.springframework.security.saml2.core.Saml2ResponseValidatorResult;
import org.springframework.security.saml2.provider.service.authentication.OpenSaml5AuthenticationProvider;
import ooo.klae.connex.backend.sso.CompositeClientRegistrationRepository;
import ooo.klae.connex.backend.sso.SocialLoginClientRegistrations;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.RequestCacheConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.session.SessionRegistryImpl;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.session.security.SpringSessionBackedSessionRegistry;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.util.pattern.PathPatternParser;

import jakarta.servlet.http.HttpServletResponse;

import ooo.klae.connex.backend.businesscard.BusinessCardImportAdmissionFilter;
import ooo.klae.connex.backend.businesscard.BusinessCardRateLimiter;
import ooo.klae.connex.backend.capability.CapabilityEntitlement;
import ooo.klae.connex.backend.observability.CorrelationIdFilter;
import ooo.klae.connex.backend.observability.MetricsScrapeTokenFilter;
import ooo.klae.connex.backend.sso.DbRelyingPartyRegistrationRepository;
import ooo.klae.connex.backend.sso.SsoAuthenticationSuccessHandler;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.notifications.WebSocketSessionRegistry;
import ooo.klae.connex.backend.services.AuditService;
import ooo.klae.connex.backend.services.LoginRateLimiter;
import ooo.klae.connex.backend.services.PrivilegedAccountService;
import ooo.klae.connex.backend.services.SessionSecurityService;
import ooo.klae.connex.backend.services.WorkspaceService;
import ooo.klae.connex.backend.tenant.WorkspaceCookie;
import ooo.klae.connex.backend.tenant.WorkspaceRequestResolver;
import ooo.klae.connex.backend.util.ClientIpResolver;
import ooo.klae.connex.backend.webauthn.WebAuthnService;

/**
 * Spring Security configuration.
 * Defines the security filter chain: endpoint access rules,
 * CSRF policy, and session management.
 * Depends on {@code UserService} (as a {@code UserDetailsService}) and {@code AuthService}.
 *
 * <p>SAML note: the assertion-consumer URL ({@code /api/login/saml2/sso/{registrationId}}) is
 * exempt from CSRF because it is an unauthenticated cross-site POST from the IdP carrying no CSRF
 * token — the SAML signature and {@code InResponseTo} are its defense. That same cross-site
 * top-level POST also means the session cookie must be {@code SameSite=None} for the stashed
 * AuthnRequest to be matched; the global cookie stays {@code SameSite=Lax} (for OIDC/password/dev
     * over plain HTTP), so a SAML-enabled deployment must additionally set
     * {@code CONNEX_SESSION_COOKIE_SAME_SITE=none} with {@code CONNEX_SESSION_COOKIE_SECURE=true} and
     * end-to-end HTTPS. This is a per-environment deploy setting, not a global code change.
 *
 * Some code inspired by Springboot development tutorial videos
 */

@Configuration
@EnableWebSecurity
public class SecurityConfig {
    private static final List<String> API_CORS_ALLOWED_HEADERS = List.of(
        "Accept",
        "Accept-Language",
        "Content-Type",
        "Idempotency-Key",
        "Idempotency-Reservation",
        "X-CSRF-TOKEN",
        "X-XSRF-TOKEN",
        "X-Workspace-Id"
    );

    private static final List<String> API_CORS_ALLOWED_METHODS = List.of(
        "GET",
        "POST",
        "PUT",
        "DELETE",
        "PATCH",
        "OPTIONS"
    );

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    UrlBasedCorsConfigurationSource corsConfigurationSource(
            @Value("${connex.cors.allowed-origins}") String[] allowedOrigins) {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(List.of(allowedOrigins));
        configuration.setAllowedMethods(API_CORS_ALLOWED_METHODS);
        configuration.setAllowedHeaders(API_CORS_ALLOWED_HEADERS);
        configuration.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource(PathPatternParser.defaultInstance);
        source.registerCorsConfiguration("/api/**", configuration);
        return source;
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

    /**
     * Tracks live sessions per principal against the shared Spring Session store, so a
     * password reset can expire the sessions a user holds across all instances. Backed
     * by the JDBC session repository (V38); {@code registerNewSession} is a no-op here
     * because Spring Session owns session lifecycle.
     *
     * <p>This is the read half of the session index. The write half is
     * {@code AccountSessionIndexResolver}, which files each session under its immutable account id
     * rather than the login username Spring Session would use by default; the two must agree on the
     * key, which is why both go through {@code AccountSessionIndex}.
     */
    @Bean
    public <S extends Session> SessionRegistry sessionRegistry(
            ObjectProvider<FindByIndexNameSessionRepository<S>> sessionRepository) {
        FindByIndexNameSessionRepository<S> repository = sessionRepository.getIfAvailable();
        return repository != null
            ? new SpringSessionBackedSessionRegistry<>(repository)
            : new SessionRegistryImpl();
    }

    /**
     * Builds the application filter chain.
     *
     * <p>CSRF protection is unconditional and has no configuration switch. The token is
     * session-stored in the default repository and echoed by the SPA in a header it fetches from
     * {@code GET /api/auth/csrf}; a plain (non-XOR) handler keeps that token stable so the client
     * can cache it. Only the pre-session auth handshake, the bearer-grade native connection
     * handoff, the token-authenticated delivery routes and, when SSO is enabled, the SAML
     * assertion consumer are exempt.
     *
     * <p>The request cache is disabled. Nothing here replays a saved request — every
     * post-authentication redirect targets a trusted frontend URL — but the default
     * {@code HttpSessionRequestCache} creates a session for each rejected anonymous request just to
     * store it. Those sessions are unreachable: the entry point answers 401 rather than redirecting
     * to a login page, and a server-rendered caller discards the {@code Set-Cookie}. Left enabled,
     * every anonymous read of an authenticated endpoint would persist a session row nobody can use.
     *
     * @return the configured filter chain
     */
    @Bean
    SecurityFilterChain chain(HttpSecurity http,
            SessionRegistry sessionRegistry,
            CompositeClientRegistrationRepository compositeClientRegistrationRepository,
            SocialLoginClientRegistrations socialLoginClientRegistrations,
            DbRelyingPartyRegistrationRepository dbRelyingPartyRegistrationRepository,
            SsoAuthenticationSuccessHandler ssoAuthenticationSuccessHandler,
            SessionSecurityService sessionSecurityService,
            UserMapper userMapper,
            WebSocketSessionRegistry webSocketSessions,
            PrivilegedMfaProperties privilegedMfaProperties,
            PrivilegedAccountService privilegedAccountService,
            WebAuthnService webAuthnService,
            AuditService auditService,
            BusinessCardRateLimiter businessCardRateLimiter,
            CapabilityEntitlement capabilityEntitlement,
            WorkspaceRequestResolver workspaceRequestResolver,
            WorkspaceService workspaceService,
            WorkspaceCookie workspaceCookie,
            OneTimeLinkFlowCookie oneTimeLinkFlowCookie,
            LogoutAuditHandler logoutAuditHandler,
            LoginRateLimiter loginRateLimiter,
            ClientIpResolver clientIpResolver,
            @Value("${connex.metrics.scrape-token:}") String metricsScrapeToken,
            @Value("${connex.sso.enabled:false}") boolean ssoEnabled) throws Exception {
        boolean oauthEnabled = ssoEnabled || socialLoginClientRegistrations.anyEnabled();
        http.addFilterAfter(new AbsoluteSessionTimeoutFilter(sessionSecurityService), SecurityContextHolderFilter.class);
        http.addFilterAfter(
            new BusinessCardImportAdmissionFilter(
                businessCardRateLimiter,
                capabilityEntitlement,
                workspaceRequestResolver,
                workspaceService),
            CsrfFilter.class);
        http.addFilterAfter(
            new OneTimeLinkExchangeAdmissionFilter(loginRateLimiter, clientIpResolver),
            CsrfFilter.class);
        http.addFilterBefore(new MetricsScrapeTokenFilter(metricsScrapeToken), AuthorizationFilter.class);
        http.addFilterBefore(
            new SessionEpochFilter(userMapper, sessionSecurityService, webSocketSessions),
            AuthorizationFilter.class);
        http.addFilterAfter(
            new PrivilegedMfaEnforcementFilter(
                privilegedMfaProperties,
                privilegedAccountService,
                webAuthnService,
                sessionSecurityService,
                auditService),
            AuthorizationFilter.class);
        http.cors(withDefaults());
        http.csrf(csrf -> {
            csrf.csrfTokenRequestHandler(new HeaderOnlyCsrfTokenRequestHandler())
                .ignoringRequestMatchers(
                    "/api/auth/login", "/api/auth/register",
                    "/api/auth/forgot-password",
                    "/api/auth/webauthn/authenticate/**",
                    "/api/account/connections/native/prepare",
                    "/api/account/connections/native/complete",
                    "/api/delivery/unsubscribe/**",
                    "/api/delivery/webhooks/**",
                    "/api/document-acceptance/**",
                    "/api/document-signature/webhooks/**");
            if (ssoEnabled) {
                csrf.ignoringRequestMatchers("/api/login/saml2/sso/**");
            }
        });
        http
            .authorizeHttpRequests(auth -> {
                auth.requestMatchers(HttpMethod.GET, "/api/health/ready").permitAll()
                    .requestMatchers(HttpMethod.GET, "/api/health").permitAll()
                    .requestMatchers(HttpMethod.GET, "/api/version").permitAll()
                    .requestMatchers("/api/metrics")
                        .hasAuthority(MetricsScrapeTokenFilter.SCRAPE_AUTHORITY)
                    .requestMatchers(HttpMethod.GET, "/api/capabilities").permitAll()
                    .requestMatchers(HttpMethod.GET, "/api/mail/managed").permitAll()
                    .requestMatchers("/api/delivery/unsubscribe/**").permitAll()
                    .requestMatchers("/api/delivery/webhooks/**").permitAll()
                    .requestMatchers("/api/document-acceptance/**").permitAll()
                    .requestMatchers("/api/document-signature/webhooks/**").permitAll()
                    .requestMatchers("/api/auth/webauthn/authenticate/**").permitAll()
                    .requestMatchers("/api/auth/webauthn/**").authenticated()
                    .requestMatchers(HttpMethod.GET, "/api/auth/me").authenticated()
                    .requestMatchers(HttpMethod.POST, "/api/invites/exchange").permitAll()
                    .requestMatchers(HttpMethod.POST, "/api/invite-links/exchange").permitAll()
                    .requestMatchers(
                        HttpMethod.POST,
                        "/api/account/connections/native/prepare").permitAll()
                    .requestMatchers(
                        HttpMethod.POST,
                        "/api/account/connections/native/complete").permitAll()
                    .requestMatchers("/api/auth/**").permitAll();
                if (oauthEnabled) {
                    auth.requestMatchers("/api/oauth2/authorization/**").permitAll()
                        .requestMatchers("/api/login/oauth2/code/**").permitAll();
                }
                if (ssoEnabled) {
                    auth.requestMatchers("/api/login/saml2/**").permitAll()
                        .requestMatchers("/saml2/**").permitAll();
                }
                auth.anyRequest().authenticated();
            })
            .sessionManagement(session -> session
                .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                .maximumSessions(-1)
                .sessionRegistry(sessionRegistry)
                .expiredSessionStrategy(event -> event.getResponse().setStatus(HttpServletResponse.SC_UNAUTHORIZED))
            )
            .headers(headers -> headers
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
            )
            .logout(logout -> logout
                .logoutUrl("/api/auth/logout")
                .addLogoutHandler(logoutAuditHandler)
                .invalidateHttpSession(true)
                .clearAuthentication(true)
                .deleteCookies("JSESSIONID")
                .logoutSuccessHandler((req, res, auth) -> {
                    workspaceCookie.clear(res);
                    oneTimeLinkFlowCookie.clearBrowserBinding(res);
                    res.setStatus(200);
                })
            )
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED))
            )
            .requestCache(RequestCacheConfigurer::disable);
        if (oauthEnabled) {
            http
                .oauth2Login(o -> o
                    .clientRegistrationRepository(compositeClientRegistrationRepository)
                    .authorizationEndpoint(a -> a.baseUri("/api/oauth2/authorization"))
                    .redirectionEndpoint(r -> r.baseUri("/api/login/oauth2/code/*"))
                    .successHandler(ssoAuthenticationSuccessHandler)
                    .failureHandler((rq, rs, ex) -> rs.sendRedirect("/auth/login?sso_error=1"))
                );
        }
        if (ssoEnabled) {
            http.saml2Login(s -> s
                .relyingPartyRegistrationRepository(dbRelyingPartyRegistrationRepository)
                .loginProcessingUrl("/api/login/saml2/sso/{registrationId}")
                .authenticationManager(samlAuthenticationManager())
                .successHandler(ssoAuthenticationSuccessHandler)
                .failureHandler((rq, rs, ex) -> rs.sendRedirect("/auth/login?sso_error=1"))
            );
        }
        return http.build();
    }

    @Bean
    FilterRegistrationBean<CorrelationIdFilter> correlationIdFilterRegistration() {
        FilterRegistrationBean<CorrelationIdFilter> registration =
            new FilterRegistrationBean<>(new CorrelationIdFilter());
        registration.setOrder(OrderedFormContentFilter.DEFAULT_ORDER - 2);
        return registration;
    }

    @Bean
    FilterRegistrationBean<ApiRequestBodySizeFilter> apiRequestBodySizeFilterRegistration(
            RequestBodySizeProperties requestBodySizeProperties) {
        FilterRegistrationBean<ApiRequestBodySizeFilter> registration =
            new FilterRegistrationBean<>(new ApiRequestBodySizeFilter(requestBodySizeProperties));
        registration.setOrder(OrderedFormContentFilter.DEFAULT_ORDER - 1);
        return registration;
    }

    /**
     * SAML authentication manager that rejects unsolicited (IdP-initiated) responses. The default
     * SAML response validator treats a {@code Response} with no {@code InResponseTo} as valid, which
     * would allow a captured, validly-signed assertion to be replayed into a victim's browser
     * (SAML login-CSRF). Requiring {@code InResponseTo} binds every accepted response to an
     * AuthnRequest this SP actually initiated.
     * @return an authentication manager over an {@link OpenSaml5AuthenticationProvider} that requires solicited responses
     */
    private AuthenticationManager samlAuthenticationManager() {
        OpenSaml5AuthenticationProvider provider = new OpenSaml5AuthenticationProvider();
        Converter<OpenSaml5AuthenticationProvider.ResponseToken, Saml2ResponseValidatorResult> defaultValidator =
                OpenSaml5AuthenticationProvider.createDefaultResponseValidator();
        provider.setResponseValidator(token -> {
            Saml2ResponseValidatorResult result = defaultValidator.convert(token);
            String inResponseTo = token.getResponse().getInResponseTo();
            if (inResponseTo == null || inResponseTo.isBlank()) {
                Saml2Error error = new Saml2Error("unsolicited_response",
                        "Unsolicited SAML responses are not accepted");
                return result == null ? Saml2ResponseValidatorResult.failure(error) : result.concat(error);
            }
            return result;
        });
        return new ProviderManager(provider);
    }

    /**
     * Customizes OIDC id-token validation so Microsoft's multi-tenant {@code common} endpoint is
     * accepted: its tokens carry a per-tenant issuer ({@code .../{tenantId}/v2.0}) that the default
     * fixed-issuer validator would reject. Microsoft id-tokens are still validated for signature
     * (via the configured JWK set), expiry, a genuine Microsoft issuer, and our own audience; every
     * other registration keeps the default strict OIDC validator.
     * @return the id-token decoder factory
     */
    @Bean
    OidcIdTokenDecoderFactory idTokenDecoderFactory() {
        OidcIdTokenDecoderFactory factory = new OidcIdTokenDecoderFactory();
        factory.setJwtValidatorFactory(registration -> {
            if (SocialLoginClientRegistrations.MICROSOFT.equals(registration.getRegistrationId())) {
                return new DelegatingOAuth2TokenValidator<>(
                        new JwtTimestampValidator(),
                        new JwtClaimValidator<String>(JwtClaimNames.ISS, SecurityConfig::isMicrosoftIssuer),
                        new JwtClaimValidator<Object>(JwtClaimNames.AUD,
                                aud -> audienceContains(aud, registration.getClientId())),
                        new JwtClaimValidator<String>("azp",
                                azp -> azp == null || azp.equals(registration.getClientId())));
            }
            return new OidcIdTokenValidator(registration);
        });
        return factory;
    }

    private static boolean isMicrosoftIssuer(String issuer) {
        return issuer != null && issuer.startsWith("https://login.microsoftonline.com/")
                && issuer.endsWith("/v2.0");
    }

    private static boolean audienceContains(Object audience, String clientId) {
        if (audience instanceof java.util.Collection<?> values) {
            return values.contains(clientId);
        }
        return clientId.equals(audience);
    }
}
