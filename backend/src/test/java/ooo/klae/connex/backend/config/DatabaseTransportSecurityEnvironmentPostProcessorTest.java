package ooo.klae.connex.backend.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class DatabaseTransportSecurityEnvironmentPostProcessorTest {

    private final DatabaseTransportSecurityEnvironmentPostProcessor postProcessor =
        new DatabaseTransportSecurityEnvironmentPostProcessor();

    @Test
    void allowsDevProfileWithoutDatasourceProperties() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("dev");

        assertDoesNotThrow(() -> postProcessor.postProcessEnvironment(environment, null));
    }

    @Test
    void allowsTestProfileWithoutDatasourceProperties() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("test");

        assertDoesNotThrow(() -> postProcessor.postProcessEnvironment(environment, null));
    }

    @Test
    void failsOutsideDevAndTestWhenDatasourceUrlIsMissing() {
        MockEnvironment environment = productionEnvironment()
            .withProperty("spring.datasource.username", "connex")
            .withProperty("spring.datasource.password", "x");

        assertThrows(IllegalStateException.class, () -> postProcessor.postProcessEnvironment(environment, null));
    }

    @Test
    void failsOutsideDevAndTestWhenDatasourcePasswordIsBlank() {
        MockEnvironment environment = productionEnvironment()
            .withProperty("spring.datasource.url", "jdbc:mysql://db.example.com:3306/connexdb?sslMode=VERIFY_CA")
            .withProperty("spring.datasource.username", "connex")
            .withProperty("spring.datasource.password", " ");

        assertThrows(IllegalStateException.class, () -> postProcessor.postProcessEnvironment(environment, null));
    }

    @Test
    void failsOutsideDevAndTestForNonMysqlDatasourceUrl() {
        MockEnvironment environment = productionEnvironment()
            .withProperty("spring.datasource.url", "jdbc:mariadb://db.example.com:3306/connexdb?sslMode=VERIFY_CA")
            .withProperty("spring.datasource.username", "connex")
            .withProperty("spring.datasource.password", "x");

        assertThrows(IllegalStateException.class, () -> postProcessor.postProcessEnvironment(environment, null));
    }

    @Test
    void failsOutsideDevAndTestWhenSslModeIsMissing() {
        MockEnvironment environment = productionEnvironment()
            .withProperty("spring.datasource.url", "jdbc:mysql://db.example.com:3306/connexdb")
            .withProperty("spring.datasource.username", "connex")
            .withProperty("spring.datasource.password", "x");

        assertThrows(IllegalStateException.class, () -> postProcessor.postProcessEnvironment(environment, null));
    }

    @Test
    void failsOutsideDevAndTestWhenSslModeDoesNotVerifyCertificateAuthority() {
        MockEnvironment environment = productionEnvironment()
            .withProperty("spring.datasource.url", "jdbc:mysql://db.example.com:3306/connexdb?sslMode=REQUIRED")
            .withProperty("spring.datasource.username", "connex")
            .withProperty("spring.datasource.password", "x");

        assertThrows(IllegalStateException.class, () -> postProcessor.postProcessEnvironment(environment, null));
    }

    @Test
    void failsOutsideDevAndTestWhenLegacyTlsModeQueryParameterCanConflictWithVerifiedSslMode() {
        MockEnvironment environment = productionEnvironment()
            .withProperty(
                "spring.datasource.url",
                "jdbc:mysql://db.example.com:3306/connexdb?sslMode=VERIFY_CA&useSSL=false"
            )
            .withProperty("spring.datasource.username", "connex")
            .withProperty("spring.datasource.password", "x");

        assertThrows(IllegalStateException.class, () -> postProcessor.postProcessEnvironment(environment, null));
    }

    @Test
    void failsOutsideDevAndTestWhenSslModeIsAmbiguous() {
        MockEnvironment environment = productionEnvironment()
            .withProperty(
                "spring.datasource.url",
                "jdbc:mysql://db.example.com:3306/connexdb?sslMode=VERIFY_CA&sslMode=DISABLED"
            )
            .withProperty("spring.datasource.username", "connex")
            .withProperty("spring.datasource.password", "x");

        assertThrows(IllegalStateException.class, () -> postProcessor.postProcessEnvironment(environment, null));
    }

    @Test
    void failsOutsideDevAndTestWhenHostSpecificSslModeCanOverrideVerifiedGlobalMode() {
        MockEnvironment environment = productionEnvironment()
            .withProperty(
                "spring.datasource.url",
                "jdbc:mysql://address=(host=db.example.com)(port=3306)(sslMode=DISABLED)/connexdb?sslMode=VERIFY_CA"
            )
            .withProperty("spring.datasource.username", "connex")
            .withProperty("spring.datasource.password", "x");

        assertThrows(IllegalStateException.class, () -> postProcessor.postProcessEnvironment(environment, null));
    }

    @Test
    void failsOutsideDevAndTestWhenEncodedHostSpecificSslModeCanOverrideVerifiedGlobalMode() {
        MockEnvironment environment = productionEnvironment()
            .withProperty(
                "spring.datasource.url",
                "jdbc:mysql://address=(host=db.example.com)(port=3306)(ssl%4dode=DISABLED)/connexdb?sslMode=VERIFY_CA"
            )
            .withProperty("spring.datasource.username", "connex")
            .withProperty("spring.datasource.password", "x");

        assertThrows(IllegalStateException.class, () -> postProcessor.postProcessEnvironment(environment, null));
    }

    @Test
    void failsOutsideDevAndTestWhenHostSpecificLegacyTlsModeCanConflictWithVerifiedSslMode() {
        MockEnvironment environment = productionEnvironment()
            .withProperty(
                "spring.datasource.url",
                "jdbc:mysql://address=(host=db.example.com)(port=3306)(useSSL=false)/connexdb?sslMode=VERIFY_CA"
            )
            .withProperty("spring.datasource.username", "connex")
            .withProperty("spring.datasource.password", "x");

        assertThrows(IllegalStateException.class, () -> postProcessor.postProcessEnvironment(environment, null));
    }

    @Test
    void failsOutsideDevAndTestWhenHikariJdbcUrlCanOverrideDatasourceUrl() {
        MockEnvironment environment = productionEnvironment()
            .withProperty("spring.datasource.url", "jdbc:mysql://db.example.com:3306/connexdb?sslMode=VERIFY_CA")
            .withProperty("spring.datasource.username", "connex")
            .withProperty("spring.datasource.password", "x")
            .withProperty("spring.datasource.hikari.jdbc-url", "jdbc:mysql://db.example.com:3306/connexdb");

        assertThrows(IllegalStateException.class, () -> postProcessor.postProcessEnvironment(environment, null));
    }

    @Test
    void failsOutsideDevAndTestWhenHikariJdbcUrlIsBlank() {
        MockEnvironment environment = productionEnvironment()
            .withProperty("spring.datasource.url", "jdbc:mysql://db.example.com:3306/connexdb?sslMode=VERIFY_CA")
            .withProperty("spring.datasource.username", "connex")
            .withProperty("spring.datasource.password", "x")
            .withProperty("spring.datasource.hikari.jdbc-url", " ");

        assertThrows(IllegalStateException.class, () -> postProcessor.postProcessEnvironment(environment, null));
    }

    @Test
    void failsOutsideDevAndTestWhenHikariDataSourceClassCanBypassValidatedUrl() {
        MockEnvironment environment = productionEnvironment()
            .withProperty("spring.datasource.url", "jdbc:mysql://db.example.com:3306/connexdb?sslMode=VERIFY_CA")
            .withProperty("spring.datasource.username", "connex")
            .withProperty("spring.datasource.password", "x")
            .withProperty("spring.datasource.hikari.data-source-class-name", "com.mysql.cj.jdbc.MysqlDataSource");

        assertThrows(IllegalStateException.class, () -> postProcessor.postProcessEnvironment(environment, null));
    }

    @Test
    void failsOutsideDevAndTestWhenDatasourceTypeCanBypassValidatedUrl() {
        MockEnvironment environment = productionEnvironment()
            .withProperty("spring.datasource.url", "jdbc:mysql://db.example.com:3306/connexdb?sslMode=VERIFY_CA")
            .withProperty("spring.datasource.username", "connex")
            .withProperty("spring.datasource.password", "x")
            .withProperty("spring.datasource.type", "com.mysql.cj.jdbc.MysqlDataSource");

        assertThrows(IllegalStateException.class, () -> postProcessor.postProcessEnvironment(environment, null));
    }

    @Test
    void failsOutsideDevAndTestWhenHikariJndiCanBypassValidatedUrl() {
        MockEnvironment environment = productionEnvironment()
            .withProperty("spring.datasource.url", "jdbc:mysql://db.example.com:3306/connexdb?sslMode=VERIFY_CA")
            .withProperty("spring.datasource.username", "connex")
            .withProperty("spring.datasource.password", "x")
            .withProperty("spring.datasource.hikari.data-source-j-n-d-i", "jdbc/connex");

        assertThrows(IllegalStateException.class, () -> postProcessor.postProcessEnvironment(environment, null));
    }

    @Test
    void failsOutsideDevAndTestWhenHikariDatasourcePropertiesCanOverrideSslMode() {
        MockEnvironment environment = productionEnvironment()
            .withProperty("spring.datasource.url", "jdbc:mysql://db.example.com:3306/connexdb?sslMode=VERIFY_CA")
            .withProperty("spring.datasource.username", "connex")
            .withProperty("spring.datasource.password", "x")
            .withProperty("spring.datasource.hikari.data-source-properties.sslMode", "DISABLED");

        assertThrows(IllegalStateException.class, () -> postProcessor.postProcessEnvironment(environment, null));
    }

    @Test
    void failsOutsideDevAndTestWhenEnvironmentStyleHikariDatasourcePropertiesCanOverrideSslMode() {
        MockEnvironment environment = productionEnvironment()
            .withProperty("spring.datasource.url", "jdbc:mysql://db.example.com:3306/connexdb?sslMode=VERIFY_CA")
            .withProperty("spring.datasource.username", "connex")
            .withProperty("spring.datasource.password", "x")
            .withProperty("SPRING_DATASOURCE_HIKARI_DATA_SOURCE_PROPERTIES_SSLMODE", "DISABLED");

        assertThrows(IllegalStateException.class, () -> postProcessor.postProcessEnvironment(environment, null));
    }

    @Test
    void allowsVerifyCaOutsideDevAndTest() {
        MockEnvironment environment = productionEnvironment()
            .withProperty("spring.datasource.url", "jdbc:mysql://db.example.com:3306/connexdb?sslMode=VERIFY_CA")
            .withProperty("spring.datasource.username", "connex")
            .withProperty("spring.datasource.password", "x");

        assertDoesNotThrow(() -> postProcessor.postProcessEnvironment(environment, null));
    }

    @Test
    void allowsVerifiedHikariJdbcUrlOutsideDevAndTest() {
        MockEnvironment environment = productionEnvironment()
            .withProperty("spring.datasource.url", "jdbc:mysql://db.example.com:3306/connexdb?sslMode=VERIFY_CA")
            .withProperty("spring.datasource.username", "connex")
            .withProperty("spring.datasource.password", "x")
            .withProperty("spring.datasource.hikari.jdbc-url", "jdbc:mysql://db.example.com:3306/connexdb?sslMode=VERIFY_CA");

        assertDoesNotThrow(() -> postProcessor.postProcessEnvironment(environment, null));
    }

    @Test
    void allowsVerifyIdentityOutsideDevAndTest() {
        MockEnvironment environment = productionEnvironment()
            .withProperty(
                "spring.datasource.url",
                "jdbc:mysql://db.example.com:3306/connexdb?createDatabaseIfNotExist=true;sslMode=verify_identity"
            )
            .withProperty("spring.datasource.username", "connex")
            .withProperty("spring.datasource.password", "x");

        assertDoesNotThrow(() -> postProcessor.postProcessEnvironment(environment, null));
    }

    private static MockEnvironment productionEnvironment() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");
        return environment;
    }
}
