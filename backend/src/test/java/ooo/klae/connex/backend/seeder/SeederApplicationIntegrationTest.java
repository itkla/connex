package ooo.klae.connex.backend.seeder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.net.URI;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import javax.sql.DataSource;

import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationInfoService;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.Ordered;

import com.zaxxer.hikari.HikariDataSource;

import ooo.klae.connex.backend.BackendApplication;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.mappers.UserMapper;

/**
 * Proves the dedicated seeder launcher preserves the guarded full application context.
 */
class SeederApplicationIntegrationTest {

    private static final Pattern GENERATED_CATALOG =
        Pattern.compile("cnx_seeder_it_[0-9a-f]{32}");
    private static final String SCRATCH_CATALOG =
        validatedCatalog("cnx_seeder_it_" + UUID.randomUUID().toString().replace("-", ""));
    private static final String CANARY_TABLE = "seeder_sql_init_canary";
    private static final String UNSAFE_URL_SENTINEL =
        "unsafe-jdbc-sentinel-7429";

    @ParameterizedTest
    @ValueSource(strings = {
        "jdbc:mysql://unsafe-jdbc-sentinel-7429.example.test:3306/connex_seed"
            + "?sslMode=VERIFY_IDENTITY",
        "jdbc:mysql://unsafe-jdbc-sentinel-7429@127.0.0.1:3306/connex_seed"
            + "?sslMode=DISABLED",
        "jdbc:mysql://address=(host=unsafe-jdbc-sentinel-7429)"
            + "(port=3306)/connex_seed?sslMode=DISABLED",
        "jdbc:mysql://127.0.0.1,unsafe-jdbc-sentinel-7429.example.test/"
            + "connex_seed?sslMode=DISABLED",
        "jdbc:mysql://127.0.0.1:3306/connex_seed"
            + "?sslMode=DISABLED&socketFactory=unsafe-jdbc-sentinel-7429",
        "jdbc:mysql://127.0.0.1:3306/connex_seed"
            + "?sslMode=DISABLED&propertiesTransform=unsafe-jdbc-sentinel-7429"
    })
    void refusesUnsafeConfiguredTargetsWithoutOpeningAConnection(String configuredUrl) {
        IllegalStateException exception = assertThrows(
            IllegalStateException.class,
            () -> verifiedConfiguredTarget(configuredUrl)
        );

        assertTrue(exception.getMessage().startsWith("Seeder refused:"));
        assertFalse(exception.getMessage().contains(UNSAFE_URL_SENTINEL));
        assertEquals(null, exception.getCause());
        assertEquals(0, exception.getSuppressed().length);
    }

    @Test
    void launchesFullSeederContextThroughReadyAndClosesWithoutSqlInitCanary() {
        DatabaseFixture fixture = createScratchCatalog();
        try {
            launchFullSeederContext(fixture);
        } finally {
            dropScratchCatalog(fixture);
        }
    }

    private static DatabaseFixture createScratchCatalog() {
        String configuredUrl = System.getenv("CONNEX_DB_URL");
        String username = System.getenv("CONNEX_DB_USERNAME");
        String password = System.getenv("CONNEX_DB_PASSWORD");
        assumeTrue(
            hasText(configuredUrl) && hasText(username) && hasText(password),
            "CONNEX_DB_URL/CONNEX_DB_USERNAME/CONNEX_DB_PASSWORD not set; "
                + "skipping seeder application integration test"
        );
        SeederStartupConfigurationValidator.JdbcTarget configuredTarget =
            localScratchTarget(configuredUrl);
        SeederStartupConfigurationValidator.JdbcTarget bootstrapTarget =
            targetWithCatalog(configuredTarget, "mysql");
        try (Connection connection = DriverManager.getConnection(
                connectionUrl(bootstrapTarget),
                username,
                password
            );
                Statement statement = connection.createStatement()) {
            statement.execute(
                "CREATE DATABASE " + quotedCatalog()
                    + " CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci"
            );
        } catch (SQLException exception) {
            assumeTrue(
                false,
                "Cannot create the seeder application scratch catalog; "
                    + "grant CREATE/DROP for dedicated integration catalogs"
            );
        }
        return new DatabaseFixture(configuredTarget, username, password);
    }

