package ooo.klae.connex.backend.seeder;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import ooo.klae.connex.backend.config.DeploymentProperties;

class SeederGuardTest {

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
    void refusesFlywaySchemaNamingTheProtectedProductionDatabase() {
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
        when(connection.getCatalog()).thenReturn("connex_pub");
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

    private static MockEnvironment seederEnvironment() {
        MockEnvironment environment = new MockEnvironment()
            .withProperty("connex.maintenance.mode", "seeder")
            .withProperty("spring.main.web-application-type", "none");
        environment.setActiveProfiles("seeder");
        return environment;
    }

    private static DataSource seederDataSource(String url) throws SQLException {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        DatabaseMetaData metaData = mock(DatabaseMetaData.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getMetaData()).thenReturn(metaData);
        when(metaData.getURL()).thenReturn(url);
        when(connection.getCatalog()).thenReturn("connex_seeder");
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
