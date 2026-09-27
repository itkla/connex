package ooo.klae.connex.backend.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;

import ooo.klae.connex.backend.beans.UnenrolledPrivilegedAccount;
import ooo.klae.connex.backend.beans.UnenrolledPrivilegedAccountCounts;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.services.AuditService;
import ooo.klae.connex.backend.services.AuthService;
import ooo.klae.connex.backend.services.PasskeyBootstrapConfirmationEmailService;
import ooo.klae.connex.backend.services.PasskeyBootstrapConfirmationPolicy;
import ooo.klae.connex.backend.services.PrivilegedAccountService;

/**
 * Boots a real, database-free {@link SpringApplication} so the startup runners and the ready
 * listeners fire in Spring Boot's own order. The collaborators model a fresh install: the
 * instance has no account until {@link BootstrapRunner} provisions the founding owner, who is
 * privileged, password-backed, and holds no passkey.
 */
class PrivilegedMfaStartupLifecycleTest {
    private static final int FOUNDING_OWNER_ID = 1;

    /**
     * The posture is recorded after bootstrap provisioning, so the founding owner is counted on
     * the boot that creates it, and before the readiness marker is published.
     */
    @Test
    void aBootstrappedUnenrolledOwnerIsCountedOnTheFirstBoot(@TempDir Path directory) {
        Path readinessFile = directory.resolve("ready");
        try (ConfigurableApplicationContext context = firstBoot(readinessFile)) {
            ArgumentCaptor<Object> posture = ArgumentCaptor.forClass(Object.class);
            verify(context.getBean(AuditService.class)).recordStrictIndependentScoped(
                    eq("auth.mfa.policy.configured"), eq("security_policy"),
                    isNull(), isNull(), isNull(), eq("privileged-mfa"), any(), posture.capture());
            Map<?, ?> recorded = assertInstanceOf(Map.class, posture.getValue());

            verify(context.getBean(AuthService.class)).provisionBootstrapOwner(any());
            assertEquals(1L, recorded.get("unenrolledPrivilegedCount"));
            assertEquals(0L, recorded.get("unenrolledWithoutSelfServiceCount"));
            assertFalse(context.getBean(FreshInstance.class).readyBeforePosture.get());
            assertTrue(Files.exists(readinessFile));
        }
    }

    /** Moving the strict event to the ready phase keeps its failure fatal to startup. */
    @Test
    void aPostureThatCannotBeRecordedStillFailsStartup(@TempDir Path directory) {
        Path readinessFile = directory.resolve("ready");
        IllegalStateException persistenceFailure = new IllegalStateException("audit unavailable");

        IllegalStateException refusal = assertThrows(IllegalStateException.class,
                () -> firstBoot(readinessFile, persistenceFailure).close());

        assertSame(persistenceFailure, refusal);
        assertFalse(Files.exists(readinessFile));
    }

    private static ConfigurableApplicationContext firstBoot(Path readinessFile) {
        return firstBoot(readinessFile, null);
    }

    private static ConfigurableApplicationContext firstBoot(
            Path readinessFile, RuntimeException auditFailure) {
        SpringApplication application = new SpringApplication(FirstBootConfiguration.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setBannerMode(Banner.Mode.OFF);
        application.setLogStartupInfo(false);
        application.setRegisterShutdownHook(false);
        application.addInitializers(context -> context.getBeanFactory().registerSingleton(
                "freshInstance", new FreshInstance(readinessFile, auditFailure)));
        return application.run(
                "--connex.bootstrap.enabled=true",
                "--connex.bootstrap.username=founder",
                "--connex.bootstrap.email=founder@example.com",
                "--connex.bootstrap.password=Aa1!aaaa-founder",
                "--connex.readiness-file=" + readinessFile);
    }

    /** Collaborator state for one boot: whether the founding owner exists yet. */
    static final class FreshInstance {
        private final AtomicBoolean ownerProvisioned = new AtomicBoolean();
        private final AtomicBoolean readyBeforePosture = new AtomicBoolean();
        private final Path readinessFile;
        private final RuntimeException auditFailure;

        FreshInstance(Path readinessFile, RuntimeException auditFailure) {
            this.readinessFile = readinessFile;
            this.auditFailure = auditFailure;
        }

        UnenrolledPrivilegedAccountCounts counts() {
            return ownerProvisioned.get()
                    ? new UnenrolledPrivilegedAccountCounts(1, 1, 0)
                    : new UnenrolledPrivilegedAccountCounts(0, 0, 0);
        }

        List<UnenrolledPrivilegedAccount> accounts() {
            return ownerProvisioned.get()
                    ? List.of(new UnenrolledPrivilegedAccount(FOUNDING_OWNER_ID, true, true))
                    : List.of();
        }

        User provisionOwner() {
            ownerProvisioned.set(true);
            User owner = new User();
            owner.setId(FOUNDING_OWNER_ID);
            return owner;
        }

        void recordPosture() {
            readyBeforePosture.set(Files.exists(readinessFile));
            if (auditFailure != null) {
                throw auditFailure;
            }
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(PrivilegedMfaProperties.class)
    @Import({BootstrapRunner.class, PrivilegedMfaStartupAudit.class, ReadinessFilePublisher.class})
    static class FirstBootConfiguration {
        @Bean
        static PropertySourcesPlaceholderConfigurer propertySourcesPlaceholderConfigurer() {
            return new PropertySourcesPlaceholderConfigurer();
        }

        @Bean
        UserMapper userMapper(FreshInstance instance) {
            UserMapper userMapper = mock(UserMapper.class);
            when(userMapper.countUsers()).thenAnswer(invocation -> instance.ownerProvisioned.get() ? 1 : 0);
            return userMapper;
        }

        @Bean
        AuthService authService(FreshInstance instance) {
            AuthService authService = mock(AuthService.class);
            when(authService.provisionBootstrapOwner(any())).thenAnswer(invocation -> instance.provisionOwner());
            return authService;
        }

        @Bean
        PrivilegedAccountService privilegedAccountService(FreshInstance instance) {
            PrivilegedAccountService privilegedAccounts = mock(PrivilegedAccountService.class);
            when(privilegedAccounts.unenrolledPrivilegedAccountCounts())
                    .thenAnswer(invocation -> instance.counts());
            when(privilegedAccounts.unenrolledPrivilegedAccounts(PrivilegedMfaStartupAudit.INVENTORY_LOG_LIMIT))
                    .thenAnswer(invocation -> instance.accounts());
            return privilegedAccounts;
        }

        @Bean
        PasskeyBootstrapConfirmationPolicy passkeyBootstrapConfirmationPolicy() {
            PasskeyBootstrapConfirmationPolicy policy = mock(PasskeyBootstrapConfirmationPolicy.class);
            when(policy.isConfirmationEnabled()).thenReturn(true);
            return policy;
        }

        @Bean
        PasskeyBootstrapConfirmationEmailService passkeyBootstrapConfirmationEmailService() {
            PasskeyBootstrapConfirmationEmailService emailService =
                    mock(PasskeyBootstrapConfirmationEmailService.class);
            when(emailService.canDeliver()).thenReturn(true);
            return emailService;
        }

        @Bean
        AuditService auditService(FreshInstance instance) {
            AuditService auditService = mock(AuditService.class);
            doAnswer(invocation -> {
                instance.recordPosture();
                return null;
            }).when(auditService).recordStrictIndependentScoped(
                    eq("auth.mfa.policy.configured"), any(), any(), any(), any(), any(), any(), any());
            return auditService;
        }

        @Bean
        Clock clock() {
            return Clock.systemUTC();
        }
    }
}
