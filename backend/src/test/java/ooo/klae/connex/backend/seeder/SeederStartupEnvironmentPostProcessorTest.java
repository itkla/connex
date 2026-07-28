package ooo.klae.connex.backend.seeder;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.PrintWriter;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverPropertyInfo;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import java.util.stream.Stream;

import javax.naming.Context;
import javax.naming.NamingException;
import javax.naming.spi.InitialContextFactory;
import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.env.MockEnvironment;

import com.zaxxer.hikari.HikariCredentialsProvider;
import com.zaxxer.hikari.SQLExceptionOverride;
import com.zaxxer.hikari.util.Credentials;

import ooo.klae.connex.backend.BackendApplication;
import ooo.klae.connex.backend.config.DatabaseTransportSecurityEnvironmentPostProcessor;

@ExtendWith(OutputCaptureExtension.class)
class SeederStartupEnvironmentPostProcessorTest {

    private static final AtomicInteger CLASS_INITIALIZATIONS = new AtomicInteger();
    private static final AtomicInteger CONSTRUCTIONS = new AtomicInteger();
    private static final AtomicInteger DRIVER_CONNECTS = new AtomicInteger();
    private static final AtomicInteger DATA_SOURCE_CONNECTIONS = new AtomicInteger();
    private static final AtomicInteger METADATA_READS = new AtomicInteger();
    private static final AtomicInteger SQL_EXECUTIONS = new AtomicInteger();
    private static final AtomicInteger JNDI_LOOKUPS = new AtomicInteger();
    private static final String CANARY_DRIVER =
        "ooo.klae.connex.backend.seeder.SeederStartupEnvironmentPostProcessorTest$CanaryDriver";
    private static final String CANARY_EXCEPTION_OVERRIDE =
        "ooo.klae.connex.backend.seeder.SeederStartupEnvironmentPostProcessorTest$CanaryExceptionOverride";
    private static final String CANARY_INITIAL_CONTEXT_FACTORY =
        "ooo.klae.connex.backend.seeder.SeederStartupEnvironmentPostProcessorTest$CanaryInitialContextFactory";
    private static final String CERTIFICATE_PASSWORD_SENTINEL =
        "certificate-password-sentinel-7429";

    @BeforeEach
    void resetCanaries() {
        CLASS_INITIALIZATIONS.set(0);
        CONSTRUCTIONS.set(0);
        DRIVER_CONNECTS.set(0);
        DATA_SOURCE_CONNECTIONS.set(0);
        METADATA_READS.set(0);
        SQL_EXECUTIONS.set(0);
        JNDI_LOOKUPS.set(0);
    }

    @Test
    void declaresTheExactPostConfigDataPreTransportOrder() {
        SeederStartupEnvironmentPostProcessor processor =
            new SeederStartupEnvironmentPostProcessor();
        DatabaseTransportSecurityEnvironmentPostProcessor transportProcessor =
            new DatabaseTransportSecurityEnvironmentPostProcessor();

        assertEquals(Integer.MAX_VALUE - 1, processor.getOrder());
        assertTrue(processor.getOrder() > ConfigDataEnvironmentPostProcessor.ORDER);
        assertTrue(processor.getOrder() < transportProcessor.getOrder());
    }

    @Test
    void refusesSeederSignalsFromThePrebindingServletApplication() {
        SpringApplication application = new SpringApplication(MinimalApplication.class);
        MockEnvironment environment = new MockEnvironment()
            .withProperty("spring.main.web-application-type", "none")
            .withProperty("connex.seeder.enabled", "true");

        IllegalStateException exception = assertThrows(
            IllegalStateException.class,
            () -> new SeederStartupEnvironmentPostProcessor()
                .postProcessEnvironment(environment, application)
        );

        assertEquals(WebApplicationType.SERVLET, application.getWebApplicationType());
        assertTrue(exception.getMessage().contains("SeederApplication launcher"));
    }

    @Test
    void preservesServletApplicationsWithoutSeederSignals() {
        SpringApplication application = new SpringApplication(BackendApplication.class);

        assertDoesNotThrow(
            () -> new SeederStartupEnvironmentPostProcessor()
                .postProcessEnvironment(new MockEnvironment(), application)
        );
        assertEquals(WebApplicationType.SERVLET, application.getWebApplicationType());
    }