    private static void dropScratchCatalog(DatabaseFixture fixture) {
        SeederStartupConfigurationValidator.JdbcTarget bootstrapTarget =
            targetWithCatalog(fixture.configuredTarget(), "mysql");
        try (Connection connection = DriverManager.getConnection(
                connectionUrl(bootstrapTarget),
                fixture.username(),
                fixture.password()
            );
                Statement statement = connection.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS " + quotedCatalog());
        } catch (SQLException exception) {
            throw sanitizedTestFailure(
                "Cannot remove seeder application scratch catalog"
            );
        }
    }

    private static void launchFullSeederContext(DatabaseFixture fixture) {
        AtomicReference<ReadyProof> readyProof = new AtomicReference<>();
        SpringApplication application =
            SeederApplication.createSpringApplication(BackendApplication.class);
        application.setBannerMode(Banner.Mode.OFF);
        application.setLogStartupInfo(false);
        application.setRegisterShutdownHook(false);
        application.addListeners(new ReadyProofListener(readyProof));

        SeederStartupConfigurationValidator.JdbcTarget scratchTarget =
            targetWithCatalog(fixture.configuredTarget(), SCRATCH_CATALOG);
        ConfigurableApplicationContext context = application.run(
            "--spring.profiles.active=seeder",
            "--connex.seeder.enabled=true",
            "--connex.seeder.profile=small",
            "--connex.seeder.seed=853",
            "--connex.seeder.workspaces=1",
            "--connex.seeder.anchor-date=2026-01-15",
            "--spring.datasource.url=" + connectionUrl(scratchTarget),
            "--spring.datasource.username=" + fixture.username(),
            "--spring.datasource.password=" + fixture.password()
        );

        try {
            ReadyProof proof = readyProof.get();
            assertNotNull(proof);
            assertTrue(proof.contextActive());
            assertFalse(context.isActive());
            assertTrue(proof.hikariDataSource());
            assertSameTarget(scratchTarget, proof.hikariTarget());
            assertSameTarget(scratchTarget, proof.metadataTarget());
            assertEquals(SCRATCH_CATALOG, proof.connectionCatalog());
            assertEquals(0, proof.pendingMigrations());
            assertEquals(proof.latestMigrationVersion(), proof.currentMigrationVersion());
            assertEquals(expectedFlywayConfiguration(), proof.flywayConfiguration());
            assertFalse(proof.canaryTablePresent());
            assertTrue(proof.userMapperStatementPresent());
            assertEquals(User.class, proof.userAlias());
            assertEquals(User.class, proof.userStatementResultType());
            assertTrue(proof.mapUnderscoreToCamelCase());
            assertEquals("Seeder Owner", proof.ownerDisplayName());
        } finally {
            if (context.isActive()) {
                context.close();
            }
        }
    }

