package ooo.klae.connex.backend.seeder;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.env.MockEnvironment;

import ooo.klae.connex.backend.config.DeploymentProperties;

class SeederGuardTest {

    private static final String[] UNSAFE_PRECONNECTION_DRIVER_PROPERTY_KEY_ALIASES = {
        "address",
        "database",
        "databaseName",
        "DATABASE_NAME",
        "dbname",
        "dnsSrv",
        "dns_srv",
        "host",
        "hostName",
        "jdbc-url",
        "path",
        "port",
        "portNumber",
        "port_number",
        "priority",
        "protocol",
        "serverAffinityOrder",
        "serverName",
        "server-name",
        "socksProxyHost",
        "socks-proxy-port",
        "socks_proxy_remote_dns",
        "type",
        "URL",
        "authenticationOpenidConnectCallbackHandler",
        "authenticationPlugins",
        "authentication_web_authn_callback_handler",
        "clientCertificateKeyStoreUrl",
        "ConnectionAttributes",
        "connectionLifecycleInterceptors",
        "default-authentication-plugin",
        "exception_interceptors",
        "ha.loadBalanceStrategy",
        "loadBalanceExceptionChecker",
        "logger",
        "profiler-event-handler",
        "propertiesTransform",
        "properties-transform",
        "queryInfoCacheFactory",
        "query_interceptors",
        "serverConfigCacheFactory",
        "session-variables",
        "socketFactory",
        "TRUSTCERTIFICATEKEYSTOREURL"
    };

