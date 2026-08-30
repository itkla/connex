package ooo.klae.connex.backend.config;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.session.security.SpringSessionBackedSessionRegistry;

/**
 * Refuses to start a servlet instance whose session registry cannot revoke anything.
 *
 * <p>{@link SecurityConfig#sessionRegistry} falls back to an in-memory {@code SessionRegistryImpl}
 * when no shared session repository is available. Nothing calls {@code registerNewSession} on it, so
 * that registry knows about no sessions at all: every revocation would enumerate an empty set,
 * expire nothing, and report success. Password reset still locks an attacker out through the session
 * epoch, but MFA recovery has no such backstop — it revokes by enumeration alone — so under the
 * fallback it would revoke nothing while appearing to succeed.
 *
 * <p>A control that silently does nothing is worse than one that is absent, so this fails the boot
 * instead. The assertion is on the resulting registry type rather than on the repository's presence,
 * because the registry is what the revocation service actually holds.
 *
 * <p>Two structural constraints, both load-bearing:
 *
 * <ul>
 *   <li>This must stay a standalone {@code @Configuration}. Moved into {@link SecurityConfig} as a
 *       bean method, the three controller slices that import that class would fail — they are
 *       servlet contexts with no {@code DataSource}. Standalone, {@code WebMvcTypeExcludeFilter}
 *       leaves it out of the slice.</li>
 *   <li>{@link ConditionalOnWebApplication} is what exempts the non-servlet test contexts, which
 *       have no session repository by design. It is the same condition the JDBC session
 *       autoconfiguration carries, so this validator's presence tracks the repository's
 *       availability exactly.</li>
 * </ul>
 *
 * <p>A {@code SmartInitializingSingleton} rather than an {@code ApplicationRunner}: the former fires
 * before the connectors accept traffic, so a misconfigured instance never serves a request.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = Type.SERVLET)
public class SessionRegistryStartupValidator {

    /**
     * Asserts the session registry is backed by the shared store.
     *
     * @param sessionRegistry the configured registry, resolved lazily so the check runs after the
     *     whole context is built
     * @return the startup assertion
     */
    @Bean
    SmartInitializingSingleton sessionRegistryStoreBackingVerifier(
            ObjectProvider<SessionRegistry> sessionRegistry) {
        return () -> requireStoreBacked(sessionRegistry.getObject());
    }

    static void requireStoreBacked(SessionRegistry registry) {
        if (!(registry instanceof SpringSessionBackedSessionRegistry<?>)) {
            throw new IllegalStateException(
                "SessionRegistry is " + registry.getClass().getName()
                    + ", not SpringSessionBackedSessionRegistry. Revocation would enumerate an "
                    + "in-memory registry that no session is ever registered with, match nothing, "
                    + "and silently expire zero sessions; MFA recovery has no session-epoch "
                    + "backstop and would revoke nothing at all. Refusing to start.");
        }
    }
}