    @Test
    void discoversTheProcessorAndSeesExternalConfigData(@TempDir Path temporaryDirectory)
            throws Exception {
        Path configFile = temporaryDirectory.resolve("application.yml");
        Files.writeString(configFile, """
            spring:
              profiles:
                active: seeder
              main:
                web-application-type: none
              datasource:
                url: jdbc:mysql://127.0.0.1/connex_seed?sslMode=DISABLED
                username: seeder
                password: seeder
              flyway:
                locations: "{vendor}"
                placeholder-replacement: false
            connex:
              seeder:
                enabled: true
              maintenance:
                mode: seeder
              tenancy:
                routing:
                  mode: single-database
              object-storage:
                legacy-migration:
                  mode: "off"
            """);

        RuntimeException exception = assertThrows(
            RuntimeException.class,
            () -> runApplication(
                MinimalApplication.class,
                "--spring.config.additional-location=" + configFile.toUri()
            )
        );

        assertTrue(refusalMessage(exception).contains("spring.flyway.locations"));
    }

    @Test
    void acceptsThePinnedLoopbackSeederConfiguration() {
        try (ConfigurableApplicationContext context = runApplication(
                MinimalApplication.class,
                safeArguments(
                    "jdbc:mysql://127.0.0.1/connex_seed"
                        + "?createDatabaseIfNotExist=true"
                        + "&allowPublicKeyRetrieval=true"
                        + "&sslMode=DISABLED"
                ))) {
            assertEquals(
                "classpath:db/migration",
                context.getEnvironment().getProperty("spring.flyway.locations")
            );
        }
    }

    @Test
    void refusesRemotePlaintextAfterTheSeederBoundaryPasses() {
        RuntimeException exception = assertThrows(
            RuntimeException.class,
            () -> runApplication(
                MinimalApplication.class,
                safeArguments(
                    "jdbc:mysql://db.example.test:3306/connex_seed?sslMode=DISABLED",
                    "--connex.seeder.allow-remote-host=true"
                )
            )
        );

        assertTrue(refusalMessage(exception).contains("VERIFY_CA"));
    }

