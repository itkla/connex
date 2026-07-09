package ooo.klae.connex.backend.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.tenant.TenantResolutionInterceptor;

/**
 * Spring MVC configuration.
 * Registers the tenant-resolution interceptor that pins the active workspace
 * for each authenticated request.
 */

@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {
    private final TenantResolutionInterceptor tenantResolutionInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(tenantResolutionInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns("/api/auth/**");
    }
}