    @ParameterizedTest
    @ValueSource(strings = {"CoNnEx_PuB", "CoNnExDb"})
    void refusesProtectedDatabaseCaseInsensitively(String database) {
        IllegalStateException exception = assertThrows(
            IllegalStateException.class,
            () -> SeederGuard.verifyJdbcUrl(
                "jdbc:mysql://127.0.0.1:3313/" + database + "?sslMode=DISABLED",
                false
            )
        );

        assertTrue(exception.getMessage().contains("protected Connex"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"CoNnEx_PuB", "CoNnExDb"})
    void refusesProtectedConfiguredDatabaseBeforeOpeningAConnection(String database) {
        MockEnvironment environment = seederEnvironment().withProperty(
            "spring.datasource.url",
            "jdbc:mysql://127.0.0.1:3313/" + database + "?sslMode=DISABLED"
        );
        DataSource dataSource = mock(DataSource.class);
        SeederGuard guard = new SeederGuard(
            environment,
            new DeploymentProperties(),
            new SeederProperties(),
            dataSource
        );

        IllegalStateException exception =
            assertThrows(IllegalStateException.class, guard::verify);

        assertTrue(exception.getMessage().contains("protected Connex"));
        verifyNoInteractions(dataSource);
    }

    @Test
    void refusesRemoteHostWithoutExplicitOverride() {
        IllegalStateException exception = assertThrows(
            IllegalStateException.class,
            () -> SeederGuard.verifyJdbcUrl(
                "jdbc:mysql://db.example.test:3306/connex_seed?sslMode=VERIFY_IDENTITY",
                false
            )
        );

        assertTrue(exception.getMessage().contains("allow-remote-host=true"));
    }

    @Test
    void permitsRemoteHostOnlyWithExplicitOverride() {
        assertDoesNotThrow(() -> SeederGuard.verifyJdbcUrl(
            "jdbc:mysql://db.example.test:3306/connex_seed?sslMode=VERIFY_IDENTITY",
            true
        ));
    }

    @Test
    void permitsSimpleLoopbackTarget() {
        assertDoesNotThrow(() -> SeederGuard.verifyJdbcUrl(
            "jdbc:mysql://127.0.0.1:3313/connex_seeder?sslMode=DISABLED",
            false
        ));
    }

    @ParameterizedTest
    @ValueSource(strings = {"prod", "customer_acme", "connex_dev"})
    void refusesNonSeederCatalogs(String database) {
        IllegalStateException exception = assertThrows(
            IllegalStateException.class,
            () -> SeederGuard.verifyJdbcUrl(
                "jdbc:mysql://127.0.0.1:3313/" + database + "?sslMode=DISABLED",
                false
            )
        );

        assertTrue(exception.getMessage().contains("dedicated seeder catalog"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void refusesTextualLocalhostRegardlessOfRemoteHostConfirmation(
            boolean allowRemoteHost) {
        IllegalStateException exception = assertThrows(
            IllegalStateException.class,
            () -> SeederGuard.verifyJdbcUrl(
                "jdbc:mysql://localhost:3313/connex_seeder?sslMode=DISABLED",
                allowRemoteHost
            )
        );

        assertTrue(exception.getMessage().contains("numeric loopback"));
    }

    @Test
    void permitsBracketedIpv6LoopbackTarget() {
        assertDoesNotThrow(() -> SeederGuard.verifyJdbcUrl(
            "jdbc:mysql://[::1]:3313/connex_seeder?sslMode=DISABLED",
            false
        ));
    }

    @Test
    void refusesDatabaseSelectingQueryParameter() {
        IllegalStateException exception = assertThrows(
            IllegalStateException.class,
            () -> SeederGuard.verifyJdbcUrl(
                "jdbc:mysql://127.0.0.1:3306/scratch?dbname=connex_pub",
                false
            )
        );

        assertTrue(exception.getMessage().contains("Connector/J query property"));
    }

    @Test
    void refusesUnsafePreconnectionQueryParameters() {
        for (String selector : UNSAFE_PRECONNECTION_DRIVER_PROPERTY_KEY_ALIASES) {
            IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> SeederGuard.verifyJdbcUrl(
                    "jdbc:mysql://127.0.0.1:3306/scratch?" + selector + "=redirect",
                    false
                ),
                selector
            );

            assertTrue(exception.getMessage().contains("Connector/J query property"), selector);
        }
        for (String selector : new String[] {"db%6Eame", "data%62ase"}) {
            IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> SeederGuard.verifyJdbcUrl(
                    "jdbc:mysql://127.0.0.1:3306/scratch?" + selector + "=redirect",
                    false
                ),
                selector
            );

            assertTrue(exception.getMessage().contains("Connector/J query property"), selector);
        }
    }

    @Test
    void refusesTargetSelectingQueryParameterBeforeRejectingOmittedAuthority() {
        IllegalStateException exception = assertThrows(
            IllegalStateException.class,
            () -> SeederGuard.verifyJdbcUrl(
                "jdbc:mysql:///scratch?%68ost=127.0.0.1",
                false
            )
        );

        assertTrue(exception.getMessage().startsWith("Seeder refused:"));
    }

    @Test
    void refusesRelaxedHikariJdbcUrlOverridesBeforeOpeningAConnection() {
        for (String propertyName : new String[] {
            "spring.datasource.hikari.jdbc-url",
            "spring.datasource.hikari.jdbcUrl",
            "spring.datasource.hikari.jdbc_url"
        }) {
            MockEnvironment environment = seederEnvironment()
                .withProperty(
                    "spring.datasource.url",
                    "jdbc:mysql://127.0.0.1:3313/connex_seeder?sslMode=DISABLED"
                )
                .withProperty(
                    propertyName,
                    "jdbc:mysql://127.0.0.1:3313/connex_pub?sslMode=DISABLED"
                );
            DataSource dataSource = mock(DataSource.class);
            SeederGuard guard = new SeederGuard(
                environment,
                new DeploymentProperties(),
                new SeederProperties(),
                dataSource
            );

            IllegalStateException exception =
                assertThrows(IllegalStateException.class, guard::verify);

            assertTrue(exception.getMessage().contains("protected Connex"));
            verifyNoInteractions(dataSource);
        }
    }

    @Test
    void refusesNormalizedUnsafeHikariDriverPropertiesBeforeOpeningAConnection() {
        assertUnsafeDriverPropertiesRefused(
            new String[] {
                "spring.datasource.hikari.data-source-properties",
                "spring.datasource.hikari.dataSourceProperties",
                "spring.datasource.hikari.data_source_properties"
            },
            "spring.datasource.hikari.data-source-properties"
        );
    }

    @Test
    void refusesNormalizedUnsafeFlywayDriverPropertiesBeforeOpeningAConnection() {
        assertUnsafeDriverPropertiesRefused(
            new String[] {
                "spring.flyway.jdbc-properties",
                "spring.flyway.jdbcProperties",
                "spring.flyway.jdbc_properties"
            },
            "spring.flyway.jdbc-properties"
        );
    }

    private static void assertUnsafeDriverPropertiesRefused(
            String[] propertyPrefixes,
            String expectedPropertyName) {
        int propertyIndex = 0;
        for (String propertyKey : UNSAFE_PRECONNECTION_DRIVER_PROPERTY_KEY_ALIASES) {
            String propertyPrefix = propertyPrefixes[propertyIndex % propertyPrefixes.length];
            String propertyName = propertyPrefix + "." + propertyKey;
            MockEnvironment environment = seederEnvironment()
                .withProperty(
                    "spring.datasource.url",
                    "jdbc:mysql://127.0.0.1:3313/connex_seeder?sslMode=DISABLED"
                )
                .withProperty(propertyName, "redirect");
            DataSource dataSource = mock(DataSource.class);
            SeederGuard guard = new SeederGuard(
                environment,
                new DeploymentProperties(),
                new SeederProperties(),
                dataSource
            );

            IllegalStateException exception =
                assertThrows(IllegalStateException.class, guard::verify, propertyName);

            assertTrue(
                exception.getMessage().contains(expectedPropertyName),
                propertyName
            );
            verifyNoInteractions(dataSource);
            propertyIndex++;
        }
    }

    @Test
    void permitsSafeHikariAndFlywayDriverProperties() throws SQLException {
        MockEnvironment environment = seederEnvironment()
            .withProperty(
                "spring.datasource.url",
                "jdbc:mysql://127.0.0.1:3313/connex_seeder?sslMode=DISABLED"
            )
            .withProperty(
                "spring.datasource.hikari.data-source-properties.sslMode",
                "DISABLED"
            )
            .withProperty("spring.flyway.jdbc-properties.connectTimeout", "5000");
        SeederGuard guard = new SeederGuard(
            environment,
            new DeploymentProperties(),
            new SeederProperties(),
            seederDataSource("jdbc:mysql://127.0.0.1:3313/connex_seeder?sslMode=DISABLED")
        );

        assertDoesNotThrow(() -> guard.verify());
    }

    @Test
    void refusesRelaxedHikariConnectionInitSqlAliasesBeforeOpeningAConnection() {
        for (String propertyName : new String[] {
            "spring.datasource.hikari.connection-init-sql",
            "spring.datasource.hikari.connectionInitSql",
            "spring.datasource.hikari.connection_init_sql"
        }) {
            MockEnvironment environment = seederEnvironment()
                .withProperty(
                    "spring.datasource.url",
                    "jdbc:mysql://127.0.0.1:3313/connex_seeder?sslMode=DISABLED"
                )
                .withProperty(propertyName, "USE connex_pub");
            DataSource dataSource = mock(DataSource.class);
            SeederGuard guard = new SeederGuard(
                environment,
                new DeploymentProperties(),
                new SeederProperties(),
                dataSource
            );

            IllegalStateException exception =
                assertThrows(IllegalStateException.class, guard::verify);

            assertTrue(
                exception.getMessage().contains(
                    "spring.datasource.hikari.connection-init-sql"
                )
            );
            verifyNoInteractions(dataSource);
        }
    }

    @Test
    void permitsProjectDefaultHikariConnectionInitSqlAfterStripping() throws SQLException {
        MockEnvironment environment = seederEnvironment()
            .withProperty(
                "spring.datasource.url",
                "jdbc:mysql://127.0.0.1:3313/connex_seeder?sslMode=DISABLED"
            )
            .withProperty(
                "spring.datasource.hikari.connection-init-sql",
                "  SET time_zone = '+00:00'  "
            );
        SeederGuard guard = new SeederGuard(
            environment,
            new DeploymentProperties(),
            new SeederProperties(),
            seederDataSource("jdbc:mysql://127.0.0.1:3313/connex_seeder?sslMode=DISABLED")
        );

        assertDoesNotThrow(() -> guard.verify());
    }

    @Test
    void refusesRelaxedHikariConnectionTestQueryAliasesBeforeOpeningAConnection() {
        for (String propertyName : new String[] {
            "spring.datasource.hikari.connection-test-query",
            "spring.datasource.hikari.connectionTestQuery",
            "spring.datasource.hikari.connection_test_query"
        }) {
            MockEnvironment environment = seederEnvironment()
                .withProperty(
                    "spring.datasource.url",
                    "jdbc:mysql://127.0.0.1:3313/connex_seeder?sslMode=DISABLED"
                )
                .withProperty(propertyName, "  USE connex_pub  ");
            DataSource dataSource = mock(DataSource.class);
            SeederGuard guard = new SeederGuard(
                environment,
                new DeploymentProperties(),
                new SeederProperties(),
                dataSource
            );

            IllegalStateException exception =
                assertThrows(IllegalStateException.class, guard::verify);

            assertTrue(
                exception.getMessage().contains(
                    "spring.datasource.hikari.connection-test-query"
                )
            );
            verifyNoInteractions(dataSource);
        }
    }

    @Test
    void permitsOnlyAbsentHikariConnectionTestQuery() throws SQLException {
        MockEnvironment environment = seederEnvironment().withProperty(
            "spring.datasource.url",
            "jdbc:mysql://127.0.0.1:3313/connex_seeder?sslMode=DISABLED"
        );
        SeederGuard guard = new SeederGuard(
            environment,
            new DeploymentProperties(),
            new SeederProperties(),
            seederDataSource(
                "jdbc:mysql://127.0.0.1:3313/connex_seeder?sslMode=DISABLED"
            )
        );

        assertDoesNotThrow(() -> guard.verify());

        environment.withProperty("spring.datasource.hikari.connection-test-query", " ");
        DataSource dataSource = mock(DataSource.class);
        SeederGuard blankQueryGuard = new SeederGuard(
            environment,
            new DeploymentProperties(),
            new SeederProperties(),
            dataSource
        );

        assertThrows(IllegalStateException.class, blankQueryGuard::verify);
        verifyNoInteractions(dataSource);
    }

    @Test
    void requiresDatasourceUrlAsTheBaselineBeforeOpeningAConnection() {
        MockEnvironment environment = seederEnvironment().withProperty(
            "spring.datasource.hikari.jdbc_url",
            "jdbc:mysql://127.0.0.1:3313/connex_seeder?sslMode=DISABLED"
        );
        DataSource dataSource = mock(DataSource.class);
        SeederGuard guard = new SeederGuard(
            environment,
            new DeploymentProperties(),
            new SeederProperties(),
            dataSource
        );

        IllegalStateException exception =
            assertThrows(IllegalStateException.class, guard::verify);

        assertTrue(exception.getMessage().contains("spring.datasource.url is required"));
        verifyNoInteractions(dataSource);
    }

    @Test
    void refusesActivationWithoutTheCompleteSeederInvocationContract() {
        MockEnvironment noProfile = new MockEnvironment()
            .withProperty("connex.maintenance.mode", "seeder")
            .withProperty("spring.main.web-application-type", "none");
        MockEnvironment servingProcess = seederEnvironment()
            .withProperty("spring.main.web-application-type", "servlet");
        MockEnvironment wrongMaintenanceMode = seederEnvironment()
            .withProperty("connex.maintenance.mode", "off");
        MockEnvironment routedTenancy = seederEnvironment()
            .withProperty("connex.tenancy.routing.mode", "catalog-per-placement");

        for (MockEnvironment environment
                : new MockEnvironment[] {noProfile, servingProcess, wrongMaintenanceMode, routedTenancy}) {
            SeederGuard guard = new SeederGuard(
                environment,
                new DeploymentProperties(),
                new SeederProperties(),
                mock(DataSource.class)
            );

            IllegalStateException exception =
                assertThrows(IllegalStateException.class, guard::verify);

            assertTrue(exception.getMessage().startsWith("Seeder refused:"));
        }
    }

    @Test
    void refusesFlywaySchemaThatIsNotTheConfiguredTargetDatabase() {
        MockEnvironment environment = seederEnvironment()
            .withProperty(
                "spring.datasource.url",
                "jdbc:mysql://127.0.0.1:3313/connex_seeder?sslMode=DISABLED"
            )
            .withProperty("spring.flyway.schemas", "scratch,connex_pub");
        SeederGuard guard = new SeederGuard(
            environment,
            new DeploymentProperties(),
            new SeederProperties(),
            mock(DataSource.class)
        );

        IllegalStateException exception =
            assertThrows(IllegalStateException.class, guard::verify);

        assertTrue(exception.getMessage().contains("spring.flyway.schemas"));
    }

    @Test
    void refusesFlywaySchemaNamingTheProtectedProductionDatabase() {
        MockEnvironment environment = seederEnvironment()
            .withProperty(
                "spring.datasource.url",
                "jdbc:mysql://127.0.0.1:3313/connex_seeder?sslMode=DISABLED"
            )
            .withProperty("spring.flyway.schemas", "connex_pub");
        SeederGuard guard = new SeederGuard(
            environment,
            new DeploymentProperties(),
            new SeederProperties(),
            mock(DataSource.class)
        );

        IllegalStateException exception =
            assertThrows(IllegalStateException.class, guard::verify);

        assertTrue(exception.getMessage().contains("spring.flyway.schemas"));
    }

    @Test
    void refusesEveryExplicitlyConfiguredDeploymentProfile() {
        for (String profile : new String[] {"saas", "silo", "on-prem"}) {
            MockEnvironment environment = seederEnvironment()
                .withProperty("connex.deployment.profile", profile);
            SeederGuard guard = new SeederGuard(
                environment,
                new DeploymentProperties(),
                new SeederProperties(),
                mock(DataSource.class)
            );

            IllegalStateException exception =
                assertThrows(IllegalStateException.class, guard::verify);

            assertTrue(exception.getMessage().contains("connex.deployment.profile"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"CoNnEx_PuB", "CoNnExDb"})
    void refusesEffectiveProtectedCatalogEvenWithLoopbackUrl(
            String effectiveCatalog) throws SQLException {
        MockEnvironment environment = seederEnvironment().withProperty(
            "spring.datasource.url",
            "jdbc:mysql://127.0.0.1:3313/connex_seeder?sslMode=DISABLED"
        );
        DataSource dataSource = dataSourceAt(
            "jdbc:mysql://127.0.0.1:3313/connex_seeder?sslMode=DISABLED",
            "connex_seeder",
            null,
            effectiveCatalog
        );
        SeederGuard guard = new SeederGuard(
            environment,
            new DeploymentProperties(),
            new SeederProperties(),
            dataSource
        );

        IllegalStateException exception =
            assertThrows(IllegalStateException.class, guard::verify);

        assertTrue(exception.getMessage().contains("protected Connex"));
    }

    @Test
    void sanitizesUncheckedDatasourceAcquisitionFailure() throws SQLException {
        DataSource dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenThrow(
            new IllegalStateException("jdbc:mysql://secret.example/connex_pub")
        );
        SeederGuard guard = new SeederGuard(
            seederEnvironment().withProperty(
                "spring.datasource.url",
                "jdbc:mysql://127.0.0.1:3313/connex_seeder?sslMode=DISABLED"
            ),
            new DeploymentProperties(),
            new SeederProperties(),
            dataSource
        );

        IllegalStateException exception =
            assertThrows(IllegalStateException.class, guard::verify);

        assertCleanRefusal(
            exception,
            "Seeder refused: could not verify effective JDBC target"
        );
    }

    @Test
    void sanitizesNullConnectionMetadata() throws SQLException {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getMetaData()).thenReturn(null);
        SeederGuard guard = new SeederGuard(
            seederEnvironment().withProperty(
                "spring.datasource.url",
                "jdbc:mysql://127.0.0.1:3313/connex_seeder?sslMode=DISABLED"
            ),
            new DeploymentProperties(),
            new SeederProperties(),
            dataSource
        );

        IllegalStateException exception =
            assertThrows(IllegalStateException.class, guard::verify);

        assertCleanRefusal(
            exception,
            "Seeder refused: could not verify effective JDBC target"
        );
    }

    @Test
    void sanitizesUncheckedMetadataDriverFailure() throws SQLException {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        DatabaseMetaData metaData = mock(DatabaseMetaData.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getMetaData()).thenReturn(metaData);
        when(metaData.getURL()).thenThrow(
            new IllegalArgumentException("driver exposed a sensitive target")
        );
        SeederGuard guard = new SeederGuard(
            seederEnvironment().withProperty(
                "spring.datasource.url",
                "jdbc:mysql://127.0.0.1:3313/connex_seeder?sslMode=DISABLED"
            ),
            new DeploymentProperties(),
            new SeederProperties(),
            dataSource
        );

        IllegalStateException exception =
            assertThrows(IllegalStateException.class, guard::verify);

        assertCleanRefusal(
            exception,
            "Seeder refused: could not verify effective JDBC target"
        );
    }

    @Test
    void sanitizesStandaloneUncheckedConnectionCloseFailure() throws SQLException {
        DataSource dataSource = dataSourceAt(
            "jdbc:mysql://127.0.0.1:3313/connex_seeder?sslMode=DISABLED",
            "connex_seeder",
            null
        );
        Connection connection = dataSource.getConnection();
        doThrow(new IllegalStateException("close exposed a sensitive target"))
            .when(connection)
            .close();
        SeederGuard guard = new SeederGuard(
            seederEnvironment().withProperty(
                "spring.datasource.url",
                "jdbc:mysql://127.0.0.1:3313/connex_seeder?sslMode=DISABLED"
            ),
            new DeploymentProperties(),
            new SeederProperties(),
            dataSource
        );

        IllegalStateException exception =
            assertThrows(IllegalStateException.class, guard::verify);

        assertCleanRefusal(
            exception,
            "Seeder refused: could not verify effective JDBC target"
        );
    }

    @Test
    void cleansIntentionalMismatchWithSuppressedCloseFailure() throws SQLException {
        DataSource dataSource = dataSourceAt(
            "jdbc:mysql://127.0.0.1:3306/connex_seeder?sslMode=DISABLED",
            "connex_seeder",
            null
        );
        Connection connection = dataSource.getConnection();
        doThrow(new SQLException("close exposed a sensitive target"))
            .when(connection)
            .close();
        SeederGuard guard = new SeederGuard(
            seederEnvironment().withProperty(
                "spring.datasource.url",
                "jdbc:mysql://127.0.0.1:3313/connex_seeder?sslMode=DISABLED"
            ),
            new DeploymentProperties(),
            new SeederProperties(),
            dataSource
        );

        IllegalStateException exception =
            assertThrows(IllegalStateException.class, guard::verify);

        assertCleanRefusal(
            exception,
            "Seeder refused: effective datasource metadata URL disagrees with spring.datasource.url"
        );
    }

    @Test
    void refusesConfiguredUrlsThatAgreeOnDatabaseButNameDifferentPorts() {
        MockEnvironment environment = seederEnvironment()
            .withProperty(
                "spring.datasource.url",
                "jdbc:mysql://127.0.0.1:3313/connex_seeder?sslMode=DISABLED"
            )
            .withProperty(
                "spring.flyway.url",
                "jdbc:mysql://127.0.0.1:3306/connex_seeder?sslMode=DISABLED"
            );
        SeederGuard guard = new SeederGuard(
            environment,
            new DeploymentProperties(),
            new SeederProperties(),
            mock(DataSource.class)
        );

        IllegalStateException exception =
            assertThrows(IllegalStateException.class, guard::verify);

        assertTrue(exception.getMessage().contains("disagree"));
    }

    @Test
    void refusesConfiguredUrlsThatNameDifferentRemoteHostsEvenWithOverride() {
        MockEnvironment environment = seederEnvironment()
            .withProperty(
                "spring.datasource.url",
                "jdbc:mysql://db-one.example.test:3306/connex_seeder?sslMode=VERIFY_IDENTITY"
            )
            .withProperty(
                "spring.flyway.url",
                "jdbc:mysql://db-two.example.test:3306/connex_seeder?sslMode=VERIFY_IDENTITY"
            )
            .withProperty("connex.seeder.allow-remote-host", "true");
        SeederGuard guard = new SeederGuard(
            environment,
            new DeploymentProperties(),
            new SeederProperties(),
            mock(DataSource.class)
        );

        IllegalStateException exception =
            assertThrows(IllegalStateException.class, guard::verify);

        assertTrue(exception.getMessage().contains("disagree"));
    }

    @Test
    void refusesTextualLocalhostBeforeComparingConfiguredTargets() {
        MockEnvironment environment = seederEnvironment()
            .withProperty(
                "spring.datasource.url",
                "jdbc:mysql://localhost:3306/connex_seeder?sslMode=DISABLED"
            )
            .withProperty(
                "spring.flyway.url",
                "jdbc:mysql://127.0.0.1:3306/connex_seeder?sslMode=DISABLED"
            );
        SeederGuard guard = new SeederGuard(
            environment,
            new DeploymentProperties(),
            new SeederProperties(),
            mock(DataSource.class)
        );

        IllegalStateException exception =
            assertThrows(IllegalStateException.class, guard::verify);

        assertTrue(exception.getMessage().contains("numeric loopback"));
    }

    @Test
    void permitsConfiguredUrlsThatOnlyDifferOnTheImplicitDefaultPort() throws SQLException {
        MockEnvironment environment = seederEnvironment()
            .withProperty(
                "spring.datasource.url",
                "jdbc:mysql://127.0.0.1/connex_seeder?sslMode=DISABLED"
            )
            .withProperty(
                "spring.flyway.url",
                "jdbc:mysql://127.0.0.1:3306/connex_seeder?sslMode=DISABLED"
            );
        SeederGuard guard = new SeederGuard(
            environment,
            new DeploymentProperties(),
            new SeederProperties(),
            seederDataSource("jdbc:mysql://127.0.0.1:3306/connex_seeder?sslMode=DISABLED")
        );

        assertDoesNotThrow(() -> guard.verify());
    }

    @Test
    void refusesConfiguredUrlsWithCaseOnlyDatabaseMismatch() {
        MockEnvironment environment = seederEnvironment()
            .withProperty(
                "spring.datasource.url",
                "jdbc:mysql://127.0.0.1:3306/Connex_Seeder?sslMode=DISABLED"
            )
            .withProperty(
                "spring.flyway.url",
                "jdbc:mysql://127.0.0.1:3306/connex_seeder?sslMode=DISABLED"
            );
        SeederGuard guard = new SeederGuard(
            environment,
            new DeploymentProperties(),
            new SeederProperties(),
            mock(DataSource.class)
        );

        IllegalStateException exception =
            assertThrows(IllegalStateException.class, guard::verify);

        assertTrue(exception.getMessage().contains("disagrees"));
    }

    @Test
    void refusesFlywayDefaultSchemaThatIsNotTheConfiguredTargetDatabase() {
        MockEnvironment environment = seederEnvironment()
            .withProperty(
                "spring.datasource.url",
                "jdbc:mysql://127.0.0.1:3313/connex_seeder?sslMode=DISABLED"
            )
            .withProperty("spring.flyway.default-schema", "connex_dev");
        SeederGuard guard = new SeederGuard(
            environment,
            new DeploymentProperties(),
            new SeederProperties(),
            mock(DataSource.class)
        );

        IllegalStateException exception =
            assertThrows(IllegalStateException.class, guard::verify);

        assertTrue(exception.getMessage().contains("spring.flyway.default-schema"));
    }

    @Test
    void refusesFlywaySchemasDeclaredAsAnIndexedList() {
        MockEnvironment environment = seederEnvironment()
            .withProperty(
                "spring.datasource.url",
                "jdbc:mysql://127.0.0.1:3313/connex_seeder?sslMode=DISABLED"
            )
            .withProperty("spring.flyway.schemas[0]", "connex_dev");
        SeederGuard guard = new SeederGuard(
            environment,
            new DeploymentProperties(),
            new SeederProperties(),
            mock(DataSource.class)
        );

        IllegalStateException exception =
            assertThrows(IllegalStateException.class, guard::verify);

        assertTrue(exception.getMessage().contains("spring.flyway.schemas"));
    }

    @Test
    void refusesFlywaySchemaWithCaseOnlyDatabaseMismatch() {
        MockEnvironment environment = seederEnvironment()
            .withProperty(
                "spring.datasource.url",
                "jdbc:mysql://127.0.0.1:3313/Connex_Seeder?sslMode=DISABLED"
            )
            .withProperty("spring.flyway.schemas", "connex_seeder");
        SeederGuard guard = new SeederGuard(
            environment,
            new DeploymentProperties(),
            new SeederProperties(),
            mock(DataSource.class)
        );

        IllegalStateException exception =
            assertThrows(IllegalStateException.class, guard::verify);

        assertTrue(exception.getMessage().contains("spring.flyway.schemas"));
    }

    @Test
    void permitsCommaBearingScalarFlywayDefaultSchemaWhenItExactlyMatchesTarget()
            throws SQLException {
        MockEnvironment environment = seederEnvironment()
            .withProperty(
                "spring.datasource.url",
                "jdbc:mysql://127.0.0.1:3313/connex_seeder,archive?sslMode=DISABLED"
            )
            .withProperty("spring.flyway.default-schema", "connex_seeder,archive");
        SeederGuard guard = new SeederGuard(
            environment,
            new DeploymentProperties(),
            new SeederProperties(),
            dataSourceAt(
                "jdbc:mysql://127.0.0.1:3313/connex_seeder,archive?sslMode=DISABLED",
                "connex_seeder,archive",
                null
            )
        );

        assertDoesNotThrow(() -> guard.verify());
    }

    @Test
    void refusesCommaSeparatedFlywaySchemasWhenAnyEntryDiffers() {
        MockEnvironment environment = seederEnvironment()
            .withProperty(
                "spring.datasource.url",
                "jdbc:mysql://127.0.0.1:3313/connex_seeder?sslMode=DISABLED"
            )
            .withProperty("spring.flyway.schemas", "connex_seeder,connex_archive");
        SeederGuard guard = new SeederGuard(
            environment,
            new DeploymentProperties(),
            new SeederProperties(),
            mock(DataSource.class)
        );

        IllegalStateException exception =
            assertThrows(IllegalStateException.class, guard::verify);

        assertTrue(exception.getMessage().contains("spring.flyway.schemas"));
    }

    @Test
    void permitsFlywaySchemaThatIsTheConfiguredTargetDatabase() throws SQLException {
        MockEnvironment environment = seederEnvironment()
            .withProperty(
                "spring.datasource.url",
                "jdbc:mysql://127.0.0.1:3313/connex_seeder?sslMode=DISABLED"
            )
            .withProperty("spring.flyway.schemas", "connex_seeder")
            .withProperty("spring.flyway.default-schema", "connex_seeder");
        SeederGuard guard = new SeederGuard(
            environment,
            new DeploymentProperties(),
            new SeederProperties(),
            seederDataSource("jdbc:mysql://127.0.0.1:3313/connex_seeder?sslMode=DISABLED")
        );

        assertDoesNotThrow(() -> guard.verify());
    }

    @Test
    void permitsExactMixedCaseDatabaseAgreementAndCaseInsensitiveHostAgreement()
            throws SQLException {
        MockEnvironment environment = seederEnvironment()
            .withProperty(
                "spring.datasource.url",
                "jdbc:mysql://DB.EXAMPLE.TEST:3306/Connex_Seeder?sslMode=VERIFY_IDENTITY"
            )
            .withProperty(
                "spring.flyway.url",
                "jdbc:mysql://db.example.test/Connex_Seeder?sslMode=VERIFY_IDENTITY"
            )
            .withProperty("connex.seeder.allow-remote-host", "true")
            .withProperty("spring.flyway.schemas", "Connex_Seeder")
            .withProperty("spring.flyway.default-schema", "Connex_Seeder");
        SeederGuard guard = new SeederGuard(
            environment,
            new DeploymentProperties(),
            new SeederProperties(),
            dataSourceAt(
                "jdbc:mysql://db.example.test:3306/Connex_Seeder?sslMode=VERIFY_IDENTITY",
                "Connex_Seeder",
                null
            )
        );

        assertDoesNotThrow(() -> guard.verify());
    }

    @Test
    void refusesFlywayInitSqlThatCouldSwitchTheSessionDatabase() throws SQLException {
        MockEnvironment environment = seederEnvironment()
            .withProperty(
                "spring.datasource.url",
                "jdbc:mysql://127.0.0.1:3313/connex_seeder?sslMode=DISABLED"
            )
            .withProperty("spring.flyway.init-sqls", "USE connex_dev");
        SeederGuard guard = new SeederGuard(
            environment,
            new DeploymentProperties(),
            new SeederProperties(),
            seederDataSource("jdbc:mysql://127.0.0.1:3313/connex_seeder?sslMode=DISABLED")
        );

        IllegalStateException exception =
            assertThrows(IllegalStateException.class, () -> guard.verify());

        assertTrue(exception.getMessage().contains("spring.flyway.init-sqls"));
    }

    @Test
    void refusesFlywayInitSqlDeclaredAsAnIndexedList() throws SQLException {
        MockEnvironment environment = seederEnvironment()
            .withProperty(
                "spring.datasource.url",
                "jdbc:mysql://127.0.0.1:3313/connex_seeder?sslMode=DISABLED"
            )
            .withProperty("spring.flyway.init-sqls[0]", "USE connex_dev");
        SeederGuard guard = new SeederGuard(
            environment,
            new DeploymentProperties(),
            new SeederProperties(),
            seederDataSource("jdbc:mysql://127.0.0.1:3313/connex_seeder?sslMode=DISABLED")
        );

        IllegalStateException exception =
            assertThrows(IllegalStateException.class, () -> guard.verify());

        assertTrue(exception.getMessage().contains("spring.flyway.init-sqls"));
    }

    @Test
    void refusesApplicationMetadataHostPortAndExactDatabaseMismatches() throws SQLException {
        for (String metadataUrl : new String[] {
            "jdbc:mysql://localhost:3313/connex_seeder?sslMode=DISABLED",
            "jdbc:mysql://127.0.0.1:3306/connex_seeder?sslMode=DISABLED",
            "jdbc:mysql://127.0.0.1:3313/connex_dev?sslMode=DISABLED",
            "jdbc:mysql://127.0.0.1:3313/CONNEX_SEEDER?sslMode=DISABLED"
        }) {
            MockEnvironment environment = seederEnvironment().withProperty(
                "spring.datasource.url",
                "jdbc:mysql://127.0.0.1:3313/connex_seeder?sslMode=DISABLED"
            );
            SeederGuard guard = new SeederGuard(
                environment,
                new DeploymentProperties(),
                new SeederProperties(),
                dataSourceAt(metadataUrl, "connex_seeder", null)
            );

            IllegalStateException exception =
                assertThrows(IllegalStateException.class, guard::verify);

            assertTrue(exception.getMessage().contains("effective datasource metadata URL"));
        }
    }

    @Test
    void refusesFlywayMetadataHostPortAndExactDatabaseMismatches() throws SQLException {
        for (String metadataUrl : new String[] {
            "jdbc:mysql://localhost:3313/connex_seeder?sslMode=DISABLED",
            "jdbc:mysql://127.0.0.1:3306/connex_seeder?sslMode=DISABLED",
            "jdbc:mysql://127.0.0.1:3313/connex_dev?sslMode=DISABLED",
            "jdbc:mysql://127.0.0.1:3313/CONNEX_SEEDER?sslMode=DISABLED"
        }) {
            MockEnvironment environment = seederEnvironment().withProperty(
                "spring.datasource.url",
                "jdbc:mysql://127.0.0.1:3313/connex_seeder?sslMode=DISABLED"
            );
            DataSource flywayDataSource = dataSourceAt(
                metadataUrl,
                "connex_seeder",
                null
            );
            SeederGuard guard = new SeederGuard(
                environment,
                new DeploymentProperties(),
                new SeederProperties(),
                seederDataSource(
                    "jdbc:mysql://127.0.0.1:3313/connex_seeder?sslMode=DISABLED"
                )
            );

            IllegalStateException exception =
                assertThrows(IllegalStateException.class, () -> guard.verify(flywayDataSource));

            assertTrue(exception.getMessage().contains("effective datasource metadata URL"));
        }
    }

    @Test
    void refusesEffectiveCatalogThatIsNotTheConfiguredTargetDatabase() throws SQLException {
        MockEnvironment environment = seederEnvironment().withProperty(
            "spring.datasource.url",
            "jdbc:mysql://127.0.0.1:3313/connex_seeder?sslMode=DISABLED"
        );
        SeederGuard guard = new SeederGuard(
            environment,
            new DeploymentProperties(),
            new SeederProperties(),
            dataSourceAt(
                "jdbc:mysql://127.0.0.1:3313/connex_seeder?sslMode=DISABLED",
                "connex_dev",
                null
            )
        );

        IllegalStateException exception =
            assertThrows(IllegalStateException.class, () -> guard.verify());

        assertTrue(exception.getMessage().contains("exact configured target"));
    }

    @Test
    void refusesCaseOnlyCurrentCatalogMismatch() throws SQLException {
        MockEnvironment environment = seederEnvironment().withProperty(
            "spring.datasource.url",
            "jdbc:mysql://127.0.0.1:3313/Connex_Seeder?sslMode=DISABLED"
        );
        SeederGuard guard = new SeederGuard(
            environment,
            new DeploymentProperties(),
            new SeederProperties(),
            dataSourceAt(
                "jdbc:mysql://127.0.0.1:3313/Connex_Seeder?sslMode=DISABLED",
                "connex_seeder",
                null
            )
        );

        IllegalStateException exception =
            assertThrows(IllegalStateException.class, guard::verify);

        assertTrue(exception.getMessage().contains("exact configured target"));
    }

    @Test
    void refusesFlywayDataSourceAttachedToAnotherCatalogThanTheApplicationTarget()
            throws SQLException {
        MockEnvironment environment = seederEnvironment().withProperty(
            "spring.datasource.url",
            "jdbc:mysql://127.0.0.1:3313/connex_seeder?sslMode=DISABLED"
        );
        DataSource flywayDataSource = dataSourceAt(
            "jdbc:mysql://127.0.0.1:3313/connex_seeder?sslMode=DISABLED",
            "connex_dev",
            null
        );
        SeederGuard guard = new SeederGuard(
            environment,
            new DeploymentProperties(),
            new SeederProperties(),
            seederDataSource("jdbc:mysql://127.0.0.1:3313/connex_seeder?sslMode=DISABLED")
        );

        IllegalStateException exception =
            assertThrows(IllegalStateException.class, () -> guard.verify(flywayDataSource));

        assertTrue(exception.getMessage().contains("exact configured target"));
    }

    @Test
    void refusesEffectiveConnectionThatReportsNoCurrentDatabase() throws SQLException {
        MockEnvironment environment = seederEnvironment().withProperty(
            "spring.datasource.url",
            "jdbc:mysql://127.0.0.1:3313/connex_seeder?sslMode=DISABLED"
        );
        SeederGuard guard = new SeederGuard(
            environment,
            new DeploymentProperties(),
            new SeederProperties(),
            dataSourceAt("jdbc:mysql://127.0.0.1:3313/connex_seeder?sslMode=DISABLED", null, null)
        );

        IllegalStateException exception =
            assertThrows(IllegalStateException.class, () -> guard.verify());

        assertTrue(exception.getMessage().contains("no current database"));
    }

    @Test
    void permitsMatchingServerDatabaseWhenCachedCatalogIsAbsent() throws SQLException {
        MockEnvironment environment = seederEnvironment().withProperty(
            "spring.datasource.url",
            "jdbc:mysql://127.0.0.1:3313/connex_seeder?sslMode=DISABLED"
        );
        SeederGuard guard = new SeederGuard(
            environment,
            new DeploymentProperties(),
            new SeederProperties(),
            dataSourceAt(
                "jdbc:mysql://127.0.0.1:3313/connex_seeder?sslMode=DISABLED",
                null,
                "connex_seeder"
            )
        );

        assertDoesNotThrow(() -> guard.verify());
    }

    @Test
    void checksSharedApplicationAndFlywayDatasourceOnlyOnce() throws SQLException {
        MockEnvironment environment = seederEnvironment().withProperty(
            "spring.datasource.url",
            "jdbc:mysql://127.0.0.1:3313/connex_seeder?sslMode=DISABLED"
        );
        DataSource sharedDataSource =
            seederDataSource("jdbc:mysql://127.0.0.1:3313/connex_seeder?sslMode=DISABLED");
        SeederGuard guard = new SeederGuard(
            environment,
            new DeploymentProperties(),
            new SeederProperties(),
            sharedDataSource
        );

        guard.verify(sharedDataSource);

        verify(sharedDataSource).getConnection();
    }

    private static MockEnvironment seederEnvironment() {
        MockEnvironment environment = new MockEnvironment()
            .withProperty("connex.seeder.enabled", "true")
            .withProperty("connex.maintenance.mode", "seeder")
            .withProperty("spring.main.web-application-type", "none")
            .withProperty("connex.tenancy.routing.mode", "single-database")
            .withProperty("connex.object-storage.legacy-migration.mode", "off");
        environment.getPropertySources().addLast(new MapPropertySource(
            "Config resource 'class path resource [application-seeder.yml]'",
            SeederStartupConfigurationValidatorTest.safeRepositoryProperties()
        ));
        environment.setActiveProfiles("seeder");
        return environment;
    }

    private static DataSource seederDataSource(String url) throws SQLException {
        return dataSourceAt(url, "connex_seeder", null);
    }

    private static DataSource dataSourceAt(String url, String catalog, String schema)
            throws SQLException {
        String serverDatabase = catalog == null ? schema : catalog;
        return dataSourceAt(url, catalog, schema, serverDatabase);
    }

    private static DataSource dataSourceAt(
            String url,
            String catalog,
            String schema,
            String serverDatabase) throws SQLException {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        DatabaseMetaData metaData = mock(DatabaseMetaData.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet result = mock(ResultSet.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getMetaData()).thenReturn(metaData);
        when(metaData.getURL()).thenReturn(url);
        when(connection.getCatalog()).thenReturn(catalog);
        when(connection.getSchema()).thenReturn(schema);
        when(connection.prepareStatement("SELECT DATABASE()")).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(result);
        when(result.next()).thenReturn(true);
        when(result.getString(1)).thenReturn(serverDatabase);
        return dataSource;
    }

    private static void assertCleanRefusal(
            IllegalStateException exception,
            String expectedMessage) {
        assertEquals(expectedMessage, exception.getMessage());
        assertNull(exception.getCause());
        assertEquals(0, exception.getSuppressed().length);
    }

    @Test
    void refusesAmbiguousMysqlTargetEvenWithRemoteOverride() {
        assertThrows(
            IllegalStateException.class,
            () -> SeederGuard.verifyJdbcUrl(
                "jdbc:mysql:loadbalance://db-one,db-two/connex_seed",
                true
            )
        );
    }
}
