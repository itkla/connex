package ooo.klae.connex.backend.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;

import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.services.AuditService;
import ooo.klae.connex.backend.services.PasskeyBootstrapConfirmationPolicy;
import ooo.klae.connex.backend.services.PrivilegedAccountService;

/** Exercises the real configuration files and bean-initialization guard without a database. */
class PrivilegedMfaStartupConfigurationTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withPropertyValues("spring.config.location=classpath:/application.yml")
            .withUserConfiguration(PolicyConfiguration.class);

    @Test
    void defaultsRequireConfirmationForPasswordBackedPrivilegedAccounts() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            PasskeyBootstrapConfirmationPolicy policy = context.getBean(PasskeyBootstrapConfirmationPolicy.class);
            User user = new User();
            user.setPasswordHash("stored-password-hash");
            when(context.getBean(UserMapper.class).getUserById(42)).thenReturn(user);
            when(context.getBean(PrivilegedAccountService.class).isPrivileged(42)).thenReturn(true);

            assertThat(policy.isConfirmationEnabled()).isTrue();
            assertThat(policy.requiresConfirmation(42)).isTrue();
            assertThat(context.getBean(PrivilegedMfaProperties.class).getChangeActor())
                    .isEqualTo("configuration-default");
        });
    }

    @Test
    void disablingConfirmationWithoutAnActorFailsDuringBeanInitialization() {
        contextRunner.withPropertyValues("CONNEX_PRIVILEGED_MFA_BOOTSTRAP_CONFIRMATION_ENABLED=false")
                .run(context -> assertThat(context).hasFailed().getFailure()
                        .hasRootCauseMessage(
                                "Disabling privileged MFA bootstrap confirmation requires an accountable change actor"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "configuration-default", " configuration-default "})
    void disablingConfirmationRejectsBlankOrDefaultActorsFromConfiguration(String actor) {
        contextRunner.withPropertyValues(
                        "CONNEX_PRIVILEGED_MFA_BOOTSTRAP_CONFIRMATION_ENABLED=false",
                        "CONNEX_PRIVILEGED_MFA_CHANGE_ACTOR=" + actor)
                .run(context -> assertThat(context).hasFailed().getFailure()
                        .hasRootCauseMessage(
                                "Disabling privileged MFA bootstrap confirmation requires an accountable change actor"));
    }

    @Test
    void anAttributedExceptionControlsEnrollmentAndTheStrictAuditTogether() {
        contextRunner.withPropertyValues(
                        "CONNEX_PRIVILEGED_MFA_BOOTSTRAP_CONFIRMATION_ENABLED=false",
                        "CONNEX_PRIVILEGED_MFA_CHANGE_ACTOR=security-change-123")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    PasskeyBootstrapConfirmationPolicy policy = context.getBean(PasskeyBootstrapConfirmationPolicy.class);
                    assertThat(policy.isConfirmationEnabled()).isFalse();
                    assertThat(policy.requiresConfirmation(42)).isFalse();
                    verifyNoInteractions(context.getBean(UserMapper.class),
                            context.getBean(PrivilegedAccountService.class));

                    context.getBean(PrivilegedMfaStartupAudit.class)
                            .run(new DefaultApplicationArguments(new String[0]));

                    verify(context.getBean(AuditService.class)).recordStrictIndependentScoped(
                            eq("auth.mfa.policy.configured"), eq("security_policy"),
                            isNull(), isNull(), isNull(), eq("privileged-mfa"),
                            eq("Privileged MFA policy configured by security-change-123"),
                            eq(Map.of("actor", "security-change-123", "configuredValue", "true",
                                    "enforced", true, "bootstrapConfirmationEnabled", false)));
                });
    }

    @Test
    void developmentDefaultsRemainExplicitAndUsableWithoutMail() {
        contextRunner.withPropertyValues("spring.profiles.active=dev")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(PasskeyBootstrapConfirmationPolicy.class).isConfirmationEnabled())
                            .isFalse();
                    assertThat(context.getBean(PrivilegedMfaProperties.class).getChangeActor())
                            .isEqualTo("local-development");
                });
    }

    @Test
    void developmentPreservesAnExplicitOperatorActor() {
        contextRunner.withPropertyValues("spring.profiles.active=dev",
                        "CONNEX_PRIVILEGED_MFA_CHANGE_ACTOR=frontend-e2e-suite")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(PrivilegedMfaProperties.class).getChangeActor())
                            .isEqualTo("frontend-e2e-suite");
                });
    }

    @Test
    void developmentDoesNotReplaceAnExplicitBlankActorWithItsDefault() {
        contextRunner.withPropertyValues("spring.profiles.active=dev", "CONNEX_PRIVILEGED_MFA_CHANGE_ACTOR=")
                .run(context -> assertThat(context).hasFailed().getFailure()
                        .hasRootCauseMessage(
                                "Disabling privileged MFA bootstrap confirmation requires an accountable change actor"));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(PrivilegedMfaProperties.class)
    @Import({PasskeyBootstrapConfirmationPolicy.class, PrivilegedMfaStartupAudit.class})
    static class PolicyConfiguration {
        @Bean
        static PropertySourcesPlaceholderConfigurer propertySourcesPlaceholderConfigurer() {
            return new PropertySourcesPlaceholderConfigurer();
        }

        @Bean
        PrivilegedAccountService privilegedAccountService() {
            return mock(PrivilegedAccountService.class);
        }

        @Bean
        UserMapper userMapper() {
            return mock(UserMapper.class);
        }

        @Bean
        AuditService auditService() {
            return mock(AuditService.class);
        }

        @Bean
        Clock clock() {
            return Clock.systemUTC();
        }
    }
}