    @Test
    void acceptsRemoteVerifiedTlsWithTheExplicitSeederOverride() {
        try (ConfigurableApplicationContext context = runApplication(
                MinimalApplication.class,
                safeArguments(
                    "jdbc:mysql://db.example.test:3306/connex_seed?sslMode=VERIFY_IDENTITY",
                    "--connex.seeder.allow-remote-host=true"
                ))) {
            assertTrue(context.isActive());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"dev", "test"})
    void refusesSeederCoprofilesBeforeTransportCanBypassThem(String coProfile) {
        RuntimeException exception = assertThrows(
            RuntimeException.class,
            () -> runApplication(
                MinimalApplication.class,
                safeArguments(
                    "jdbc:mysql://127.0.0.1/connex_seed?sslMode=DISABLED",
                    "--spring.profiles.active=seeder," + coProfile
                )
            )
        );

        assertTrue(refusalMessage(exception).contains("only active Spring profile"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "--spring.datasource.type="
            + "ooo.klae.connex.backend.seeder.SeederStartupEnvironmentPostProcessorTest$CanaryDataSource",
        "--spring.datasource.driver-class-name="
            + "ooo.klae.connex.backend.seeder.SeederStartupEnvironmentPostProcessorTest$CanaryDriver",
        "--spring.datasource.hikari.data-source-class-name="
            + "ooo.klae.connex.backend.seeder.SeederStartupEnvironmentPostProcessorTest$CanaryDataSource",
        "--spring.datasource.hikari.credentials-provider-class-name="
            + "ooo.klae.connex.backend.seeder.SeederStartupEnvironmentPostProcessorTest$CanaryCredentialsProvider",
        "--spring.datasource.hikari.exception-override-class-name="
            + "ooo.klae.connex.backend.seeder.SeederStartupEnvironmentPostProcessorTest$CanaryExceptionOverride",
        "--spring.datasource.hikari.metric-registry=java:comp/env/unsafe",
        "--spring.datasource.jndi-name=java:comp/env/unsafe",
        "--spring.datasource.xa.data-source-class-name="
            + "ooo.klae.connex.backend.seeder.SeederStartupEnvironmentPostProcessorTest$CanaryDataSource",
        "--spring.flyway.driver-class-name="
            + "ooo.klae.connex.backend.seeder.SeederStartupEnvironmentPostProcessorTest$CanaryDriver",
        "--spring.main.sources="
            + "ooo.klae.connex.backend.seeder.SeederStartupEnvironmentPostProcessorTest$CanaryDataSource",
        "--context.initializer.classes="
            + "ooo.klae.connex.backend.seeder.SeederStartupEnvironmentPostProcessorTest$CanaryDataSource",
        "--context.listener.classes="
            + "ooo.klae.connex.backend.seeder.SeederStartupEnvironmentPostProcessorTest$CanaryDataSource"
    })
    void refusesCanaryConstructionChannelsBeforeAnyInfrastructureActivity(
            String unsafeArgument) {
        String previousFactory = System.getProperty(Context.INITIAL_CONTEXT_FACTORY);
        try {
            System.setProperty(
                Context.INITIAL_CONTEXT_FACTORY,
                CANARY_INITIAL_CONTEXT_FACTORY
            );

            RuntimeException exception = assertThrows(
                RuntimeException.class,
                () -> runApplication(
                    AutoConfiguredApplication.class,
                    safeArguments(
                        "jdbc:mysql://127.0.0.1/connex_seed?sslMode=DISABLED",
                        unsafeArgument
                    )
                )
            );

            assertTrue(refusalMessage(exception).startsWith("Seeder refused:"));
            assertNoCanaryActivity();
        } finally {
            restoreSystemProperty(Context.INITIAL_CONTEXT_FACTORY, previousFactory);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "--spring.flyway.locations={vendor}",
        "--spring.flyway.callback-locations={vendor}",
        "--spring.flyway.locations=filesystem:/tmp/unsafe",
        "--spring.flyway.callback-locations=filesystem:/tmp/unsafe"
    })
    void refusesExternalAndVendorLocationsBeforeDatasourceOrMetadataActivity(
            String unsafeArgument) {
        RuntimeException exception = assertThrows(
            RuntimeException.class,
            () -> runApplication(
                BeanCanaryApplication.class,
                safeArguments(
                    "jdbc:mysql://127.0.0.1/connex_seed?sslMode=DISABLED",
                    unsafeArgument
                )
            )
        );

        assertTrue(refusalMessage(exception).startsWith("Seeder refused:"));
        assertNoCanaryActivity();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "--spring.sql.init.mode=never",
        "--spring.sql.init.data-locations=optional:classpath:seeder-sql-init-canary.sql",
        "--spring.sql.init.schema-locations=classpath:unsafe-seeder-schema.sql",
        "--spring.sql.init.data_locations[0]=classpath:unsafe-seeder-data.sql",
        "--spring.sql.init.username=unsafe",
        "--spring.sql.init.encoding=UTF-16",
        "--spring.sql.init.separator=GO",
        "--spring.sql.init.continue-on-error=true",
        "--spring.sql.init.unknown-future-option=unsafe",
        "--mybatis.mapper-locations=classpath:mappers/*.xml",
        "--mybatis.mapper_locations[0]=classpath*:unsafe/**/*.xml",
        "--mybatis.config-location=classpath:unsafe-mybatis.xml",
        "--mybatis.configuration-properties.catalog=unsafe",
        "--mybatis.configuration.variables.catalog=unsafe",
        "--mybatis.configuration.database-id=unsafe",
        "--mybatis.type-handlers-package=operator.unsafe",
        "--mybatis.type-aliases-super-type=operator.UnsafeAlias",
        "--mybatis.configuration.default-enum-type-handler="
            + "ooo.klae.connex.backend.seeder.SeederStartupEnvironmentPostProcessorTest$CanaryDataSource",
        "--mybatis.unknown-future-option=unsafe"
    })
    void refusesSqlInitializerAndMybatisChannelsBeforeAnyInfrastructureActivity(
            String unsafeArgument) {
        RuntimeException exception = assertThrows(
            RuntimeException.class,
            () -> runApplication(
                BeanCanaryApplication.class,
                safeArguments(
                    "jdbc:mysql://127.0.0.1/connex_seed?sslMode=DISABLED",
                    unsafeArgument
                )
            )
        );

        assertTrue(refusalMessage(exception).startsWith("Seeder refused:"));
        assertNoCanaryActivity();
    }

