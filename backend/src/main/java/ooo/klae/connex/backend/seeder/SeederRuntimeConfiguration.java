package ooo.klae.connex.backend.seeder;

import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Installs the guarded Flyway and startup behavior only for explicit seeder invocations.
 */
@Configuration(proxyBeanMethods = false)
@Profile("seeder")
@ConditionalOnProperty(prefix = "connex.seeder", name = "enabled", havingValue = "true")
public class SeederRuntimeConfiguration {

    @Bean
    FlywayMigrationStrategy seederFlywayMigrationStrategy(
            SeederGuard guard,
            MigrationTimingReporter migrationTimingReporter) {
        return new SeederFlywayMigrationStrategy(guard, migrationTimingReporter);
    }

    @Bean
    SmartInitializingSingleton seederGuardVerifier(SeederGuard guard) {
        return guard::verify;
    }
}
