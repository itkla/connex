package ooo.klae.connex.backend.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.session.SessionRegistryImpl;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.session.HttpSessionEventPublisher;

import jakarta.servlet.http.HttpServletResponse;

/**
 * Spring Security configuration.
 * Defines the security filter chain: endpoint access rules,
 * CSRF policy, and session management.
 * Depends on {@code UserService} (as a {@code UserDetailsService}) and {@code AuthService}.
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
     * Tracks live sessions per principal so a password reset can expire every
     * session a user holds. In-memory and single-JVM, matching the current
     * Tomcat session model; sessions are registered explicitly at login.
     */
    @Bean
    public SessionRegistry sessionRegistry() {
        return new SessionRegistryImpl();
    }

    /**
     * Publishes servlet session lifecycle events so {@link SessionRegistry}
     * drops entries when a session is destroyed or expires.
     */
    @Bean
    public HttpSessionEventPublisher httpSessionEventPublisher() {
        return new HttpSessionEventPublisher();
    }

    @Bean
    SecurityFilterChain chain(HttpSecurity http,
            SessionRegistry sessionRegistry,
            @Value("${connex.security.csrf-enabled:true}") boolean csrfEnabled) throws Exception {
        if (csrfEnabled) {
            // Session-stored token (default repo), echoed by the SPA in a header it fetches from
            // GET /api/auth/csrf. A plain (non-XOR) handler keeps the token stable so the client can
            // cache it. The auth handshake is exempt since there is no session to protect pre-login.
            http.csrf(csrf -> csrf
                .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
                .ignoringRequestMatchers(
                    "/api/auth/login", "/api/auth/register", "/api/auth/logout",
                    "/api/auth/forgot-password", "/api/auth/reset-password",
                    "/api/auth/webauthn/authenticate/**"));
        } else {
            http.csrf(AbstractHttpConfigurer::disable);
        }
        http
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/auth/webauthn/authenticate/**").permitAll()
                .requestMatchers("/api/auth/webauthn/**").authenticated()
                .requestMatchers("/api/auth/**").permitAll()
                .anyRequest().authenticated()
            )
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
        return http.build();
    }
}
