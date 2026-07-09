package ooo.klae.connex.backend.config;

import static org.springframework.security.config.Customizer.withDefaults;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
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
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.session.SessionRegistryImpl;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.session.security.SpringSessionBackedSessionRegistry;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.util.pattern.PathPatternParser;

import jakarta.servlet.http.HttpServletResponse;

import ooo.klae.connex.backend.sso.DbRelyingPartyRegistrationRepository;
import ooo.klae.connex.backend.sso.SsoAuthenticationSuccessHandler;
import ooo.klae.connex.backend.services.SessionSecurityService;

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
     * password reset can expire every session a user holds across all instances. Backed
     * by the JDBC session repository (V38); {@code registerNewSession} is a no-op here
     * because Spring Session owns session lifecycle.
     */
    @Bean
    public <S extends Session> SessionRegistry sessionRegistry(
            ObjectProvider<FindByIndexNameSessionRepository<S>> sessionRepository) {
        FindByIndexNameSessionRepository<S> repository = sessionRepository.getIfAvailable();
        return repository != null
            ? new SpringSessionBackedSessionRegistry<>(repository)
            : new SessionRegistryImpl();
    }

    @Bean
    SecurityFilterChain chain(HttpSecurity http,
            SessionRegistry sessionRegistry,
            CompositeClientRegistrationRepository compositeClientRegistrationRepository,
            SocialLoginClientRegistrations socialLoginClientRegistrations,
            DbRelyingPartyRegistrationRepository dbRelyingPartyRegistrationRepository,
            SsoAuthenticationSuccessHandler ssoAuthenticationSuccessHandler,
            RequestBodySizeProperties requestBodySizeProperties,
            SessionSecurityService sessionSecurityService,
            @Value("${connex.security.csrf-enabled:true}") boolean csrfEnabled,
            @Value("${connex.sso.enabled:false}") boolean ssoEnabled) throws Exception {
        boolean oauthEnabled = ssoEnabled || socialLoginClientRegistrations.anyEnabled();
        http.addFilterBefore(new ApiRequestBodySizeFilter(requestBodySizeProperties), SecurityContextHolderFilter.class);
        http.addFilterAfter(new AbsoluteSessionTimeoutFilter(sessionSecurityService), SecurityContextHolderFilter.class);
        http.cors(withDefaults());
        if (csrfEnabled) {
            // Session-stored token (default repo), echoed by the SPA in a header it fetches from
            // GET /api/auth/csrf. A plain (non-XOR) handler keeps the token stable so the client can
            // cache it. The auth handshake is exempt since there is no session to protect pre-login.
            http.csrf(csrf -> {
                csrf.csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
                    .ignoringRequestMatchers(
                        "/api/auth/login", "/api/auth/register", "/api/auth/logout",
                        "/api/auth/forgot-password", "/api/auth/reset-password",
                        "/api/auth/webauthn/authenticate/**");
                if (oauthEnabled) {
                    csrf.ignoringRequestMatchers("/api/auth/sso/link/confirm");
                }
                if (ssoEnabled) {
                    csrf.ignoringRequestMatchers("/api/login/saml2/sso/**");
                }
            });
        } else {
            http.csrf(AbstractHttpConfigurer::disable);
        }
        http
            .authorizeHttpRequests(auth -> {
                auth.requestMatchers("/api/auth/webauthn/authenticate/**").permitAll()
                    .requestMatchers("/api/auth/webauthn/**").authenticated()
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
                .contentSecurityPolicy(csp -> csp
                    .policyDirectives("default-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'")
                )
            )
            .logout(logout -> logout
                .logoutUrl("/api/auth/logout")
                .invalidateHttpSession(true)
                .clearAuthentication(true)
                .deleteCookies("JSESSIONID")
                .logoutSuccessHandler((req, res, auth) -> res.setStatus(200))
            );
        if (oauthEnabled) {
            http
                .oauth2Login(o -> o
                    .clientRegistrationRepository(compositeClientRegistrationRepository)
                    .authorizationEndpoint(a -> a.baseUri("/api/oauth2/authorization"))
                    .redirectionEndpoint(r -> r.baseUri("/api/login/oauth2/code/*"))
                    .successHandler(ssoAuthenticationSuccessHandler)
                    .failureHandler((rq, rs, ex) -> rs.sendRedirect("/auth/login?sso_error=1"))
                )
                .exceptionHandling(ex -> ex
                    .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED))
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
