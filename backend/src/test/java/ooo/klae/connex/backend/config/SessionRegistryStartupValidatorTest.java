package ooo.klae.connex.backend.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.session.SessionRegistryImpl;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.session.security.SpringSessionBackedSessionRegistry;

/** The in-memory fallback must fail the boot rather than revoke nothing quietly. */
class SessionRegistryStartupValidatorTest {

    @Test
    void anInMemoryRegistryRefusesToStart() {
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> SessionRegistryStartupValidator.requireStoreBacked(new SessionRegistryImpl()));

        assertTrue(failure.getMessage().contains("SessionRegistryImpl"),
                "the failure must name what was found");
        assertTrue(failure.getMessage().contains("Refusing to start"));
    }

    /**
     * The predicate tests above pass even if the bean is never wired — remove {@code @Bean} or
     * {@code @Configuration} and a servlet deployment still starts with a registry that revokes
     * nothing. These drive the real context instead.
     */
    @Test
    void aServletContextWithTheInMemoryFallbackRefusesToStart() {
        new WebApplicationContextRunner()
                .withUserConfiguration(SessionRegistryStartupValidator.class, FallbackRegistry.class)
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void aNonServletContextIsExemptAndStarts() {
        new ApplicationContextRunner()
                .withUserConfiguration(SessionRegistryStartupValidator.class, FallbackRegistry.class)
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Configuration(proxyBeanMethods = false)
    static class FallbackRegistry {
        @Bean
        SessionRegistry sessionRegistry() {
            return new SessionRegistryImpl();
        }
    }

    @Test
    void aStoreBackedRegistryStarts() {
        FindByIndexNameSessionRepository<Session> repository = mock();

        assertDoesNotThrow(() -> SessionRegistryStartupValidator.requireStoreBacked(
                new SpringSessionBackedSessionRegistry<>(repository)));
    }
}
