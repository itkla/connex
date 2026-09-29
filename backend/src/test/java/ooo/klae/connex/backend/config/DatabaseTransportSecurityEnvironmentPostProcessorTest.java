package ooo.klae.connex.backend.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.session.autoconfigure.SessionAutoConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.env.MockPropertySource;
import org.springframework.session.MapSession;
import org.springframework.session.MapSessionRepository;
import org.springframework.session.web.http.CookieHttpSessionIdResolver;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.session.web.http.SessionRepositoryFilter;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.GenericWebApplicationContext;

import ooo.klae.connex.backend.controllers.AuthController;
import ooo.klae.connex.backend.dto.LoginDto;
import ooo.klae.connex.backend.services.AuthService;

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
    void allowsExactNonWebSeederProfileWithLoopbackPlaintextMysqlUrl() {
        MockEnvironment environment = seederEnvironment()
            .withProperty(
                "spring.datasource.url",
                "jdbc:mysql://127.0.0.1:3313/connex_seeder?sslMode=DISABLED"
            )
            .withProperty("spring.datasource.username", "connex")
            .withProperty("spring.datasource.password", "x")
            .withProperty(
                "spring.flyway.url",
                "jdbc:mysql://localhost:3313/connex_seeder?sslMode=DISABLED"
            );

        assertDoesNotThrow(() -> postProcessor.postProcessEnvironment(environment, null));
    }

    @Test
    void refusesSeederPlaintextUrlWithoutCompleteIsolationProperties() {
        MockEnvironment environment = new MockEnvironment()
            .withProperty(
                "spring.datasource.url",
                "jdbc:mysql://127.0.0.1:3313/connex_seeder?sslMode=DISABLED"
            )
            .withProperty("spring.datasource.username", "connex")
            .withProperty("spring.datasource.password", "x")
            .withProperty("connex.maintenance.mode", "seeder")
            .withProperty("spring.main.web-application-type", "none");
        environment.setActiveProfiles("seeder");

        assertThrows(
            IllegalStateException.class,
            () -> postProcessor.postProcessEnvironment(environment, null)
        );
    }

    @Test
    void refusesRemoteSeederPlaintextUrlEvenWithSeederRemoteGuardOverride() {
        MockEnvironment environment = seederEnvironment()
            .withProperty(
                "spring.datasource.url",
                "jdbc:mysql://db.example.com:3306/connex_seeder?sslMode=DISABLED"
            )
            .withProperty("spring.datasource.username", "connex")
            .withProperty("spring.datasource.password", "x")
            .withProperty("connex.seeder.allow-remote-host", "true");

        assertThrows(
            IllegalStateException.class,
            () -> postProcessor.postProcessEnvironment(environment, null)
        );
    }

    @Test
    void allowsLocalSystemdStagingLoopbackPlaintextMysqlUrl() {
        MockEnvironment environment = localSystemdStagingEnvironment()
            .withProperty("spring.datasource.url", "jdbc:mysql://localhost:3306/connexdb?sslMode=DISABLED")
            .withProperty("spring.datasource.username", "connex")
            .withProperty("spring.datasource.password", "x");

        assertDoesNotThrow(() -> postProcessor.postProcessEnvironment(environment, null));
        assertEquals("true", environment.getProperty("CONNEX_SESSION_COOKIE_SECURE", "true"));
        assertEquals("true", environment.getProperty("CONNEX_WORKSPACE_COOKIE_SECURE", "true"));
        assertEquals(
            "http://localhost:3001",
            environment.getProperty("CONNEX_CORS_ALLOWED_ORIGINS")
        );
        assertEquals(
            "http://localhost:3001",
            environment.getProperty("CONNEX_WEBAUTHN_ALLOWED_ORIGINS")
        );
        assertEquals("localhost", environment.getProperty("CONNEX_WEBAUTHN_RP_ID"));
    }

    @Test
    void failsLocalSystemdStagingWithDevProfile() {
        MockEnvironment environment = localSystemdStagingEnvironment();
        environment.setActiveProfiles("dev");

        assertThrows(IllegalStateException.class, () -> postProcessor.postProcessEnvironment(environment, null));
    }

    @Test
    void localSystemdStagingDefaultsDoNotOverrideEnvironmentProperties() {
        MockEnvironment environment = localSystemdStagingEnvironment()
            .withProperty("spring.datasource.url", "jdbc:mysql://localhost:3306/connexdb?sslMode=DISABLED")
            .withProperty("spring.datasource.username", "connex")
            .withProperty("spring.datasource.password", "x");
        MockPropertySource systemEnvironment = new MockPropertySource(
            StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME
        )
            .withProperty("CONNEX_CORS_ALLOWED_ORIGINS", "https://staging.example")
            .withProperty("CONNEX_SESSION_COOKIE_SECURE", "true");
        environment.getPropertySources().addFirst(systemEnvironment);

        assertDoesNotThrow(() -> postProcessor.postProcessEnvironment(environment, null));
        assertEquals("https://staging.example", environment.getProperty("CONNEX_CORS_ALLOWED_ORIGINS"));
        assertEquals("true", environment.getProperty("CONNEX_SESSION_COOKIE_SECURE"));
    }

    @Test
    void localSystemdStagingDefaultsResolveThroughApplicationPlaceholders() {
        MockEnvironment environment = localSystemdStagingEnvironment()
            .withProperty("spring.datasource.url", "jdbc:mysql://localhost:3306/connexdb?sslMode=DISABLED")
            .withProperty("spring.datasource.username", "connex")
            .withProperty("spring.datasource.password", "x")
            .withProperty("server.servlet.session.cookie.secure", "${CONNEX_SESSION_COOKIE_SECURE:true}")
            .withProperty("connex.workspace-cookie.secure", "${CONNEX_WORKSPACE_COOKIE_SECURE:true}")
            .withProperty("connex.cors.allowed-origins", "${CONNEX_CORS_ALLOWED_ORIGINS:http://localhost:3000}")
            .withProperty("connex.webauthn.allowed-origins", "${CONNEX_WEBAUTHN_ALLOWED_ORIGINS:http://localhost:3000}")
            .withProperty("connex.webauthn.rp-id", "${CONNEX_WEBAUTHN_RP_ID:localhost}");

        assertDoesNotThrow(() -> postProcessor.postProcessEnvironment(environment, null));
        assertEquals("true", environment.getProperty("server.servlet.session.cookie.secure"));
        assertEquals("true", environment.getProperty("connex.workspace-cookie.secure"));
        assertEquals("http://localhost:3001", environment.getProperty("connex.cors.allowed-origins"));
        assertEquals("http://localhost:3001", environment.getProperty("connex.webauthn.allowed-origins"));
        assertEquals("localhost", environment.getProperty("connex.webauthn.rp-id"));
    }

    @Test
    void failsLocalPathWithoutSystemdInvocationForLoopbackPlaintextMysqlUrl() {
        MockEnvironment environment = productionEnvironment()
            .withProperty("user.dir", "/opt/connex-staging/backend")
            .withProperty("spring.datasource.url", "jdbc:mysql://localhost:3306/connexdb?sslMode=DISABLED")
            .withProperty("spring.datasource.username", "connex")
            .withProperty("spring.datasource.password", "x");

        assertThrows(IllegalStateException.class, () -> postProcessor.postProcessEnvironment(environment, null));
    }

    @Test
    void failsLocalSystemdStagingRemotePlaintextMysqlUrl() {
        MockEnvironment environment = localSystemdStagingEnvironment()
            .withProperty("spring.datasource.url", "jdbc:mysql://db.example.com:3306/connexdb?sslMode=DISABLED")
            .withProperty("spring.datasource.username", "connex")
            .withProperty("spring.datasource.password", "x");

        assertThrows(IllegalStateException.class, () -> postProcessor.postProcessEnvironment(environment, null));
    }

    @Test
    void failsLocalSystemdStagingLoopbackPlaintextMysqlUrlWithoutExplicitDisabledSslMode() {
        MockEnvironment environment = localSystemdStagingEnvironment()
            .withProperty("spring.datasource.url", "jdbc:mysql://localhost:3306/connexdb")
            .withProperty("spring.datasource.username", "connex")
            .withProperty("spring.datasource.password", "x");

        assertThrows(IllegalStateException.class, () -> postProcessor.postProcessEnvironment(environment, null));
    }

    @Test
    void failsOutsideLocalSystemdStagingForLoopbackPlaintextMysqlUrl() {
        MockEnvironment environment = productionEnvironment()
            .withProperty("spring.datasource.url", "jdbc:mysql://localhost:3306/connexdb?sslMode=DISABLED")
            .withProperty("spring.datasource.username", "connex")
            .withProperty("spring.datasource.password", "x");

        assertThrows(IllegalStateException.class, () -> postProcessor.postProcessEnvironment(environment, null));
    }

    @Test
    void allowsLocalSystemdStagingLoopbackPlaintextHikariJdbcUrl() {
        MockEnvironment environment = localSystemdStagingEnvironment()
            .withProperty("spring.datasource.url", "jdbc:mysql://localhost:3306/connexdb?sslMode=DISABLED")
            .withProperty("spring.datasource.username", "connex")
            .withProperty("spring.datasource.password", "x")
            .withProperty("spring.datasource.hikari.jdbc-url", "jdbc:mysql://127.0.0.1:3306/connexdb?sslMode=DISABLED");

        assertDoesNotThrow(() -> postProcessor.postProcessEnvironment(environment, null));
    }

    @Test
    void failsLocalSystemdStagingMalformedLoopbackAuthority() {
        MockEnvironment environment = localSystemdStagingEnvironment()
            .withProperty(
                "spring.datasource.url",
                "jdbc:mysql://localhost:3306@db.example.com/connexdb?sslMode=DISABLED"
            )
            .withProperty("spring.datasource.username", "connex")
            .withProperty("spring.datasource.password", "x");

        assertThrows(IllegalStateException.class, () -> postProcessor.postProcessEnvironment(environment, null));
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
    void failsOutsideDevAndTestWhenFlywayUrlDisablesTls() {
        MockEnvironment environment = productionEnvironment()
            .withProperty("spring.datasource.url", "jdbc:mysql://db.example.com:3306/connexdb?sslMode=VERIFY_CA")
            .withProperty("spring.datasource.username", "connex")
            .withProperty("spring.datasource.password", "x")
            .withProperty("spring.flyway.url", "jdbc:mysql://db.example.com:3306/connexdb?sslMode=DISABLED");

        assertThrows(IllegalStateException.class, () -> postProcessor.postProcessEnvironment(environment, null));
    }

    @Test
    void failsOutsideDevAndTestWhenEnvironmentStyleFlywayUrlDisablesTls() {
        MockEnvironment environment = productionEnvironment()
            .withProperty("spring.datasource.url", "jdbc:mysql://db.example.com:3306/connexdb?sslMode=VERIFY_CA")
            .withProperty("spring.datasource.username", "connex")
            .withProperty("spring.datasource.password", "x")
            .withProperty("SPRING_FLYWAY_URL", "jdbc:mysql://db.example.com:3306/connexdb?sslMode=DISABLED");

        assertThrows(IllegalStateException.class, () -> postProcessor.postProcessEnvironment(environment, null));
    }

    @Test
    void failsOutsideDevAndTestWhenFlywayJdbcPropertiesDisableTls() {
        MockEnvironment environment = productionEnvironment()
            .withProperty("spring.datasource.url", "jdbc:mysql://db.example.com:3306/connexdb?sslMode=VERIFY_CA")
            .withProperty("spring.datasource.username", "connex")
            .withProperty("spring.datasource.password", "x")
            .withProperty("spring.flyway.url",
                "jdbc:mysql://migrations.example.com:3306/connexdb?sslMode=VERIFY_IDENTITY")
            .withProperty("spring.flyway.jdbc-properties.sslMode", "DISABLED");

        assertThrows(IllegalStateException.class, () -> postProcessor.postProcessEnvironment(environment, null));
    }

    @Test
    void failsOutsideDevAndTestWhenEnvironmentStyleFlywayJdbcPropertiesDisableTls() {
        MockEnvironment environment = productionEnvironment()
            .withProperty("spring.datasource.url", "jdbc:mysql://db.example.com:3306/connexdb?sslMode=VERIFY_CA")
            .withProperty("spring.datasource.username", "connex")
            .withProperty("spring.datasource.password", "x")
            .withProperty("SPRING_FLYWAY_JDBC_PROPERTIES_SSLMODE", "DISABLED");

        assertThrows(IllegalStateException.class, () -> postProcessor.postProcessEnvironment(environment, null));
    }

    @Test
    void allowsVerifiedFlywayUrlOutsideDevAndTest() {
        MockEnvironment environment = productionEnvironment()
            .withProperty("spring.datasource.url", "jdbc:mysql://db.example.com:3306/connexdb?sslMode=VERIFY_CA")
            .withProperty("spring.datasource.username", "connex")
            .withProperty("spring.datasource.password", "x")
            .withProperty("spring.flyway.url", "jdbc:mysql://migrations.example.com:3306/connexdb?sslMode=VERIFY_IDENTITY");

        assertDoesNotThrow(() -> postProcessor.postProcessEnvironment(environment, null));
    }

    @Test
    void allowsLocalSystemdStagingLoopbackPlaintextFlywayUrl() {
        MockEnvironment environment = localSystemdStagingEnvironment()
            .withProperty("spring.datasource.url", "jdbc:mysql://localhost:3306/connexdb?sslMode=DISABLED")
            .withProperty("spring.datasource.username", "connex")
            .withProperty("spring.datasource.password", "x")
            .withProperty("spring.flyway.url", "jdbc:mysql://127.0.0.1:3306/connexdb?sslMode=DISABLED");

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

    /**
     * Exercises executable-archive cookie wiring, where auto-configuration runs before a servlet context exists.
     * A pre-bound mock servlet context selects Boot's external-WAR serializer, which reads container cookie
     * settings instead of {@code server.servlet.session.cookie.*} from {@code application.yml}.
     */
    @Test
    void stagingLoginEmitsSecureSessionCookieWithUnsetOverrides() throws IOException {
        MockEnvironment environment = configuredStagingEnvironment();
        postProcessor.postProcessEnvironment(environment, null);

        assertEquals("true", environment.getProperty("server.servlet.session.cookie.secure"));
        assertEquals("true", environment.getProperty("connex.workspace-cookie.secure"));

        new WebApplicationContextRunner(GenericWebApplicationContext::new)
            .withInitializer(context -> context.setEnvironment(environment))
            .withConfiguration(AutoConfigurations.of(SessionAutoConfiguration.class))
            .run(context -> {
                AuthService authService = mock(AuthService.class);
                doAnswer(invocation -> {
                    HttpServletRequest request = invocation.getArgument(1, HttpServletRequest.class);
                    request.getSession().setAttribute("authenticated", true);
                    request.changeSessionId();
                    return null;
                }).when(authService).login(any(LoginDto.class), any(HttpServletRequest.class), any(HttpServletResponse.class));
                AuthController controller = new AuthController(authService, mock(), mock(), mock(), mock(), mock());
                CookieHttpSessionIdResolver resolver = new CookieHttpSessionIdResolver();
                resolver.setCookieSerializer(context.getBean(CookieSerializer.class));
                SessionRepositoryFilter<MapSession> sessions =
                    new SessionRepositoryFilter<>(new MapSessionRepository(new ConcurrentHashMap<>()));
                sessions.setHttpSessionIdResolver(resolver);

                String cookie = MockMvcBuilders.standaloneSetup(controller).addFilters(sessions).build()
                    .perform(post("/api/auth/login").secure(false)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"staging-user\",\"password\":\"synthetic-password\"}"))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getHeader("Set-Cookie");

                assertNotNull(cookie, "no Set-Cookie header was written");
                assertTrue(cookie.startsWith("JSESSIONID="), cookie);
                assertTrue(cookie.contains("; Secure"), cookie);
                verify(authService).login(any(LoginDto.class), any(HttpServletRequest.class), any(HttpServletResponse.class));
            });
    }

    @Test
    void localHttpCookieExceptionRequiresExplicitOptIn() throws IOException {
        MockEnvironment environment = configuredStagingEnvironment()
            .withProperty("CONNEX_LOCAL_HTTP_COOKIES_ENABLED", "true");

        postProcessor.postProcessEnvironment(environment, null);

        assertEquals("false", environment.getProperty("server.servlet.session.cookie.secure"));
        assertEquals("false", environment.getProperty("connex.workspace-cookie.secure"));
        assertEquals("http://localhost:3001", environment.getProperty("connex.cors.allowed-origins"));
        assertEquals("localhost", environment.getProperty("connex.webauthn.rp-id"));
    }

    @Test
    void refusesLocalHttpCookieOptInForPublicOrMalformedOrigins() throws IOException {
        for (String property : new String[] {"CONNEX_CORS_ALLOWED_ORIGINS", "CONNEX_WEBAUTHN_ALLOWED_ORIGINS"}) {
            for (String origin : new String[] {
                "https://preview.connexcrm.jp", "http://preview.connexcrm.jp",
                "http://localhost:3001,https://preview.connexcrm.jp", "http://localhost.evil.test",
                "http://localhost@evil.test", "http://localhost:3001/redirect", "*", ""
            }) {
                MockEnvironment environment = configuredStagingEnvironment()
                    .withProperty("CONNEX_LOCAL_HTTP_COOKIES_ENABLED", "true")
                    .withProperty(property, origin);
                assertThrows(IllegalStateException.class, () -> postProcessor.postProcessEnvironment(environment, null), origin);
            }
        }
    }

    @Test
    void refusesLocalHttpCookieOptInForIndexedPublicWebAuthnOrigin() throws IOException {
        MockEnvironment environment = configuredStagingEnvironment()
            .withProperty("CONNEX_LOCAL_HTTP_COOKIES_ENABLED", "true")
            .withProperty("connex.webauthn.allowed-origins[0]", "https://preview.connexcrm.jp");

        assertThrows(IllegalStateException.class, () -> postProcessor.postProcessEnvironment(environment, null));
    }

    @Test
    void refusesLocalHttpCookieOptInWhenIndexedCorsOriginMasksPublicScalar() throws IOException {
        MockEnvironment environment = configuredStagingEnvironment()
            .withProperty("CONNEX_LOCAL_HTTP_COOKIES_ENABLED", "true")
            .withProperty("CONNEX_CORS_ALLOWED_ORIGINS", "https://preview.connexcrm.jp")
            .withProperty("connex.cors.allowed-origins[0]", "http://localhost:3001");

        assertThrows(IllegalStateException.class, () -> postProcessor.postProcessEnvironment(environment, null));
    }

    @Test
    void refusesLegacyInsecureStagingOverridesWithoutLocalHttpOptIn() throws IOException {
        for (String property : new String[] {
            "CONNEX_SESSION_COOKIE_SECURE", "CONNEX_WORKSPACE_COOKIE_SECURE",
            "server.servlet.session.cookie.secure", "connex.workspace-cookie.secure",
            "server.servlet.session.cookie.Secure", "connex.workspaceCookie.secure"
        }) {
            for (String value : new String[] {"false", "", " "}) {
                MockEnvironment environment = configuredStagingEnvironment().withProperty(property, value);
                assertThrows(IllegalStateException.class, () -> postProcessor.postProcessEnvironment(environment, null), property);
            }
        }
    }

    @Test
    void refusesLocalHttpCookieOptInOutsideSystemdStaging() throws IOException {
        MockEnvironment environment = configuredStagingEnvironment()
            .withProperty("user.dir", "/opt/connex/backend")
            .withProperty("spring.datasource.url", "jdbc:mysql://db.example.test/connexdb?sslMode=VERIFY_IDENTITY")
            .withProperty("CONNEX_LOCAL_HTTP_COOKIES_ENABLED", "true");

        assertThrows(IllegalStateException.class, () -> postProcessor.postProcessEnvironment(environment, null));
    }

    private static MockEnvironment configuredStagingEnvironment() throws IOException {
        MockEnvironment environment = localSystemdStagingEnvironment()
            .withProperty("CONNEX_DEPLOYMENT_PROFILE", "silo")
            .withProperty("spring.datasource.url", "jdbc:mysql://localhost:3306/connexdb?sslMode=DISABLED")
            .withProperty("spring.datasource.username", "connex")
            .withProperty("spring.datasource.password", "x");
        environment.getPropertySources().addLast(new MockPropertySource(
            StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME));
        for (var propertySource : new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"))) {
            environment.getPropertySources().addLast(propertySource);
        }
        ConfigurationPropertySources.attach(environment);
        return environment;
    }

    private static MockEnvironment productionEnvironment() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");
        return environment;
    }

    private static MockEnvironment localSystemdStagingEnvironment() {
        return productionEnvironment()
            .withProperty("user.dir", "/opt/connex-staging/backend")
            .withProperty("INVOCATION_ID", "4df8e80cad3c4b36a3e3a11f47c7f4f5");
    }

    private static MockEnvironment seederEnvironment() {
        MockEnvironment environment = new MockEnvironment()
            .withProperty("connex.seeder.enabled", "true")
            .withProperty("connex.maintenance.mode", "seeder")
            .withProperty("spring.main.web-application-type", "none");
        environment.setActiveProfiles("seeder");
        return environment;
    }
}