    private static ReadyProof inspectReadyContext(
            ConfigurableApplicationContext context) throws SQLException {
        DataSource dataSource = context.getBean(DataSource.class);
        HikariDataSource hikariDataSource = dataSource instanceof HikariDataSource hikari
            ? hikari
            : null;
        SeederStartupConfigurationValidator.JdbcTarget hikariTarget =
            hikariDataSource == null
                ? null
                : SeederStartupConfigurationValidator.verifiedTarget(
                    hikariDataSource.getJdbcUrl(),
                    "integration Hikari JDBC URL",
                    false
                );
        Map<String, Flyway> flywayBeans = context.getBeansOfType(Flyway.class);
        Flyway flyway = flywayBeans.values().stream().findFirst().orElseThrow();
        org.flywaydb.core.api.configuration.Configuration flywayConfiguration =
            flyway.getConfiguration();
        MigrationInfoService migrationInfo = flyway.info();
        MigrationInfo currentMigration = Objects.requireNonNull(migrationInfo.current());
        MigrationVersion currentVersion = Objects.requireNonNull(currentMigration.getVersion());
        MigrationVersion latestVersion = Arrays.stream(migrationInfo.all())
            .map(MigrationInfo::getVersion)
            .filter(Objects::nonNull)
            .max(MigrationVersion::compareTo)
            .orElseThrow();
        Configuration mybatis = context.getBean(SqlSessionFactory.class).getConfiguration();
        String userStatementName = UserMapper.class.getName() + ".getUserByUsername";
        MappedStatement userStatement = mybatis.getMappedStatement(userStatementName);
        User owner = context.getBean(UserMapper.class).getAllUsers().stream()
            .filter(user -> "Seeder Owner".equals(user.getDisplayName()))
            .findFirst()
            .orElseThrow();

        try (Connection connection = dataSource.getConnection()) {
            SeederStartupConfigurationValidator.JdbcTarget metadataTarget =
                SeederStartupConfigurationValidator.verifiedTarget(
                    connection.getMetaData().getURL(),
                    "integration metadata URL",
                    false
                );
            return new ReadyProof(
                context.isActive(),
                hikariDataSource != null,
                hikariTarget,
                metadataTarget,
                connection.getCatalog(),
                migrationInfo.pending().length,
                currentVersion.toString(),
                latestVersion.toString(),
                new FlywayConfigurationProof(
                    flywayBeans.size() == 1,
                    flywayConfiguration.isBaselineOnMigrate(),
                    flywayConfiguration.getBaselineVersion().toString(),
                    flywayConfiguration.isCleanDisabled(),
                    flywayConfiguration.isSkipExecutingMigrations(),
                    flywayConfiguration.getTarget().toString(),
                    flywayConfiguration.getTable(),
                    flywayConfiguration.isSkipDefaultResolvers(),
                    flywayConfiguration.isSkipDefaultCallbacks(),
                    Arrays.stream(flywayConfiguration.getLocations())
                        .map(location -> location.getDescriptor())
                        .toList(),
                    Arrays.stream(flywayConfiguration.getCallbackLocations())
                        .map(location -> location.getDescriptor())
                        .toList(),
                    flywayConfiguration.getInitSql(),
                    Map.copyOf(flywayConfiguration.getPlaceholders()),
                    flywayConfiguration.isPlaceholderReplacement(),
                    flywayConfiguration.getSqlMigrationPrefix(),
                    flywayConfiguration.getRepeatableSqlMigrationPrefix(),
                    flywayConfiguration.getSqlMigrationSeparator(),
                    List.of(flywayConfiguration.getSqlMigrationSuffixes()),
                    flywayConfiguration.isValidateOnMigrate(),
                    flywayConfiguration.isValidateMigrationNaming(),
                    Arrays.stream(flywayConfiguration.getIgnoreMigrationPatterns())
                        .map(Object::toString)
                        .toList(),
                    flywayConfiguration.isOutOfOrder(),
                    flywayConfiguration.isFailOnMissingLocations()
                ),
                tableExists(connection, SCRATCH_CATALOG, CANARY_TABLE),
                mybatis.hasStatement(userStatementName),
                mybatis.getTypeAliasRegistry().resolveAlias("User"),
                userStatement.getResultMaps().get(0).getType(),
                mybatis.isMapUnderscoreToCamelCase(),
                owner.getDisplayName()
            );
        }
    }

