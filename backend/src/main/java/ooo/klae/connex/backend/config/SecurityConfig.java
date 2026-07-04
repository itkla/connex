package ooo.klae.connex.backend.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.saml2.core.Saml2Error;
import org.springframework.security.saml2.core.Saml2ResponseValidatorResult;
import org.springframework.security.saml2.provider.service.authentication.OpenSaml5AuthenticationProvider;
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
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.session.security.SpringSessionBackedSessionRegistry;

import jakarta.servlet.http.HttpServletResponse;

import ooo.klae.connex.backend.sso.DbClientRegistrationRepository;
import ooo.klae.connex.backend.sso.DbRelyingPartyRegistrationRepository;
import ooo.klae.connex.backend.sso.SsoAuthenticationSuccessHandler;

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
 * {@code server.servlet.session.cookie.same-site: none} with {@code CONNEX_SESSION_COOKIE_SECURE=true}
 * and end-to-end HTTPS. This is a per-environment deploy setting, not a global code change.
 *
 * Some code inspired by Springboot development tutorial videos
 */

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
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
            DbClientRegistrationRepository dbClientRegistrationRepository,
            DbRelyingPartyRegistrationRepository dbRelyingPartyRegistrationRepository,
            SsoAuthenticationSuccessHandler ssoAuthenticationSuccessHandler,
            @Value("${connex.security.csrf-enabled:true}") boolean csrfEnabled,
            @Value("${connex.sso.enabled:false}") boolean ssoEnabled) throws Exception {
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
                if (ssoEnabled) {
                    csrf.ignoringRequestMatchers("/api/auth/sso/link/confirm", "/api/login/saml2/sso/**");
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
                if (ssoEnabled) {
                    auth.requestMatchers("/api/oauth2/authorization/**").permitAll()
                        .requestMatchers("/api/login/oauth2/code/**").permitAll()
                        .requestMatchers("/api/login/saml2/**").permitAll()
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
        if (ssoEnabled) {
            http
                .oauth2Login(o -> o
                    .clientRegistrationRepository(dbClientRegistrationRepository)
                    .authorizationEndpoint(a -> a.baseUri("/api/oauth2/authorization"))
                    .redirectionEndpoint(r -> r.baseUri("/api/login/oauth2/code/*"))
                    .successHandler(ssoAuthenticationSuccessHandler)
                    .failureHandler((rq, rs, ex) -> rs.sendRedirect("/auth/login?sso_error=1"))
                )
                .saml2Login(s -> s
                    .relyingPartyRegistrationRepository(dbRelyingPartyRegistrationRepository)
                    .loginProcessingUrl("/api/login/saml2/sso/{registrationId}")
                    .authenticationManager(samlAuthenticationManager())
                    .successHandler(ssoAuthenticationSuccessHandler)
                    .failureHandler((rq, rs, ex) -> rs.sendRedirect("/auth/login?sso_error=1"))
                )
                .exceptionHandling(ex -> ex
                    .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED))
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
}
