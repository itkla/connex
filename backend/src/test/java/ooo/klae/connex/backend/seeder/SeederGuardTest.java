package ooo.klae.connex.backend.seeder;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
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
        "socketFactory"
    };

    @Test
    void refusesProtectedProductionDatabaseCaseInsensitively() {
        IllegalStateException exception = assertThrows(
            IllegalStateException.class,
            () -> SeederGuard.verifyJdbcUrl(
                "jdbc:mysql://127.0.0.1:3313/CoNnEx_PuB?sslMode=DISABLED",
                false
            )
        );

        assertTrue(exception.getMessage().contains("connex_pub"));
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

        assertTrue(exception.getMessage().contains("dbname"));
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

            assertTrue(exception.getMessage().contains("query parameter"), selector);
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

            assertTrue(exception.getMessage().contains("query parameter"), selector);
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

        assertTrue(exception.getMessage().contains("host"));
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

            assertTrue(exception.getMessage().contains("connex_pub"));
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
    void permitsAbsentAndWhitespaceOnlyHikariConnectionTestQuery() throws SQLException {
        for (String statement : new String[] {null, " \t "}) {
            MockEnvironment environment = seederEnvironment().withProperty(
                "spring.datasource.url",
                "jdbc:mysql://127.0.0.1:3313/connex_seeder?sslMode=DISABLED"
            );
            if (statement != null) {
                environment.withProperty(
                    "spring.datasource.hikari.connection-test-query",
                    statement
                );
            }
            SeederGuard guard = new SeederGuard(
                environment,
                new DeploymentProperties(),
                new SeederProperties(),
                seederDataSource(
                    "jdbc:mysql://127.0.0.1:3313/connex_seeder?sslMode=DISABLED"
                )
            );

            assertDoesNotThrow(() -> guard.verify());
        }
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
        assertTrue(exception.getMessage().contains("scratch"));
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
        assertTrue(exception.getMessage().contains("connex_pub"));
    }

    @Test
    void refusesEveryExplicitlyConfiguredDeploymentProfile() {
        for (String profile : new String[] {"saas", "silo", "on-prem"}) {
            DeploymentProperties deployment = new DeploymentProperties();
            deployment.setProfile(profile);
            SeederGuard guard = new SeederGuard(
                seederEnvironment(),
                deployment,
                new SeederProperties(),
                mock(DataSource.class)
            );

            IllegalStateException exception =
                assertThrows(IllegalStateException.class, guard::verify);

            assertTrue(exception.getMessage().contains(profile));
        }
    }

    @Test
    void refusesEffectiveProductionCatalogEvenWithLoopbackUrl() throws SQLException {
        MockEnvironment environment = seederEnvironment().withProperty(
            "spring.datasource.url",
            "jdbc:mysql://127.0.0.1:3313/connex_seeder?sslMode=DISABLED"
        );
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        DatabaseMetaData metaData = mock(DatabaseMetaData.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getMetaData()).thenReturn(metaData);
        when(metaData.getURL()).thenReturn("jdbc:mysql://127.0.0.1:3313/connex_seeder?sslMode=DISABLED");
        when(connection.getCatalog()).thenReturn("CoNnEx_PuB");
        SeederGuard guard = new SeederGuard(
            environment,
            new DeploymentProperties(),
            new SeederProperties(),
            dataSource
        );

        IllegalStateException exception =
            assertThrows(IllegalStateException.class, guard::verify);

        assertTrue(exception.getMessage().contains("catalog connex_pub"));
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
            );
        SeederProperties properties = new SeederProperties();
        properties.setAllowRemoteHost(true);
        SeederGuard guard = new SeederGuard(
            environment,
            new DeploymentProperties(),
            properties,
            mock(DataSource.class)
        );

        IllegalStateException exception =
            assertThrows(IllegalStateException.class, guard::verify);

        assertTrue(exception.getMessage().contains("disagree"));
    }

    @Test
    void refusesConfiguredUrlsThatSpellTheLoopbackHostDifferently() {
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

        assertTrue(exception.getMessage().contains("disagree"));
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
                "jdbc:mysql://localhost:3306/Connex_Seeder?sslMode=DISABLED"
            )
            .withProperty(
                "spring.flyway.url",
                "jdbc:mysql://LOCALHOST:3306/connex_seeder?sslMode=DISABLED"
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
        assertTrue(exception.getMessage().contains("connex_dev"));
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
        assertTrue(exception.getMessage().contains("connex_dev"));
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
        assertTrue(exception.getMessage().contains("Connex_Seeder"));
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

        assertTrue(exception.getMessage().contains("connex_archive"));
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
                "jdbc:mysql://LOCALHOST:3306/Connex_Seeder?sslMode=DISABLED"
            )
            .withProperty(
                "spring.flyway.url",
                "jdbc:mysql://localhost/Connex_Seeder?sslMode=DISABLED"
            )
            .withProperty("spring.flyway.schemas", "Connex_Seeder")
            .withProperty("spring.flyway.default-schema", "Connex_Seeder");
        SeederGuard guard = new SeederGuard(
            environment,
            new DeploymentProperties(),
            new SeederProperties(),
            dataSourceAt(
                "jdbc:mysql://localhost:3306/Connex_Seeder?sslMode=DISABLED",
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

        assertTrue(exception.getMessage().contains("connex_dev"));
        assertTrue(exception.getMessage().contains("connex_seeder"));
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

        assertTrue(exception.getMessage().contains("connex_seeder"));
        assertTrue(exception.getMessage().contains("Connex_Seeder"));
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

        assertTrue(exception.getMessage().contains("connex_dev"));
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
    void permitsEffectiveDatabaseReportedOnlyAsASchema() throws SQLException {
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
            .withProperty("connex.maintenance.mode", "seeder")
            .withProperty("spring.main.web-application-type", "none");
        environment.setActiveProfiles("seeder");
        return environment;
    }

    private static DataSource seederDataSource(String url) throws SQLException {
        return dataSourceAt(url, "connex_seeder", null);
    }

    private static DataSource dataSourceAt(String url, String catalog, String schema)
            throws SQLException {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        DatabaseMetaData metaData = mock(DatabaseMetaData.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getMetaData()).thenReturn(metaData);
        when(metaData.getURL()).thenReturn(url);
        when(connection.getCatalog()).thenReturn(catalog);
        when(connection.getSchema()).thenReturn(schema);
        return dataSource;
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
