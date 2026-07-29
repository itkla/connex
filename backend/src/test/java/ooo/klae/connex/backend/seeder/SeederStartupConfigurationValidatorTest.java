package ooo.klae.connex.backend.seeder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.env.MockEnvironment;

class SeederStartupConfigurationValidatorTest {

    private static final String SENSITIVE_CONNECTOR_VALUE_SENTINEL =
        "sensitive-connector-value-sentinel-7429";

    @Test
    void validatesThePinnedSafeSeederConfiguration() {
        SeederStartupConfigurationValidator.ValidatedConfiguration configuration =
            SeederStartupConfigurationValidator.validate(safeEnvironment());

        assertEquals("127.0.0.1", configuration.target().host());
        assertEquals(3306, configuration.target().port());
        assertEquals("Connex_Seeder", configuration.target().database());
        assertFalse(configuration.allowRemoteHost());
    }

    @Test
    void explicitFalseAloneDoesNotActivateTheSeederBoundary() {
        MockEnvironment environment = new MockEnvironment()
            .withProperty("connex.seeder.enabled", "false");

        Optional<SeederStartupConfigurationValidator.ValidatedConfiguration> result =
            SeederStartupConfigurationValidator.validateIfActivated(environment);

        assertTrue(result.isEmpty());
    }

