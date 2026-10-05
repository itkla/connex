package ooo.klae.connex.backend.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.context.annotation.Primary;
import org.springframework.mock.env.MockEnvironment;

import com.zaxxer.hikari.HikariDataSource;

import ooo.klae.connex.backend.tenant.TenantContext;
import ooo.klae.connex.backend.tenant.TenantRoutingDataSource;
import ooo.klae.connex.backend.tenant.TenantRoutingProperties;

/** Exercises production decoration and startup verification with unopened pools. */
class TenantRoutingDecorationContextTest {
    private static final String CONTROL_POOL = "controlPlanePool";
    private static final String CATALOG = "connex_Q7a";
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withUserConfiguration(RoutingDependencies.class, TenantRoutingConfig.class)
        .withPropertyValues(
            "connex.tenancy.routing.mode=catalog-per-placement",
            "connex.tenancy.routing.default-catalog=" + CATALOG);

    @Test
    void tenantPoolIsWrappedByTheRoutingDecorator() {
        contextRunner.withUserConfiguration(TwoPools.class).run(context -> {
            assertThat(context).hasNotFailed();
            DataSource tenant = context.getBean(TenantRoutingConfig.TENANT_DATASOURCE_BEAN, DataSource.class);
            assertTrue(tenant instanceof TenantRoutingDataSource,
                "the primary datasource must be tenant-routed when catalog-per-placement is enabled");
            assertSame(tenant, context.getBean(DataSource.class));
            TenantRoutingProperties properties = context.getBean(TenantRoutingProperties.class);
            assertTrue(properties.isCatalogPerPlacement());
            assertEquals(CATALOG, properties.getDefaultCatalog());
        });
    }

    @Test
    void otherPoolsAreLeftUndecorated() {
        contextRunner.withUserConfiguration(TwoPools.class).run(context -> {
            assertThat(context).hasNotFailed();
            assertFalse(context.getBean(CONTROL_POOL, DataSource.class) instanceof TenantRoutingDataSource,
                "only the tenant pool may be routed; a routed control-plane pool would make placement lookups "
                    + "self-referential");
        });
    }

    @Test
    void productionConfigurationIsDiscoveredWhenRoutingIsEnabled() {
        MockEnvironment environment = new MockEnvironment()
            .withProperty("connex.tenancy.routing.mode", "catalog-per-placement");
        ClassPathScanningCandidateComponentProvider scanner =
            new ClassPathScanningCandidateComponentProvider(true, environment);
        scanner.addExcludeFilter((metadata, factory) -> !metadata.getClassMetadata().getClassName()
            .equals(TenantRoutingConfig.class.getName()));

        assertThat(scanner.findCandidateComponents(TenantRoutingConfig.class.getPackageName()))
            .extracting(definition -> definition.getBeanClassName())
            .containsExactly(TenantRoutingConfig.class.getName());
    }

    @Test
    void singleDatabaseModeLeavesBothPoolsAndVerifierInactive() {
        contextRunner.withUserConfiguration(TwoPools.class)
            .withPropertyValues("connex.tenancy.routing.mode=single-database")
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context.getBean(DataSource.class)).isInstanceOf(HikariDataSource.class);
                assertThat(context.getBean(CONTROL_POOL)).isInstanceOf(HikariDataSource.class);
                assertFalse(context.containsBean("tenantRoutingDataSourceDecorator"));
                assertFalse(context.containsBean("tenantRoutingDecorationVerifier"));
            });
    }

    @Test
    void rejectsAnUnwrappedTenantPool() {
        contextRunner.withUserConfiguration(UnwrappedTenantPool.class).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasMessageContaining(
                "'dataSource' datasource is not wrapped by TenantRoutingDataSource");
        });
    }

    @Test
    void rejectsAnUnroutedPrimaryPool() {
        contextRunner.withUserConfiguration(WrongPrimaryPool.class).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasMessageContaining(
                "The routed 'dataSource' datasource is not the primary DataSource");
        });
    }

    @Test
    void rejectsARoutedSecondaryPool() {
        contextRunner.withUserConfiguration(RoutedSecondaryPool.class).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasMessageContaining(
                "Datasource bean 'controlPlanePool' is tenant-routed but only 'dataSource' may be");
        });
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableConfigurationProperties(TenantRoutingProperties.class)
    static class RoutingDependencies {
        @Bean
        TenantContext tenantContext() {
            return new TenantContext();
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TwoPools {
        @Bean
        @Primary
        DataSource dataSource() {
            return pool("tenant-pool");
        }

        @Bean(name = CONTROL_POOL)
        DataSource controlPlanePool() {
            return pool("control-pool");
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class UnwrappedTenantPool {
        @Bean
        @Primary
        DataSource dataSource() {
            return mock(DataSource.class);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class WrongPrimaryPool {
        @Bean
        DataSource dataSource() {
            return pool("tenant-pool");
        }

        @Bean(name = CONTROL_POOL)
        @Primary
        DataSource controlPlanePool() {
            return pool("control-pool");
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class RoutedSecondaryPool {
        @Bean
        @Primary
        DataSource dataSource() {
            return pool("tenant-pool");
        }

        @Bean(name = CONTROL_POOL)
        DataSource controlPlanePool(TenantContext tenantContext) {
            HikariDataSource pool = pool("control-pool");
            return new TenantRoutingDataSource(pool, tenantContext, CATALOG, pool::evictConnection);
        }
    }

    private static HikariDataSource pool(String poolName) {
        HikariDataSource pool = new HikariDataSource();
        pool.setJdbcUrl("jdbc:mysql://127.0.0.1:3433/" + CATALOG);
        pool.setMaximumPoolSize(2);
        pool.setPoolName(poolName);
        return pool;
    }
}
