package ooo.klae.connex.backend.config;

import java.util.List;
import java.util.Locale;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.i18n.AcceptHeaderLocaleResolver;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.tenant.TenantResolutionInterceptor;

/**
 * Spring MVC configuration.
 * Registers the tenant-resolution interceptor that pins the active workspace
 * for each authenticated request, including post-security credential binding for {@code /api/v1}.
 *
 * <p>Interceptors run in the {@code DispatcherServlet}, after whichever Spring Security chain
 * served the request, so the excluded paths are excluded here as well as in their chains. The
 * browser CSP collector is on that list: {@link CspReportSecurityConfig} keeps it off the session,
 * and this keeps the tenant interceptor from resolving a security context on it regardless of how
 * that chain is later configured.
 */

@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {
    private final TenantResolutionInterceptor tenantResolutionInterceptor;

    @Bean
    LocaleResolver localeResolver() {
        AcceptHeaderLocaleResolver resolver = new AcceptHeaderLocaleResolver();
        resolver.setSupportedLocales(List.of(Locale.ENGLISH, Locale.JAPANESE));
        resolver.setDefaultLocale(Locale.ENGLISH);
        return resolver;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(tenantResolutionInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns(
                        "/api/auth/**", "/api/mail/managed", "/api/health", "/api/health/ready",
                        "/api/metrics", "/api/version", "/api/capabilities", "/api/csp-reports");
    }
}
