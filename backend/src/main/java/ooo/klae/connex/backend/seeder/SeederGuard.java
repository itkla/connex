package ooo.klae.connex.backend.seeder;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.config.DeploymentProperties;
import ooo.klae.connex.backend.seeder.SeederStartupConfigurationValidator.JdbcTarget;
import ooo.klae.connex.backend.seeder.SeederStartupConfigurationValidator.ValidatedConfiguration;

/**
 * Revalidates effective seeder datasources before Flyway or fixture writers may mutate them.
 */
@Component
@RequiredArgsConstructor(onConstructor_ = @Autowired)
public class SeederGuard {

    private static final String PRODUCTION_DATABASE = "connex_pub";
    private static final String JDBC_TARGET_VERIFICATION_FAILURE =
        "could not verify effective JDBC target";

    private final Environment environment;
    private final DataSource dataSource;

    SeederGuard(
            Environment environment,
            DeploymentProperties deploymentProperties,
            SeederProperties properties,
            DataSource dataSource) {
        this(environment, dataSource);
    }

    /**
     * Verifies configured and effective application datasource targets.
     */
    public void verify() {
        verify(dataSource);
    }

    /**
     * Verifies configured targets plus every supplied effective datasource.
     *
     * @param additionalDataSources Flyway or other effective datasources to revalidate
     */
    public synchronized void verify(DataSource... additionalDataSources) {
        ValidatedConfiguration configuration =
            SeederStartupConfigurationValidator.validate(environment);
        Set<DataSource> effectiveDataSources =
            Collections.newSetFromMap(new IdentityHashMap<>());
        effectiveDataSources.add(dataSource);
        if (additionalDataSources != null) {
            Collections.addAll(effectiveDataSources, additionalDataSources);
        }
        for (DataSource effectiveDataSource : effectiveDataSources) {
            verifyMetadata(effectiveDataSource, configuration);
        }
    }

    static void verifyJdbcUrl(String url, boolean allowRemoteHost) {
        SeederStartupConfigurationValidator.verifyJdbcUrl(url, allowRemoteHost);
    }

    private static void verifyMetadata(
            DataSource effectiveDataSource,
            ValidatedConfiguration configuration) {
        if (effectiveDataSource == null) {
            throw SeederStartupConfigurationValidator.refused(
                "effective datasource is unavailable"
            );
        }
        try (Connection connection = effectiveDataSource.getConnection()) {
            JdbcTarget metadataTarget = SeederStartupConfigurationValidator.verifiedTarget(
                connection.getMetaData().getURL(),
                "effective datasource metadata URL",
                configuration.allowRemoteHost()
            );
            if (!configuration.target().matches(metadataTarget)) {
                throw SeederStartupConfigurationValidator.refused(
                    "effective datasource metadata URL disagrees with spring.datasource.url"
                );
            }
            verifyEffectiveDatabase(
                effectiveDatabase(connection),
                configuration.target().database()
            );
        } catch (SQLException exception) {
            throw SeederStartupConfigurationValidator.refused(
                JDBC_TARGET_VERIFICATION_FAILURE
            );
        } catch (RuntimeException exception) {
            throw SeederStartupConfigurationValidator.cleanRefusal(
                exception,
                JDBC_TARGET_VERIFICATION_FAILURE
            );
        }
    }

    private static String effectiveDatabase(Connection connection) throws SQLException {
        String catalog = connection.getCatalog();
        if (StringUtils.hasText(catalog)) {
            return catalog.strip();
        }
        String schema = connection.getSchema();
        return schema == null ? "" : schema.strip();
    }

    private static void verifyEffectiveDatabase(
            String effectiveDatabase,
            String targetDatabase) {
        if (!StringUtils.hasText(effectiveDatabase)) {
            throw SeederStartupConfigurationValidator.refused(
                "the effective connection reports no current database"
            );
        }
        if (PRODUCTION_DATABASE.equalsIgnoreCase(effectiveDatabase)) {
            throw SeederStartupConfigurationValidator.refused(
                "effective catalog is the protected connex_pub target"
            );
        }
        if (!effectiveDatabase.equals(targetDatabase)) {
            throw SeederStartupConfigurationValidator.refused(
                "effective catalog is not the exact configured target database"
            );
        }
    }
}