    @ParameterizedTest
    @MethodSource("certificatePasswordCommandLineCases")
    void refusesCertificateStorePasswordCommandLineChannelsBeforeInfrastructure(
            String channel,
            String propertyName,
            CapturedOutput output) {
        String jdbcUrl =
            "jdbc:mysql://127.0.0.1/connex_seed?sslMode=DISABLED";
        String unsafeArgument = switch (channel) {
            case "jdbc-query" -> {
                jdbcUrl += "&" + propertyName + "=" + CERTIFICATE_PASSWORD_SENTINEL;
                yield null;
            }
            case "hikari-map" ->
                "--spring.datasource.hikari.data-source-properties."
                    + propertyName + "=" + CERTIFICATE_PASSWORD_SENTINEL;
            case "flyway-map" ->
                "--spring.flyway.jdbc-properties."
                    + propertyName + "=" + CERTIFICATE_PASSWORD_SENTINEL;
            default -> throw new IllegalStateException("Unexpected test channel");
        };
        String hikariDebug = "--logging.level.com.zaxxer.hikari=DEBUG";
        String[] arguments = unsafeArgument == null
            ? safeArguments(jdbcUrl, hikariDebug)
            : safeArguments(jdbcUrl, hikariDebug, unsafeArgument);

        RuntimeException exception = assertThrows(
            RuntimeException.class,
            () -> runApplication(BeanCanaryApplication.class, arguments)
        );

        String message = refusalMessage(exception);
        assertTrue(message.startsWith("Seeder refused:"));
        assertFalse(message.contains(CERTIFICATE_PASSWORD_SENTINEL));
        assertEquals(null, exception.getCause());
        assertEquals(0, exception.getSuppressed().length);
        assertNoCanaryActivity();
        assertFalse(output.getOut().contains(CERTIFICATE_PASSWORD_SENTINEL));
        assertFalse(output.getErr().contains(CERTIFICATE_PASSWORD_SENTINEL));
    }

    private static Stream<Arguments> certificatePasswordCommandLineCases() {
        return Stream.of(
            "clientCertificateKeyStorePassword",
            "trustCertificateKeyStorePassword"
        ).flatMap(propertyName -> Stream.of(
            Arguments.of("jdbc-query", propertyName),
            Arguments.of("hikari-map", propertyName),
            Arguments.of("flyway-map", propertyName)
        ));
    }

    @Test
    void refusesMaliciousHikariConfigurationFileWithoutReadingIt(
            @TempDir Path temporaryDirectory) throws Exception {
        Path configurationFile = temporaryDirectory.resolve("malicious.properties");
        Files.writeString(configurationFile, """
            driverClassName=%s
            jdbcUrl=jdbc:mysql://127.0.0.1/unsafe
            exceptionOverrideClassName=%s
            """.formatted(CANARY_DRIVER, CANARY_EXCEPTION_OVERRIDE));
        String previousValue = System.getProperty("hikaricp.configurationFile");
        try {
            System.setProperty(
                "hikaricp.configurationFile",
                configurationFile.toString()
            );

            RuntimeException exception = assertThrows(
                RuntimeException.class,
                () -> runApplication(
                    AutoConfiguredApplication.class,
                    safeArguments(
                        "jdbc:mysql://127.0.0.1/connex_seed?sslMode=DISABLED"
                    )
                )
            );

            assertTrue(
                refusalMessage(exception).contains("hikaricp.configurationFile")
            );
            assertNoCanaryActivity();
        } finally {
            restoreSystemProperty("hikaricp.configurationFile", previousValue);
        }
    }