    @Test
    void activatesOnAnyGenuineRelaxedSignalAndRefusesConflicts() {
        MockEnvironment environment = safeEnvironment()
            .withProperty("CONNEX_SEEDER_ENABLED", "false")
            .withProperty("connex_seeder_enabled", "true");

        IllegalStateException exception = assertThrows(
            IllegalStateException.class,
            () -> SeederStartupConfigurationValidator.validateIfActivated(environment)
        );

        assertTrue(exception.getMessage().contains("conflicting relaxed aliases"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"dev", "test"})
    void refusesEverySeederCoprofile(String coProfile) {
        MockEnvironment environment = safeEnvironment();
        environment.setActiveProfiles("seeder", coProfile);

        IllegalStateException exception = assertThrows(
            IllegalStateException.class,
            () -> SeederStartupConfigurationValidator.validate(environment)
        );

        assertTrue(exception.getMessage().contains("only active Spring profile"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "address",
        "databaseName",
        "dbname",
        "dnsSrv",
        "host",
        "namedPipePath",
        "path",
        "port",
        "protocol",
        "serverName",
        "type",
        "url",
        "jdbcUrl",
        "useConfigs",
        "parseInfoCacheFactory",
        "queryInfoCacheFactory",
        "connect.timeout",
        "propertiesTransform",
        "socketFactory",
        "sessionVariables",
        "clientInfoProvider",
        "xdevapi.ssl-mode",
        "unknownFutureProperty"
    })
    void refusesRoutingExecutableCompatibilityAndUnknownQueryProperties(String propertyName) {
        MockEnvironment environment = safeEnvironment()
            .withProperty(
                "spring.datasource.url",
                "jdbc:mysql://127.0.0.1/Connex_Seeder?" + propertyName + "=unsafe"
            );

        IllegalStateException exception = assertThrows(
            IllegalStateException.class,
            () -> SeederStartupConfigurationValidator.validate(environment)
        );

        assertTrue(exception.getMessage().contains("Connector/J query property"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "clientCertificateKeyStoreUrl",
        "clientCertificateKeyStorePassword",
        "connectionAttributes",
        "CLIENTCERTIFICATEKEYSTOREURL",
        "CONNECTIONATTRIBUTES",
        "TRUSTCERTIFICATEKEYSTOREURL",
        "client-certificate-key-store-url",
        "client_certificate_key_store_url",
        "connection-attributes",
        "connection_attributes",
        "trustCertificateKeyStoreUrl",
        "trust-certificate-key-store-url",
        "trust_certificate_key_store_url",
        "trustCertificateKeyStorePassword"
    })
    void refusesSensitiveConnectorPropertiesInJdbcQueryParameters(String propertyName) {
        MockEnvironment environment = safeEnvironment()
            .withProperty(
                "spring.datasource.url",
                "jdbc:mysql://127.0.0.1/Connex_Seeder?sslMode=DISABLED&"
                    + propertyName + "=" + SENSITIVE_CONNECTOR_VALUE_SENTINEL
            );

        assertSanitizedConnectorRefusal(
            environment,
            "Connector/J query property"
        );
    }

    @Test
    void refusesEncodedSensitiveConnectorQueryProperties() {
        for (String encodedProperty : new String[] {
            "connection%41ttributes",
            "clientCertificateKeyStore%55rl",
            "trustCertificateKeyStore%55rl"
        }) {
            MockEnvironment environment = safeEnvironment()
                .withProperty(
                    "spring.datasource.url",
                    "jdbc:mysql://127.0.0.1/Connex_Seeder?sslMode=DISABLED&"
                        + encodedProperty + "=https%3A%2F%2F127.0.0.1%2F"
                        + SENSITIVE_CONNECTOR_VALUE_SENTINEL
                );

            assertSanitizedConnectorRefusal(
                environment,
                "Connector/J query property"
            );
        }
    }

    @Test
    void refusesSensitiveConnectorPropertiesAcrossRelaxedDriverMapForms() {
        for (String propertyPrefix : new String[] {
            "spring.datasource.hikari.data-source-properties",
            "spring.datasource.hikari.dataSourceProperties",
            "spring.datasource.hikari.data_source_properties",
            "spring.flyway.jdbc-properties",
            "spring.flyway.jdbcProperties",
            "spring.flyway.jdbc_properties"
        }) {
            for (String propertyName : new String[] {
                "clientCertificateKeyStoreUrl",
                "clientCertificateKeyStorePassword",
                "connectionAttributes",
                "CLIENTCERTIFICATEKEYSTOREURL",
                "CONNECTIONATTRIBUTES",
                "TRUSTCERTIFICATEKEYSTOREURL",
                "client-certificate-key-store-url",
                "client_certificate_key_store_url",
                "connection-attributes",
                "connection_attributes",
                "trustCertificateKeyStoreUrl",
                "trust-certificate-key-store-url",
                "trust_certificate_key_store_url",
                "trustCertificateKeyStorePassword"
            }) {
                for (String configuredProperty : new String[] {
                    propertyPrefix + "." + propertyName,
                    propertyPrefix + "[" + propertyName + "]"
                }) {
                    MockEnvironment environment = safeEnvironment()
                        .withProperty(
                            configuredProperty,
                            SENSITIVE_CONNECTOR_VALUE_SENTINEL
                        );

                    assertSanitizedConnectorRefusal(
                        environment,
                        "unreviewed Connector/J property"
                    );
                }
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "spring.datasource.hikari.data-source-properties",
        "spring.datasource.hikari.dataSourceProperties",
        "spring.datasource.hikari.data_source_properties",
        "spring.flyway.jdbc-properties",
        "spring.flyway.jdbcProperties",
        "spring.flyway.jdbc_properties"
    })
    void refusesUnknownRelaxedAndBracketedDriverMapProperties(String propertyPrefix) {
        MockEnvironment dottedEnvironment = safeEnvironment()
            .withProperty(propertyPrefix + ".futurePlugin", "unsafe");
        MockEnvironment bracketedEnvironment = safeEnvironment()
            .withProperty(propertyPrefix + "[futurePlugin]", "unsafe");

        for (MockEnvironment environment
                : new MockEnvironment[] {dottedEnvironment, bracketedEnvironment}) {
            IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> SeederStartupConfigurationValidator.validate(environment)
            );

            assertTrue(exception.getMessage().contains("unreviewed Connector/J property"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "spring.datasource.hikari.jdbc-url[0]",
        "spring.datasource.hikari.driver_class_name[0]",
        "spring.flyway.default_schema[0]",
        "spring.flyway.driverClassName[0]"
    })
    void refusesIndexedScalarAliases(String propertyName) {
        MockEnvironment environment = safeEnvironment()
            .withProperty(propertyName, SeederStartupConfigurationValidator.PROJECT_DRIVER);

        IllegalStateException exception = assertThrows(
            IllegalStateException.class,
            () -> SeederStartupConfigurationValidator.validate(environment)
        );

        assertTrue(exception.getMessage().contains("must be a scalar"));
    }

    @Test
    void refusesHiddenRelaxedHikariJdbcUrlMismatch() {
        MockEnvironment environment = safeEnvironment()
            .withProperty(
                "spring.datasource.hikari.jdbc-url",
                "jdbc:mysql://127.0.0.1/Connex_Seeder?sslMode=DISABLED"
            )
            .withProperty(
                "spring.datasource.hikari.jdbc_url",
                "jdbc:mysql://127.0.0.1/connex_seeder?sslMode=DISABLED"
            );

        IllegalStateException exception = assertThrows(
            IllegalStateException.class,
            () -> SeederStartupConfigurationValidator.validate(environment)
        );

        assertTrue(exception.getMessage().contains("spring.datasource.hikari.jdbc-url"));
        assertTrue(exception.getMessage().contains("conflicting relaxed aliases"));
    }

    @Test
    void refusesUppercaseEnvironmentStyleHikariJdbcUrlMismatch() {
        MockEnvironment environment = safeEnvironment()
            .withProperty(
                "SPRING_DATASOURCE_HIKARI_JDBC_URL",
                "jdbc:mysql://127.0.0.1/connex_seeder?sslMode=DISABLED"
            );

        IllegalStateException exception = assertThrows(
            IllegalStateException.class,
            () -> SeederStartupConfigurationValidator.validate(environment)
        );

        assertTrue(exception.getMessage().contains("spring.datasource.hikari.jdbc-url"));
    }

    @Test
    void refusesHikariJdbcUrlAliasesThatDisagreeAcrossPropertySources() {
        MockEnvironment environment = safeEnvironment()
            .withProperty(
                "spring.datasource.hikari.jdbc-url",
                "jdbc:mysql://127.0.0.1/Connex_Seeder?sslMode=DISABLED"
            );
        environment.getPropertySources().addLast(new MapPropertySource(
            "lowerOperatorConfiguration",
            Map.of(
                "spring.datasource.hikari.jdbc_url",
                "jdbc:mysql://127.0.0.1/connex_seeder?sslMode=DISABLED"
            )
        ));

        IllegalStateException exception = assertThrows(
            IllegalStateException.class,
            () -> SeederStartupConfigurationValidator.validate(environment)
        );

        assertTrue(exception.getMessage().contains("conflicting relaxed aliases"));
    }

    @Test
    void comparesCatalogsCaseSensitivelyAndHostsCaseInsensitively() {
        MockEnvironment matching = safeEnvironment()
            .withProperty(
                "spring.flyway.url",
                "jdbc:mysql://db.example.test:3306/Connex_Seeder?sslMode=VERIFY_IDENTITY"
            )
            .withProperty(
                "spring.datasource.url",
                "jdbc:mysql://DB.EXAMPLE.TEST/Connex_Seeder?sslMode=VERIFY_IDENTITY"
            )
            .withProperty("connex.seeder.allow-remote-host", "true");
        MockEnvironment mismatching = safeEnvironment()
            .withProperty(
                "spring.flyway.url",
                "jdbc:mysql://127.0.0.1:3306/connex_seeder?sslMode=DISABLED"
            );

        SeederStartupConfigurationValidator.validate(matching);

        IllegalStateException exception = assertThrows(
            IllegalStateException.class,
            () -> SeederStartupConfigurationValidator.validate(mismatching)
        );
        assertTrue(exception.getMessage().contains("disagrees"));
    }

    @Test
    void bindsDefaultSchemaAsOneCommaBearingScalar() {
        MockEnvironment environment = safeEnvironment()
            .withProperty(
                "spring.datasource.url",
                "jdbc:mysql://127.0.0.1/Connex_Seeder,Archive?sslMode=DISABLED"
            )
            .withProperty(
                "spring.flyway.default-schema",
                "Connex_Seeder,Archive"
            );

        SeederStartupConfigurationValidator.ValidatedConfiguration configuration =
            SeederStartupConfigurationValidator.validate(environment);

        assertEquals("Connex_Seeder,Archive", configuration.target().database());
    }

    @Test
    void acceptsReviewedQueryAndRelaxedMapProperties() {
        MockEnvironment environment = safeEnvironment()
            .withProperty(
                "spring.datasource.url",
                "jdbc:mysql://127.0.0.1/Connex_Seeder"
                    + "?createDatabaseIfNotExist=true"
                    + "&allowPublicKeyRetrieval=true"
                    + "&sslMode=DISABLED"
            )
            .withProperty(
                "spring.datasource.hikari.data_source_properties[connectTimeout]",
                "5000"
            )
            .withProperty(
                "spring.flyway.jdbc-properties[serverTimezone]",
                "UTC"
            );

        SeederStartupConfigurationValidator.validate(environment);
    }

    @Test
    void acceptsOnlyTheValidatedDynamicFlywayChannels() {
        MockEnvironment environment = safeEnvironment()
            .withProperty(
                "spring.flyway.url",
                "jdbc:mysql://127.0.0.1:3306/Connex_Seeder?sslMode=DISABLED"
            )
            .withProperty(
                "spring.flyway.driver_class_name",
                SeederStartupConfigurationValidator.PROJECT_DRIVER
            )
            .withProperty(
                "spring.flyway.jdbcProperties[connectTimeout]",
                "5000"
            )
            .withProperty("spring.flyway.default_schema", "Connex_Seeder")
            .withProperty("spring.flyway.schemas[0]", "Connex_Seeder");

        SeederStartupConfigurationValidator.validate(environment);
    }

    @ParameterizedTest
    @MethodSource("pinnedFlywayProperties")
    void requiresEveryPinnedFlywayPropertyFromTheSeederRepositorySource(
            String propertyName,
            Object expectedValue) {
        Map<String, Object> missingPin = new LinkedHashMap<>(safeRepositoryProperties());
        missingPin.remove(propertyName);
        Map<String, Object> wrongPin = new LinkedHashMap<>(safeRepositoryProperties());
        wrongPin.put(propertyName, wrongRepositoryValue(expectedValue));

        for (MockEnvironment environment : new MockEnvironment[] {
            safeEnvironment(missingPin),
            safeEnvironment(wrongPin)
        }) {
            IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> SeederStartupConfigurationValidator.validate(environment),
                propertyName
            );

            assertTrue(exception.getMessage().contains(propertyName), propertyName);
        }
    }

    @ParameterizedTest
    @MethodSource("pinnedFlywayProperties")
    void refusesEverySameValueOperatorOverrideOfPinnedFlywaySemantics(
            String propertyName,
            Object expectedValue) {
        MockEnvironment environment = safeEnvironment()
            .withProperty(propertyName, operatorValue(expectedValue));

        IllegalStateException exception = assertThrows(
            IllegalStateException.class,
            () -> SeederStartupConfigurationValidator.validate(environment),
            propertyName
        );

        assertTrue(exception.getMessage().contains("operator configuration"), propertyName);
    }

    @Test
    void refusesExternalSourcesWhoseNamesEmbedRepositoryResourceText() {
        MockEnvironment environment = safeEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource(
            "Config resource 'file [/tmp/class path resource "
                + "[application-seeder.yml]/application.yml]'",
            Map.of("spring.flyway.enabled", true)
        ));

        IllegalStateException exception = assertThrows(
            IllegalStateException.class,
            () -> SeederStartupConfigurationValidator.validate(environment)
        );

        assertTrue(exception.getMessage().contains("operator configuration"));
    }

    @ParameterizedTest
    @MethodSource("pinnedFlywayAliasAndDescendantProperties")
    void refusesAliasesIndexesAndDescendantsForEveryPinnedFlywaySemantic(
            String propertyName,
            String configuredValue) {
        MockEnvironment environment = safeEnvironment()
            .withProperty(propertyName, configuredValue);

        IllegalStateException exception = assertThrows(
            IllegalStateException.class,
            () -> SeederStartupConfigurationValidator.validate(environment)
        );

        assertTrue(exception.getMessage().contains("spring.flyway"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "spring.flyway.user",
        "spring.flyway.password",
        "spring.flyway.clean-on-validation-error",
        "spring.flyway.baseline-migration-prefix",
        "spring.flyway.undo-sql-migration-prefix",
        "spring.flyway.unknown-future-option"
    })
    void refusesFlywayCredentialsRemovedDeprecatedAndUnknownSemanticKeys(
            String propertyName) {
        MockEnvironment environment = safeEnvironment()
            .withProperty(propertyName, "unsafe");

        IllegalStateException exception = assertThrows(
            IllegalStateException.class,
            () -> SeederStartupConfigurationValidator.validate(environment)
        );

        assertTrue(exception.getMessage().contains("spring.flyway"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "connectionTimeZone=UTC&serverTimezone=Asia%2FTokyo",
        "tlsCiphersuites=first&enabledSSLCipherSuites=second",
        "tlsVersions=TLSv1.3&enabledTLSProtocols=TLSv1.2"
    })
    void refusesAmbiguousReviewedQueryAliases(String query) {
        MockEnvironment environment = safeEnvironment().withProperty(
            "spring.datasource.url",
            "jdbc:mysql://127.0.0.1/Connex_Seeder?" + query
        );

        IllegalStateException exception = assertThrows(
            IllegalStateException.class,
            () -> SeederStartupConfigurationValidator.validate(environment)
        );

        assertTrue(exception.getMessage().contains("ambiguous duplicate"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "spring.datasource.hikari.data-source-properties",
        "spring.flyway.jdbc-properties"
    })
    void refusesAmbiguousReviewedMapAliases(String propertyPrefix) {
        MockEnvironment environment = safeEnvironment()
            .withProperty(propertyPrefix + ".connectionTimeZone", "UTC")
            .withProperty(propertyPrefix + ".serverTimezone", "Asia/Tokyo");

        IllegalStateException exception = assertThrows(
            IllegalStateException.class,
            () -> SeederStartupConfigurationValidator.validate(environment)
        );

        assertTrue(exception.getMessage().contains("ambiguous Connector/J aliases"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "spring.datasource.hikari.data-source-properties",
        "spring.flyway.jdbc-properties"
    })
    void refusesPunctuationRelaxedConnectorMapNames(String propertyPrefix) {
        MockEnvironment environment = safeEnvironment()
            .withProperty(propertyPrefix + "[connect.timeout]", "5000");

        IllegalStateException exception = assertThrows(
            IllegalStateException.class,
            () -> SeederStartupConfigurationValidator.validate(environment)
        );

        assertTrue(exception.getMessage().contains("unreviewed Connector/J property"));
    }

    @Test
    void refusesIndexedMigrationAndCallbackLocationAdditions() {
        for (String propertyPrefix : new String[] {
            "spring.flyway.locations",
            "spring.flyway.callback-locations"
        }) {
            MockEnvironment environment = safeEnvironment()
                .withProperty(propertyPrefix + "[0]", "classpath:db/migration")
                .withProperty(propertyPrefix + "[1]", "filesystem:/tmp/unsafe");

            IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> SeederStartupConfigurationValidator.validate(environment)
            );

            assertTrue(exception.getMessage().contains(propertyPrefix));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "{vendor}",
        "classpath:db/{vendor}",
        "filesystem:/tmp/migrations",
        "https://example.test/migrations",
        "db/migration",
        "classpath:db/*"
    })
    void refusesEveryNonRepositoryMigrationLocation(String location) {
        MockEnvironment environment = safeEnvironment()
            .withProperty("spring.flyway.locations", location);

        IllegalStateException exception = assertThrows(
            IllegalStateException.class,
            () -> SeederStartupConfigurationValidator.validate(environment)
        );

        assertTrue(exception.getMessage().contains("spring.flyway.locations"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "{vendor}",
        "classpath:db/{vendor}",
        "filesystem:/tmp/callbacks",
        "https://example.test/callbacks",
        "db/callbacks",
        "classpath:db/*"
    })
    void refusesEveryConfiguredCallbackLocation(String location) {
        MockEnvironment environment = safeEnvironment()
            .withProperty("spring.flyway.callback-locations", location);

        IllegalStateException exception = assertThrows(
            IllegalStateException.class,
            () -> SeederStartupConfigurationValidator.validate(environment)
        );

        assertTrue(exception.getMessage().contains("spring.flyway.callback-locations"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "spring.flyway.locations",
        "spring.flyway.callback-locations"
    })
    void refusesSameValueOperatorLocationOverrides(String propertyName) {
        String value = propertyName.contains("callback") ? "" : "classpath:db/migration";
        MockEnvironment environment = safeEnvironment().withProperty(propertyName, value);

        IllegalStateException exception = assertThrows(
            IllegalStateException.class,
            () -> SeederStartupConfigurationValidator.validate(environment)
        );

        assertTrue(exception.getMessage().contains("operator configuration"));
    }

    @Test
    void refusesSameValueOperatorOverridesOfRepositoryClosedNamespaces() {
        Map<String, String> sameValueOverrides = Map.of(
            "spring.sql.init.mode",
            "never",
            "spring.sql.init.data-locations",
            SeederStartupConfigurationValidator.PROJECT_SQL_INIT_DATA_LOCATION,
            "mybatis.mapper-locations",
            SeederStartupConfigurationValidator.PROJECT_MYBATIS_MAPPER_LOCATIONS,
            "mybatis.type-aliases-package",
            SeederStartupConfigurationValidator.PROJECT_MYBATIS_TYPE_ALIASES_PACKAGE,
            "mybatis.configuration.map-underscore-to-camel-case",
            "true"
        );

        for (Map.Entry<String, String> override : sameValueOverrides.entrySet()) {
            MockEnvironment environment = safeEnvironment()
                .withProperty(override.getKey(), override.getValue());

            IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> SeederStartupConfigurationValidator.validate(environment),
                override.getKey()
            );

            assertTrue(
                exception.getMessage().contains("operator configuration"),
                override.getKey()
            );
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "SPRING_SQL_INIT_MODE",
        "spring.sql.init.schema-locations",
        "spring.sql.init.schema_locations[0]",
        "spring.sql.init.dataLocations",
        "spring.sql.init.username",
        "spring.sql.init.password",
        "spring.sql.init.platform",
        "spring.sql.init.encoding",
        "spring.sql.init.separator",
        "spring.sql.init.continue-on-error",
        "spring.sql.init.unknown-future-option"
    })
    void refusesTheWholeRelaxedSqlInitializerNamespace(String propertyName) {
        MockEnvironment environment = safeEnvironment()
            .withProperty(propertyName, "unsafe");

        IllegalStateException exception = assertThrows(
            IllegalStateException.class,
            () -> SeederStartupConfigurationValidator.validate(environment)
        );

        assertTrue(exception.getMessage().contains("spring.sql.init"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "MYBATIS_MAPPER_LOCATIONS",
        "mybatis.mapper_locations[0]",
        "mybatis.config-location",
        "mybatis.configuration-properties.catalog",
        "mybatis.configuration.variables.catalog",
        "mybatis.configuration.database-id",
        "mybatis.type-handlers-package",
        "mybatis.typeAliasesSuperType",
        "mybatis.default-scripting-language-driver",
        "mybatis.scripting-language-driver.unsafe",
        "mybatis.unknown-future-option"
    })
    void refusesTheWholeRelaxedMybatisNamespace(String propertyName) {
        MockEnvironment environment = safeEnvironment()
            .withProperty(propertyName, "unsafe");

        IllegalStateException exception = assertThrows(
            IllegalStateException.class,
            () -> SeederStartupConfigurationValidator.validate(environment)
        );

        assertTrue(exception.getMessage().contains("mybatis"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "connex.maintenance.mode",
        "spring.main.web-application-type",
        "connex.tenancy.routing.mode",
        "connex.object-storage.legacy-migration.mode",
        "spring.datasource.driver-class-name",
        "spring.flyway.driver-class-name",
        "spring.flyway.default-schema"
    })
    void refusesWhitespacePaddedExactContractValues(String propertyName) {
        String value = switch (propertyName) {
            case "connex.maintenance.mode" -> " seeder ";
            case "spring.main.web-application-type" -> " none ";
            case "connex.tenancy.routing.mode" -> " single-database ";
            case "connex.object-storage.legacy-migration.mode" -> " off ";
            case "spring.flyway.default-schema" -> " Connex_Seeder ";
            default -> " " + SeederStartupConfigurationValidator.PROJECT_DRIVER + " ";
        };
        MockEnvironment environment = safeEnvironment().withProperty(propertyName, value);

        assertThrows(
            IllegalStateException.class,
            () -> SeederStartupConfigurationValidator.validate(environment)
        );
    }

    @Test
    void refusesWhitespaceDifferentRelaxedAliasesAcrossPropertySources() {
        MockEnvironment environment = safeEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource(
            "operatorAlias",
            Map.of("CONNEX_MAINTENANCE_MODE", "seeder ")
        ));

        IllegalStateException exception = assertThrows(
            IllegalStateException.class,
            () -> SeederStartupConfigurationValidator.validate(environment)
        );

        assertTrue(exception.getMessage().contains("conflicting relaxed aliases"));
    }

    @Test
    void acceptsTheRepositoryBlankDeploymentProfileButRefusesWhitespace() {
        SeederStartupConfigurationValidator.validate(
            safeEnvironment().withProperty("connex.deployment.profile", "")
        );
        MockEnvironment environment = safeEnvironment()
            .withProperty("connex.deployment.profile", " ");

        IllegalStateException exception = assertThrows(
            IllegalStateException.class,
            () -> SeederStartupConfigurationValidator.validate(environment)
        );

        assertTrue(exception.getMessage().contains("must be unset"));
    }

    @Test
    void refusesOperatorSpringApplicationSources() {
        MockEnvironment environment = safeEnvironment()
            .withProperty("spring.main.sources[0]", "operator.UnsafeConfiguration");

        IllegalStateException exception = assertThrows(
            IllegalStateException.class,
            () -> SeederStartupConfigurationValidator.validate(environment)
        );

        assertTrue(exception.getMessage().contains("spring.main.sources"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "spring.autoconfigure.exclude",
        "spring.autoconfigure.exclude[0]",
        "SPRING_AUTOCONFIGURE_EXCLUDE"
    })
    void refusesAutoConfigurationExclusions(String propertyName) {
        MockEnvironment environment = safeEnvironment().withProperty(
            propertyName,
            "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration"
        );

        IllegalStateException exception = assertThrows(
            IllegalStateException.class,
            () -> SeederStartupConfigurationValidator.validate(environment)
        );

        assertTrue(exception.getMessage().contains("spring.autoconfigure.exclude"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "spring.datasource.type",
        "spring.datasource.jndi_name",
        "spring.datasource.hikari.dataSourceClassName",
        "spring.datasource.hikari.data_source_jndi",
        "spring.datasource.hikari.credentialsProviderClassName",
        "spring.datasource.hikari.exception_override_class_name",
        "spring.datasource.hikari.metric-registry",
        "spring.datasource.hikari.healthCheckRegistry",
        "spring.datasource.hikari.catalog",
        "spring.datasource.hikari.schema",
        "spring.datasource.hikari.connection_test_query"
    })
    void refusesHikariScalarConstructionAndRoutingChannelsEvenWhenBlank(
            String propertyName) {
        MockEnvironment environment = safeEnvironment().withProperty(propertyName, " ");

        IllegalStateException exception = assertThrows(
            IllegalStateException.class,
            () -> SeederStartupConfigurationValidator.validate(environment)
        );

        assertTrue(exception.getMessage().contains("must be unset"));
    }

    @Test
    void refusesXaDriverPlaceholderAndInitSqlChannels() {
        MockEnvironment xaEnvironment = safeEnvironment()
            .withProperty(
                "spring.datasource.xa.data-source-class-name",
                "operator.UnsafeDataSource"
            );
        MockEnvironment placeholderEnvironment = safeEnvironment()
            .withProperty("spring.flyway.placeholders.catalog", "unsafe");
        MockEnvironment initEnvironment = safeEnvironment()
            .withProperty("spring.flyway.init-sqls[0]", "USE unsafe");

        assertThrows(
            IllegalStateException.class,
            () -> SeederStartupConfigurationValidator.validate(xaEnvironment)
        );
        assertThrows(
            IllegalStateException.class,
            () -> SeederStartupConfigurationValidator.validate(placeholderEnvironment)
        );
        assertThrows(
            IllegalStateException.class,
            () -> SeederStartupConfigurationValidator.validate(initEnvironment)
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "jdbc:mysql:loadbalance://host-one,host-two/Connex_Seeder",
        "jdbc:mysql:replication://host-one,host-two/Connex_Seeder",
        "jdbc:mysql://address=(host=127.0.0.1)/Connex_Seeder",
        "jdbc:mysql://user@127.0.0.1/Connex_Seeder",
        "jdbc:mysql://127.0.0.1/Connex_Seeder#fragment",
        "jdbc:mysql://127.0.0.1:/Connex_Seeder",
        "jdbc:mysql://127.0.0.1:0/Connex_Seeder",
        "jdbc:mysql://127.0.0.1/Connex_Seeder%2FArchive"
    })
    void refusesEveryAlternateOrAmbiguousConnectorJUrlForm(String jdbcUrl) {
        MockEnvironment environment = safeEnvironment()
            .withProperty("spring.datasource.url", jdbcUrl);

        IllegalStateException exception = assertThrows(
            IllegalStateException.class,
            () -> SeederStartupConfigurationValidator.validate(environment)
        );

        assertTrue(exception.getMessage().startsWith("Seeder refused:"));
    }

    @Test
    void sanitizesMalformedUnbindableAndUnresolvedConfigurationFailures() {
        MockEnvironment malformedBoolean = safeEnvironment()
            .withProperty("connex.seeder.allow-remote-host", "not-a-boolean");
        MockEnvironment unresolvedPlaceholder = safeEnvironment()
            .withProperty("connex.tenancy.routing.mode", "${MISSING_ROUTING_MODE}");
        MockEnvironment malformedUrl = safeEnvironment()
            .withProperty("spring.datasource.url", "jdbc:mysql://[broken/Connex_Seeder");

        for (MockEnvironment environment : new MockEnvironment[] {
            malformedBoolean,
            unresolvedPlaceholder,
            malformedUrl
        }) {
            IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> SeederStartupConfigurationValidator.validate(environment)
            );

            assertTrue(exception.getMessage().startsWith("Seeder refused:"));
            assertFalse(exception.getMessage().contains("not-a-boolean"));
            assertFalse(exception.getMessage().contains("MISSING_ROUTING_MODE"));
            assertFalse(exception.getMessage().contains("jdbc:mysql://[broken"));
            assertEquals(null, exception.getCause());
        }
    }

    private static void assertSanitizedConnectorRefusal(
            MockEnvironment environment,
            String expectedReason) {
        IllegalStateException exception = assertThrows(
            IllegalStateException.class,
            () -> SeederStartupConfigurationValidator.validate(environment)
        );

        assertTrue(exception.getMessage().startsWith("Seeder refused:"));
        assertTrue(exception.getMessage().contains(expectedReason));
        assertFalse(
            exception.getMessage().contains(SENSITIVE_CONNECTOR_VALUE_SENTINEL)
        );
        assertEquals(null, exception.getCause());
        assertEquals(0, exception.getSuppressed().length);
    }

    private static MockEnvironment safeEnvironment() {
        return safeEnvironment(safeRepositoryProperties());
    }

    private static MockEnvironment safeEnvironment(
            Map<String, Object> repositoryProperties) {
        MockEnvironment environment = new MockEnvironment()
            .withProperty("connex.seeder.enabled", "true")
            .withProperty("connex.maintenance.mode", "seeder")
            .withProperty("spring.main.web-application-type", "none")
            .withProperty("connex.tenancy.routing.mode", "single-database")
            .withProperty("connex.object-storage.legacy-migration.mode", "off")
            .withProperty(
                "spring.datasource.url",
                "jdbc:mysql://127.0.0.1/Connex_Seeder?sslMode=DISABLED"
            )
            .withProperty(
                "spring.datasource.driver-class-name",
                SeederStartupConfigurationValidator.PROJECT_DRIVER
            )
            .withProperty(
                "spring.datasource.hikari.connection-init-sql",
                SeederStartupConfigurationValidator.PROJECT_HIKARI_CONNECTION_INIT_SQL
            )
            .withProperty(
                "spring.flyway.driver-class-name",
                SeederStartupConfigurationValidator.PROJECT_DRIVER
            );
        environment.getPropertySources().addLast(new MapPropertySource(
            "Config resource 'class path resource [application-seeder.yml]'",
            repositoryProperties
        ));
        environment.setActiveProfiles("seeder");
        return environment;
    }

    static Map<String, Object> safeRepositoryProperties() {
        Map<String, Object> properties = new LinkedHashMap<>(
            SeederStartupConfigurationValidator.REPOSITORY_FLYWAY_PROPERTIES
        );
        properties.put("spring.sql.init.mode", "never");
        properties.put(
            "spring.sql.init.data-locations",
            SeederStartupConfigurationValidator.PROJECT_SQL_INIT_DATA_LOCATION
        );
        properties.put(
            "mybatis.mapper-locations",
            SeederStartupConfigurationValidator.PROJECT_MYBATIS_MAPPER_LOCATIONS
        );
        properties.put(
            "mybatis.type-aliases-package",
            SeederStartupConfigurationValidator.PROJECT_MYBATIS_TYPE_ALIASES_PACKAGE
        );
        properties.put("mybatis.configuration.map-underscore-to-camel-case", true);
        return Map.copyOf(properties);
    }

    private static Stream<Arguments> pinnedFlywayProperties() {
        return SeederStartupConfigurationValidator.REPOSITORY_FLYWAY_PROPERTIES.entrySet()
            .stream()
            .map(entry -> Arguments.of(entry.getKey(), entry.getValue()));
    }

    private static Stream<Arguments> pinnedFlywayAliasAndDescendantProperties() {
        return SeederStartupConfigurationValidator.REPOSITORY_FLYWAY_PROPERTIES.entrySet()
            .stream()
            .flatMap(entry -> {
                String configuredValue = operatorValue(entry.getValue());
                String environmentAlias = entry.getKey()
                    .toUpperCase(Locale.ROOT)
                    .replace('.', '_')
                    .replace('-', '_');
                return Stream.of(
                    Arguments.of(environmentAlias, configuredValue),
                    Arguments.of(entry.getKey() + "[0]", configuredValue),
                    Arguments.of(entry.getKey() + ".unexpected", configuredValue)
                );
            });
    }

    private static Object wrongRepositoryValue(Object expectedValue) {
        if (expectedValue instanceof Boolean booleanValue) {
            return !booleanValue;
        }
        if (expectedValue instanceof Number) {
            return 1;
        }
        if (expectedValue instanceof List<?>) {
            return List.of("unsafe");
        }
        if (expectedValue instanceof Map<?, ?>) {
            return Map.of("unsafe", "unsafe");
        }
        return "unsafe";
    }

    private static String operatorValue(Object expectedValue) {
        if (expectedValue instanceof List<?> values) {
            return values.isEmpty() ? "" : values.getFirst().toString();
        }
        if (expectedValue instanceof Map<?, ?>) {
            return "";
        }
        return expectedValue.toString();
    }
}
