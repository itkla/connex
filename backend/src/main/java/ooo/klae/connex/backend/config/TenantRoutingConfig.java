package ooo.klae.connex.backend.config;

import java.sql.SQLException;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.sql.DataSource;

import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
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

    /**
     * The tenant pool's bean name — Spring Boot's auto-configured primary
     * datasource. Decoration targets this bean by identity, not by type: when
     * the control-plane split (#440 increment 3) adds a second pool, a
     * type-based hook would tenant-route the control-plane pool too, making
     * placement lookups self-referential.
     */
    static final String TENANT_DATASOURCE_BEAN = "dataSource";

    private static final Pattern JDBC_URL_DATABASE = Pattern.compile("^jdbc:mysql://[^/]+/([^?/]+)");

    @Bean
    static BeanPostProcessor tenantRoutingDataSourceDecorator(
            ObjectProvider<TenantRoutingProperties> properties,
            ObjectProvider<TenantContext> tenantContext) {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (!(bean instanceof HikariDataSource hikari) || !TENANT_DATASOURCE_BEAN.equals(beanName)) {
                    return bean;
                }
                return decorate(hikari, properties.getObject(), tenantContext.getObject());
            }
        };
    }

    /**
     * Fails startup unless decoration landed exactly where intended: the
     * tenant pool ({@link #TENANT_DATASOURCE_BEAN}) must be wrapped by
     * {@link TenantRoutingDataSource}, and no other datasource bean may be —
     * an unwrapped tenant pool serves every dedicated org silently unrouted,
     * and a wrapped control-plane pool would tenant-route placement lookups
     * themselves. Refusing to start is fail-closed in both directions.
     */
    @Bean
    static SmartInitializingSingleton tenantRoutingDecorationVerifier(ListableBeanFactory beanFactory) {
        return () -> {
            Map<String, DataSource> dataSources = beanFactory.getBeansOfType(DataSource.class);
            DataSource tenantPool = dataSources.get(TENANT_DATASOURCE_BEAN);
            if (tenantPool == null || !routes(tenantPool)) {
                throw new IllegalStateException(
                    "connex.tenancy.routing.mode=catalog-per-placement is enabled but the '" + TENANT_DATASOURCE_BEAN
                        + "' datasource is not wrapped by TenantRoutingDataSource; refusing to start rather than "
                        + "serve tenants unrouted");
            }
            for (Map.Entry<String, DataSource> entry : dataSources.entrySet()) {
                if (!TENANT_DATASOURCE_BEAN.equals(entry.getKey()) && routes(entry.getValue())) {
                    throw new IllegalStateException(
                        "Datasource bean '" + entry.getKey() + "' is tenant-routed but only '"
                            + TENANT_DATASOURCE_BEAN + "' may be; a routed control-plane pool would make "
                            + "placement lookups self-referential");
                }
            }
        };
    }

    private static boolean routes(DataSource dataSource) {
        try {
            return dataSource instanceof TenantRoutingDataSource
                || dataSource.isWrapperFor(TenantRoutingDataSource.class);
        } catch (SQLException e) {
            return false;
        }
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
        String urlDatabase = databaseFromJdbcUrl(hikari.getJdbcUrl());
        if (urlDatabase != null && !urlDatabase.equals(defaultCatalog)) {
            throw new IllegalStateException(
                "connex.tenancy.routing.default-catalog '" + defaultCatalog + "' does not match the database '"
                    + urlDatabase + "' in the JDBC URL; arming the pool catalog would silently repoint every "
                    + "connection at the wrong database");
        }
        String existingCatalog = hikari.getCatalog();
        if (existingCatalog == null || existingCatalog.isBlank()) {
            hikari.setCatalog(defaultCatalog);
        } else if (!existingCatalog.equals(defaultCatalog)) {
            throw new IllegalStateException(
                "Hikari pool catalog '" + existingCatalog + "' conflicts with connex.tenancy.routing.default-catalog '"
                    + defaultCatalog + "'; the pool dirty-bit reset and the routing decorator would disagree");
        }
        return new TenantRoutingDataSource(hikari, tenantContext, defaultCatalog, hikari::evictConnection);
    }

    private static String databaseFromJdbcUrl(String jdbcUrl) {
        if (jdbcUrl == null) {
            return null;
        }
        Matcher matcher = JDBC_URL_DATABASE.matcher(jdbcUrl);
        return matcher.find() ? matcher.group(1) : null;
    }
}