    @Test
    void refusesBlankHikariConfigurationFileSystemProperty() {
        String previousValue = System.getProperty("hikaricp.configurationFile");
        try {
            System.setProperty("hikaricp.configurationFile", "");

            RuntimeException exception = assertThrows(
                RuntimeException.class,
                () -> runApplication(
                    AutoConfiguredApplication.class,
                    safeArguments(
                        "jdbc:mysql://127.0.0.1/connex_seed?sslMode=DISABLED"
                    )
                )
            );

            assertTrue(
                refusalMessage(exception).contains("hikaricp.configurationFile")
            );
            assertNoCanaryActivity();
        } finally {
            restoreSystemProperty("hikaricp.configurationFile", previousValue);
        }
    }

    private static ConfigurableApplicationContext runApplication(
            Class<?> applicationClass,
            String... arguments) {
        SpringApplication application =
            SeederApplication.createSpringApplication(applicationClass);
        application.setBannerMode(Banner.Mode.OFF);
        application.setLogStartupInfo(false);
        application.setRegisterShutdownHook(false);
        return application.run(arguments);
    }

    private static String[] safeArguments(
            String jdbcUrl,
            String... additionalArguments) {
        String[] arguments = new String[additionalArguments.length + 5];
        arguments[0] = "--spring.profiles.active=seeder";
        arguments[1] = "--connex.seeder.enabled=true";
        arguments[2] = "--spring.datasource.url=" + jdbcUrl;
        arguments[3] = "--spring.datasource.username=seeder";
        arguments[4] = "--spring.datasource.password=seeder";
        System.arraycopy(
            additionalArguments,
            0,
            arguments,
            5,
            additionalArguments.length
        );
        return arguments;
    }

    private static String refusalMessage(Throwable exception) {
        Throwable current = exception;
        while (current != null) {
            if (current.getMessage() != null
                    && (current.getMessage().startsWith("Seeder refused:")
                        || current.getMessage().contains("VERIFY_CA"))) {
                return current.getMessage();
            }
            current = current.getCause();
        }
        return "";
    }

    private static void assertNoCanaryActivity() {
        assertEquals(0, CLASS_INITIALIZATIONS.get());
        assertEquals(0, CONSTRUCTIONS.get());
        assertEquals(0, DRIVER_CONNECTS.get());
        assertEquals(0, DATA_SOURCE_CONNECTIONS.get());
        assertEquals(0, METADATA_READS.get());
        assertEquals(0, SQL_EXECUTIONS.get());
        assertEquals(0, JNDI_LOOKUPS.get());
    }

    private static void restoreSystemProperty(String propertyName, String previousValue) {
        if (previousValue == null) {
            System.clearProperty(propertyName);
        } else {
            System.setProperty(propertyName, previousValue);
        }
    }

    private static Connection canaryConnection() {
        return (Connection) Proxy.newProxyInstance(
            SeederStartupEnvironmentPostProcessorTest.class.getClassLoader(),
            new Class<?>[] {Connection.class},
            (proxy, method, arguments) -> {
                if ("getMetaData".equals(method.getName())) {
                    METADATA_READS.incrementAndGet();
                }
                if ("createStatement".equals(method.getName())) {
                    return canaryStatement(Statement.class);
                }
                if ("prepareStatement".equals(method.getName())) {
                    return canaryStatement(PreparedStatement.class);
                }
                if ("close".equals(method.getName())) {
                    return null;
                }
                Class<?> returnType = method.getReturnType();
                if (!returnType.isPrimitive()) {
                    return null;
                }
                if (returnType == boolean.class) {
                    return false;
                }
                if (returnType == char.class) {
                    return '\0';
                }
                return 0;
            }
        );
    }

