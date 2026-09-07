package ooo.klae.connex.backend.signature;

import org.springframework.boot.servlet.filter.OrderedFormContentFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import ooo.klae.connex.backend.config.DocumentAcceptanceAdmissionFilter;
import ooo.klae.connex.backend.util.ClientIpResolver;

/**
 * Registers the public acceptance admission filter beside the collaborators it needs.
 *
 * <p>This deliberately does not live in {@code SecurityConfig}. Controller slice tests import that
 * configuration without component-scanning {@code signature} or {@code util}, so a registration
 * there fails every slice context with {@code NoSuchBeanDefinitionException} — including tests for
 * controllers unrelated to signatures. Keeping the registration in the package that owns the
 * limiter means the filter is present in any context that scans this package and absent from
 * slices that legitimately do not exercise the acceptance routes.
 */
@Configuration
public class DocumentAcceptanceAdmissionConfiguration {

    @Bean
    FilterRegistrationBean<DocumentAcceptanceAdmissionFilter> documentAcceptanceAdmissionFilterRegistration(
            DocumentAcceptanceRateLimiter rateLimiter,
            ClientIpResolver clientIpResolver) {
        FilterRegistrationBean<DocumentAcceptanceAdmissionFilter> registration =
            new FilterRegistrationBean<>(
                new DocumentAcceptanceAdmissionFilter(rateLimiter, clientIpResolver));
        registration.setOrder(OrderedFormContentFilter.DEFAULT_ORDER - 3);
        return registration;
    }
}
