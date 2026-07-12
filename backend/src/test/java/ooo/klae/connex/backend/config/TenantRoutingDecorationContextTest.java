package ooo.klae.connex.backend.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.zaxxer.hikari.HikariDataSource;

import ooo.klae.connex.backend.tenant.TenantRoutingDataSource;

/**
 * Boots a real context with {@code catalog-per-placement} enabled and TWO
 * Hikari pools — the shape the control-plane split introduces — and proves the
 * decoration lands exactly where intended: the tenant pool (bean name
 * {@code dataSource}) is wrapped by {@link TenantRoutingDataSource} and the
 * second pool is not. Context startup itself exercises the two-direction
 * verifier: it would refuse to start if either direction failed.
 */
@SpringBootTest
class TenantRoutingDecorationContextTest {

    static final String CONTROL_POOL = "controlPlanePool";

    private static final Pattern JDBC_URL_DATABASE = Pattern.compile("^jdbc:mysql://[^/]+/([^?/]+)");

    @Autowired private ApplicationContext context;

    @DynamicPropertySource
    static void routingProperties(DynamicPropertyRegistry registry) {
        registry.add("connex.tenancy.routing.mode", () -> "catalog-per-placement");
        registry.add("connex.tenancy.routing.default-catalog", TenantRoutingDecorationContextTest::databaseFromEnv);
    }

    private static String databaseFromEnv() {
        String url = System.getenv("CONNEX_DB_URL");
        if (url == null) {
            return "connexdb";
        }
        Matcher matcher = JDBC_URL_DATABASE.matcher(url);
        return matcher.find() ? matcher.group(1) : "connexdb";
    }

    /**
     * Declares both pools explicitly (auto-configuration backs off once a
     * DataSource bean exists), mirroring the future two-pool wiring. Return
     * types are {@code DataSource} because the tenant pool's runtime type
     * changes to {@link TenantRoutingDataSource} after decoration.
     */
    @TestConfiguration
    static class TwoPools {

        @Bean
        @Primary
        DataSource dataSource(Environment environment) {
            return pool(environment, "tenant-pool");
        }

        @Bean(name = CONTROL_POOL)
        DataSource controlPlanePool(Environment environment) {
            return pool(environment, "control-pool");
        }

        private HikariDataSource pool(Environment environment, String poolName) {
            HikariDataSource hikari = new HikariDataSource();
            hikari.setJdbcUrl(environment.getProperty("spring.datasource.url"));
            hikari.setUsername(environment.getProperty("spring.datasource.username"));
            hikari.setPassword(environment.getProperty("spring.datasource.password"));
            hikari.setMaximumPoolSize(2);
            hikari.setPoolName(poolName);
            return hikari;
        }
    }

    @Test
    void tenantPoolIsWrappedByTheRoutingDecorator() {
        assertTrue(context.getBean("dataSource", DataSource.class) instanceof TenantRoutingDataSource,
            "the primary datasource must be tenant-routed when catalog-per-placement is enabled");
    }

    @Test
    void otherPoolsAreLeftUndecorated() {
        assertFalse(context.getBean(CONTROL_POOL, DataSource.class) instanceof TenantRoutingDataSource,
            "only the tenant pool may be routed; a routed control-plane pool would make placement lookups "
                + "self-referential");
    }
}
