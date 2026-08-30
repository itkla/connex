package ooo.klae.connex.backend.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
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

    @Test
    void aStoreBackedRegistryStarts() {
        FindByIndexNameSessionRepository<Session> repository = mock();

        assertDoesNotThrow(() -> SessionRegistryStartupValidator.requireStoreBacked(
                new SpringSessionBackedSessionRegistry<>(repository)));
    }
}