    private static Object canaryStatement(Class<?> statementType) {
        return Proxy.newProxyInstance(
            SeederStartupEnvironmentPostProcessorTest.class.getClassLoader(),
            new Class<?>[] {statementType},
            (proxy, method, arguments) -> {
                if (method.getName().startsWith("execute")) {
                    SQL_EXECUTIONS.incrementAndGet();
                }
                if ("close".equals(method.getName())) {
                    return null;
                }
                Class<?> returnType = method.getReturnType();
                if (!returnType.isPrimitive()) {
                    return null;
                }
                if (returnType == boolean.class) {
                    return false;
                }
                if (returnType == char.class) {
                    return '\0';
                }
                return 0;
            }
        );
    }

    @Configuration(proxyBeanMethods = false)
    static class MinimalApplication {
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class AutoConfiguredApplication {
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class BeanCanaryApplication {

        @Bean
        DataSource dataSource() {
            return new CanaryDataSource();
        }
    }

    static final class CanaryDriver implements Driver {

        static {
            CLASS_INITIALIZATIONS.incrementAndGet();
        }

        CanaryDriver() {
            CONSTRUCTIONS.incrementAndGet();
        }

        @Override
        public Connection connect(String url, Properties info) {
            DRIVER_CONNECTS.incrementAndGet();
            return canaryConnection();
        }

        @Override
        public boolean acceptsURL(String url) {
            return true;
        }

        @Override
        public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) {
            return new DriverPropertyInfo[0];
        }

        @Override
        public int getMajorVersion() {
            return 1;
        }

        @Override
        public int getMinorVersion() {
            return 0;
        }

        @Override
        public boolean jdbcCompliant() {
            return false;
        }

        @Override
        public Logger getParentLogger() {
            return Logger.getGlobal();
        }
    }

    static final class CanaryDataSource implements DataSource {

        static {
            CLASS_INITIALIZATIONS.incrementAndGet();
        }

        CanaryDataSource() {
            CONSTRUCTIONS.incrementAndGet();
        }

        @Override
        public Connection getConnection() {
            DATA_SOURCE_CONNECTIONS.incrementAndGet();
            return canaryConnection();
        }

        @Override
        public Connection getConnection(String username, String password) {
            DATA_SOURCE_CONNECTIONS.incrementAndGet();
            return canaryConnection();
        }

        @Override
        public PrintWriter getLogWriter() {
            return null;
        }

        @Override
        public void setLogWriter(PrintWriter out) {
        }

        @Override
        public void setLoginTimeout(int seconds) {
        }

        @Override
        public int getLoginTimeout() {
            return 0;
        }

        @Override
        public Logger getParentLogger() throws SQLFeatureNotSupportedException {
            return Logger.getGlobal();
        }

        @Override
        public <T> T unwrap(Class<T> iface) throws SQLException {
            throw new SQLException("unsupported");
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) {
            return false;
        }
    }

    static final class CanaryCredentialsProvider implements HikariCredentialsProvider {

        static {
            CLASS_INITIALIZATIONS.incrementAndGet();
        }

        CanaryCredentialsProvider() {
            CONSTRUCTIONS.incrementAndGet();
        }

        @Override
        public Credentials getCredentials() {
            return Credentials.of("canary", "canary");
        }
    }

    static final class CanaryExceptionOverride implements SQLExceptionOverride {

        static {
            CLASS_INITIALIZATIONS.incrementAndGet();
        }

        CanaryExceptionOverride() {
            CONSTRUCTIONS.incrementAndGet();
        }

        @java.lang.Override
        public Override adjudicate(SQLException sqlException) {
            return Override.CONTINUE_EVICT;
        }
    }

    static final class CanaryInitialContextFactory implements InitialContextFactory {

        static {
            CLASS_INITIALIZATIONS.incrementAndGet();
        }

        CanaryInitialContextFactory() {
            CONSTRUCTIONS.incrementAndGet();
        }

        @Override
        public Context getInitialContext(java.util.Hashtable<?, ?> environment)
                throws NamingException {
            return (Context) Proxy.newProxyInstance(
                SeederStartupEnvironmentPostProcessorTest.class.getClassLoader(),
                new Class<?>[] {Context.class},
                (proxy, method, arguments) -> {
                    if (method.getName().startsWith("lookup")) {
                        JNDI_LOOKUPS.incrementAndGet();
                    }
                    return null;
                }
            );
        }
    }
}
