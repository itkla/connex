package ooo.klae.connex.backend.integration;

import java.sql.SQLException;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.test.context.DynamicPropertyRegistrar;

/**
 * Owns a catalog per trajectory context so global readiness cannot observe another suite's fixtures.
 * Flyway and production provisioning establish all authority inside this catalog.
 */
@TestConfiguration(proxyBeanMethods = false)
public class ScriptedTrajectoryDatabaseConfiguration {

    /** Drops the disposable catalog when its owning application context closes. */
    @Bean(destroyMethod = "close")
    MySqlScratchCatalog trajectoryCatalog(Environment environment) throws SQLException {
        return MySqlScratchCatalog.create(
                environment.getRequiredProperty("spring.datasource.url"),
                environment.getRequiredProperty("spring.datasource.username"),
                environment.getRequiredProperty("spring.datasource.password"),
                "connex_trajectory_", "utf8mb4", "utf8mb4_0900_ai_ci");
    }

    /** Registers the isolated URL before datasource and migration beans are initialized. */
    @Bean
    DynamicPropertyRegistrar trajectoryDatabaseProperties(MySqlScratchCatalog trajectoryCatalog) {
        return registry -> registry.add("spring.datasource.url", trajectoryCatalog::url);
    }
}
