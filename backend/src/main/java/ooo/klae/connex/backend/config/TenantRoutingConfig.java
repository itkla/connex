package ooo.klae.connex.backend.config;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.zaxxer.hikari.HikariDataSource;

import ooo.klae.connex.backend.tenant.TenantContext;
import ooo.klae.connex.backend.tenant.TenantRoutingDataSource;
import ooo.klae.connex.backend.tenant.TenantRoutingProperties;

/**
 * Installs the {@link TenantRoutingDataSource} decorator around the
 * auto-configured Hikari pool when {@code connex.tenancy.routing.mode} is
 * {@code catalog-per-placement}. The same hook arms HikariCP's own dirty-bit
 * catalog reset by giving the pool an explicit default catalog (Hikari only
 * restores a dirtied catalog on return when one is configured). In the default
 * {@code single-database} mode this configuration is inert and the connection
 * path is byte-identical to a deployment without routing.
 */
@Configuration
@ConditionalOnProperty(name = "connex.tenancy.routing.mode", havingValue = "catalog-per-placement")
public class TenantRoutingConfig {

    @Bean
    static BeanPostProcessor tenantRoutingDataSourceDecorator(
            ObjectProvider<TenantRoutingProperties> properties,
            ObjectProvider<TenantContext> tenantContext) {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (!(bean instanceof HikariDataSource hikari)) {
                    return bean;
                }
                return decorate(hikari, properties.getObject(), tenantContext.getObject());
            }
        };
    }

    /**
     * Validates the routing configuration, arms the pool-level default catalog,
     * and wraps the pool. Public so the adversarial integration test can
     * exercise the exact production wiring against a real pool.
     */
    public static TenantRoutingDataSource decorate(HikariDataSource hikari, TenantRoutingProperties properties,
            TenantContext tenantContext) {
        String defaultCatalog = properties.getDefaultCatalog();
        if (defaultCatalog == null || defaultCatalog.isBlank()) {
            throw new IllegalStateException(
                "connex.tenancy.routing.default-catalog is required when mode=catalog-per-placement");
        }
        if (hikari.getCatalog() == null) {
            hikari.setCatalog(defaultCatalog);
        }
        return new TenantRoutingDataSource(hikari, tenantContext, defaultCatalog, hikari::evictConnection);
    }
}
