package ooo.klae.connex.backend.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.web.server.WebServer;
import org.springframework.boot.web.server.servlet.ServletWebServerFactory;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.mock.env.MockEnvironment;

import ooo.klae.connex.backend.seeder.SeederStartupEnvironmentPostProcessor;

class DeploymentProfileEnvironmentPostProcessorTest {

    private static final AtomicInteger WEB_SERVER_CREATIONS = new AtomicInteger();

    @BeforeEach
    void resetWebServerCreations() {
        WEB_SERVER_CREATIONS.set(0);
    }

    @Test
    void declaresPostConfigDataPreSeederAndTransportOrder() {
        DeploymentProfileEnvironmentPostProcessor processor =
            new DeploymentProfileEnvironmentPostProcessor();
        SeederStartupEnvironmentPostProcessor seederProcessor =
            new SeederStartupEnvironmentPostProcessor();
        DatabaseTransportSecurityEnvironmentPostProcessor transportProcessor =
            new DatabaseTransportSecurityEnvironmentPostProcessor();

        assertEquals(Integer.MAX_VALUE - 2, processor.getOrder());
        assertTrue(processor.getOrder() > ConfigDataEnvironmentPostProcessor.ORDER);
        assertTrue(processor.getOrder() < seederProcessor.getOrder());
        assertTrue(processor.getOrder() < transportProcessor.getOrder());
    }

    @Test
    void resolvesDeploymentPostureFromEnvironmentVariablesWithRelaxedBinding() {
        MockEnvironment environment = new MockEnvironment();
        environment.getPropertySources().addFirst(new SystemEnvironmentPropertySource(
            "deploymentProfileEnvironment",
            Map.of(
                "CONNEX_DEPLOYMENT_PROFILE", "on-prem",
                "CONNEX_MAIL_MANAGED", "true"
            )
        ));

        IllegalStateException exception = assertThrows(
            IllegalStateException.class,
            () -> new DeploymentProfileEnvironmentPostProcessor()
                .postProcessEnvironment(environment, new SpringApplication(ProbeApplication.class))
        );

        assertEquals("connex.deployment.profile=on-prem forbids: connex.mail.managed=true",
            exception.getMessage());
    }

    /**
     * The scripted AI adapter must be unreachable in a deployed edition, not merely unused.
     *
     * <p>Refusing here rather than from an {@code ApplicationRunner} is what makes that true: the
     * check runs after ConfigData and before the application context exists, so no scripted bean
     * can be constructed even transiently.
     */
    @Test
    void refusesTheScriptedAiProviderProfileBeforeTheContextExists() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("ai-scripted-provider", "dev");
        environment.getPropertySources().addFirst(new SystemEnvironmentPropertySource(
            "deploymentProfileEnvironment",
            Map.of(
                "CONNEX_DEPLOYMENT_PROFILE", "saas",
                "CONNEX_AI_SCRIPTED_PROVIDER_ENABLED", "true"
            )
        ));

        IllegalStateException exception = assertThrows(
            IllegalStateException.class,
            () -> new DeploymentProfileEnvironmentPostProcessor()
                .postProcessEnvironment(environment, new SpringApplication(ProbeApplication.class))
        );