    private static boolean tableExists(
            Connection connection,
            String catalog,
            String table) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT COUNT(*)
                FROM information_schema.TABLES
                WHERE TABLE_SCHEMA = ?
                  AND TABLE_NAME = ?
                """)) {
            statement.setString(1, catalog);
            statement.setString(2, table);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getInt(1) != 0;
            }
        }
    }

    private static FlywayConfigurationProof expectedFlywayConfiguration() {
        return new FlywayConfigurationProof(
            true,
            true,
            "0",
            true,
            false,
            MigrationVersion.LATEST.toString(),
            "flyway_schema_history",
            false,
            false,
            List.of("classpath:db/migration"),
            List.of(),
            null,
            Map.of(),
            false,
            "V",
            "R",
            "__",
            List.of(".sql"),
            true,
            true,
            List.of(),
            false,
            true
        );
    }

    private static String validatedCatalog(String catalog) {
        if (!GENERATED_CATALOG.matcher(catalog).matches()) {
            throw new IllegalArgumentException("Invalid generated seeder catalog");
        }
        return catalog;
    }

    private static String quotedCatalog() {
        return "`" + validatedCatalog(SCRATCH_CATALOG) + "`";
    }

    private static SeederStartupConfigurationValidator.JdbcTarget verifiedConfiguredTarget(
            String configuredUrl) {
        return SeederStartupConfigurationValidator.verifiedTarget(
            configuredUrl,
            "CONNEX_DB_URL",
            false
        );
    }

    private static SeederStartupConfigurationValidator.JdbcTarget localScratchTarget(
            String configuredUrl) {
        try {
            if (!configuredUrl.startsWith("jdbc:mysql://")) {
                throw sanitizedTestFailure("Seeder integration database URL is unsupported");
            }
            URI uri = URI.create(configuredUrl.substring("jdbc:".length()));
            String host = uri.getHost();
            if (host == null) {
                throw sanitizedTestFailure("Seeder integration database host is missing");
            }
            if ("localhost".equalsIgnoreCase(host)) {
                host = "127.0.0.1";
            }
            int port = uri.getPort() == -1 ? 3306 : uri.getPort();
            SeederStartupConfigurationValidator.JdbcTarget scratchTarget =
                new SeederStartupConfigurationValidator.JdbcTarget(
                    host,
                    port,
                    SCRATCH_CATALOG
                );
            return verifiedConfiguredTarget(connectionUrl(scratchTarget));
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw sanitizedTestFailure("Seeder integration database URL is malformed");
        }
    }

    private static SeederStartupConfigurationValidator.JdbcTarget targetWithCatalog(
            SeederStartupConfigurationValidator.JdbcTarget configuredTarget,
            String catalog) {
        if (!"mysql".equals(catalog)) {
            validatedCatalog(catalog);
        }
        return new SeederStartupConfigurationValidator.JdbcTarget(
            configuredTarget.host(),
            configuredTarget.port(),
            catalog
        );
    }

    private static String connectionUrl(
            SeederStartupConfigurationValidator.JdbcTarget target) {
        String authorityHost = target.host().contains(":")
            ? "[" + target.host() + "]"
            : target.host();
        return "jdbc:mysql://" + authorityHost + ":" + target.port() + "/"
            + target.database()
            + "?allowPublicKeyRetrieval=true&sslMode=DISABLED";
    }

    private static void assertSameTarget(
            SeederStartupConfigurationValidator.JdbcTarget expected,
            SeederStartupConfigurationValidator.JdbcTarget actual) {
        assertNotNull(actual);
        assertEquals(expected.host(), actual.host());
        assertEquals(expected.port(), actual.port());
        assertEquals(expected.database(), actual.database());
    }

    private static IllegalStateException sanitizedTestFailure(String message) {
        return new IllegalStateException(message, (Throwable) null);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private record ReadyProof(
        boolean contextActive,
        boolean hikariDataSource,
        SeederStartupConfigurationValidator.JdbcTarget hikariTarget,
        SeederStartupConfigurationValidator.JdbcTarget metadataTarget,
        String connectionCatalog,
        int pendingMigrations,
        String currentMigrationVersion,
        String latestMigrationVersion,
        FlywayConfigurationProof flywayConfiguration,
        boolean canaryTablePresent,
        boolean userMapperStatementPresent,
        Class<?> userAlias,
        Class<?> userStatementResultType,
        boolean mapUnderscoreToCamelCase,
        String ownerDisplayName
    ) {
    }

    private record FlywayConfigurationProof(
        boolean enabled,
        boolean baselineOnMigrate,
        String baselineVersion,
        boolean cleanDisabled,
        boolean skipExecutingMigrations,
        String target,
        String table,
        boolean skipDefaultResolvers,
        boolean skipDefaultCallbacks,
        List<String> locations,
        List<String> callbackLocations,
        String initSql,
        Map<String, String> placeholders,
        boolean placeholderReplacement,
        String sqlMigrationPrefix,
        String repeatableSqlMigrationPrefix,
        String sqlMigrationSeparator,
        List<String> sqlMigrationSuffixes,
        boolean validateOnMigrate,
        boolean validateMigrationNaming,
        List<String> ignoreMigrationPatterns,
        boolean outOfOrder,
        boolean failOnMissingLocations
    ) {
    }

    private record DatabaseFixture(
        SeederStartupConfigurationValidator.JdbcTarget configuredTarget,
        String username,
        String password
    ) {
    }

    private static final class ReadyProofListener
            implements ApplicationListener<ApplicationReadyEvent>, Ordered {

        private final AtomicReference<ReadyProof> proof;

        private ReadyProofListener(AtomicReference<ReadyProof> proof) {
            this.proof = proof;
        }

        @Override
        public void onApplicationEvent(ApplicationReadyEvent event) {
            try {
                proof.set(inspectReadyContext(event.getApplicationContext()));
            } catch (SQLException exception) {
                throw sanitizedTestFailure(
                    "Could not inspect ready seeder application context"
                );
            }
        }

        @Override
        public int getOrder() {
            return Ordered.HIGHEST_PRECEDENCE;
        }
    }
}
