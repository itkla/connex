package ooo.klae.connex.backend.integration;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.test.context.TestPropertySource;

/**
 * Stages privileged-MFA rollout for integration tests whose existing subject is an unenrolled
 * privileged fixture rather than MFA enforcement.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@TestPropertySource(properties = {
    "connex.security.privileged-mfa.enforced=false",
    "connex.security.privileged-mfa.change-actor=unenrolled-privileged-integration-fixture"
})
public @interface UnenrolledPrivilegedFixture {
}