        assertEquals("connex.deployment.profile=saas forbids the ai-scripted-provider "
            + "Spring profile", exception.getMessage());
    }

    @Test
    void refusesTheScriptedAiProviderFlagWithoutItsProfileBeforeTheContextExists() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("dev");
        environment.getPropertySources().addFirst(new SystemEnvironmentPropertySource(
            "deploymentProfileEnvironment",
            Map.of("CONNEX_AI_SCRIPTED_PROVIDER_ENABLED", "true")
        ));

        IllegalStateException exception = assertThrows(
            IllegalStateException.class,
            () -> new DeploymentProfileEnvironmentPostProcessor()
                .postProcessEnvironment(environment, new SpringApplication(ProbeApplication.class))
        );

        assertEquals("connex.ai.scripted-provider.enabled requires the ai-scripted-provider "
            + "Spring profile", exception.getMessage());
    }

    /**
     * The same refusal through the registered processor, with real ConfigData supplying the keys.
     *
     * <p>The Spring profiles are passed as arguments rather than declared in the loaded file
     * because an ambient {@code SPRING_PROFILES_ACTIVE} outranks a config-data declaration, and a
     * startup refusal must not depend on the developer's shell.
     */
    @Test
    void refusesTheScriptedAiProviderProfileFromConfigDataBeforeWebServerCreation(
            @TempDir Path temporaryDirectory) throws IOException {
        Path configFile = temporaryDirectory.resolve("application.yml");
        Files.writeString(configFile, """
            connex:
              deployment:
                profile: silo
              ai:
                scripted-provider:
                  enabled: true
            """);

        RuntimeException exception = assertThrows(
            RuntimeException.class,
            () -> runApplication(
                "--spring.profiles.active=test,ai-scripted-provider",
                "--spring.config.additional-location=" + configFile.toUri()
            )
        );

        assertEquals("connex.deployment.profile=silo forbids the ai-scripted-provider "
            + "Spring profile", refusalMessage(exception));
        assertEquals(0, WEB_SERVER_CREATIONS.get());
    }

    @Test
    void registeredProcessorSeesConfigDataAndRefusesBeforeWebServerCreation(
            @TempDir Path temporaryDirectory) throws IOException {
        Path configFile = temporaryDirectory.resolve("application.yml");
        Files.writeString(configFile, """
            spring:
              profiles:
                active: test
            connex:
              deployment:
                profile: on-prem
              mail:
                managed: true
            """);

        RuntimeException exception = assertThrows(
            RuntimeException.class,
            () -> runApplication(
                "--spring.config.additional-location=" + configFile.toUri()
            )
        );

        assertEquals("connex.deployment.profile=on-prem forbids: connex.mail.managed=true",
            refusalMessage(exception));
        assertEquals(0, WEB_SERVER_CREATIONS.get());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        DeploymentProperties.PROFILE_SAAS,
        DeploymentProperties.PROFILE_SILO,
        DeploymentProperties.PROFILE_ON_PREM
    })
    void supportedProfileReachesWebServerCreation(String profile) {
        assertDoesNotThrow(() -> {
            try (ConfigurableApplicationContext ignored = runApplication(
                    "--spring.profiles.active=test",
                    "--connex.deployment.profile=" + profile,
                    "--connex.mail.managed=false")) {
                assertEquals(1, WEB_SERVER_CREATIONS.get());
            }
        });
    }

    private static ConfigurableApplicationContext runApplication(String... arguments) {
        return probeApplication().run(arguments);
    }

    private static SpringApplication probeApplication() {
        SpringApplication application = new SpringApplication(ProbeApplication.class);
        application.setWebApplicationType(WebApplicationType.SERVLET);
        application.setBannerMode(Banner.Mode.OFF);
        application.setLogStartupInfo(false);
        application.setRegisterShutdownHook(false);
        return application;
    }

    private static String refusalMessage(Throwable exception) {
        Throwable current = exception;
        while (current != null) {
            if (current.getMessage() != null
                    && current.getMessage().startsWith("connex.deployment.profile=")) {
                return current.getMessage();
            }
            current = current.getCause();
        }
        return "";
    }

    @Configuration(proxyBeanMethods = false)
    static class ProbeApplication {

        @Bean
        ServletWebServerFactory servletWebServerFactory() {
            return initializers -> {
                WEB_SERVER_CREATIONS.incrementAndGet();
                return new ProbeWebServer();
            };
        }
    }

    static class ProbeWebServer implements WebServer {

        @Override
        public void start() {
        }

        @Override
        public void stop() {
        }

        @Override
        public int getPort() {
            return 0;
        }
    }
}
