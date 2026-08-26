package ooo.klae.connex.backend.config;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

import ooo.klae.connex.backend.services.AuditService;

class PrivilegedMfaStartupAuditTest {

    @Test
    void startupAuditNamesActorAndEffectiveFailClosedValue() {
        PrivilegedMfaProperties properties = new PrivilegedMfaProperties();
        properties.setEnforced("malformed");
        properties.setChangeActor("change-123");
        AuditService auditService = mock(AuditService.class);
        PrivilegedMfaStartupAudit runner = new PrivilegedMfaStartupAudit(
                properties,
                auditService,
                Clock.fixed(Instant.parse("2026-08-13T12:00:00Z"), ZoneOffset.UTC));

        runner.run(new DefaultApplicationArguments(new String[0]));

        verify(auditService).recordStrictIndependentScoped(
                eq("auth.mfa.policy.configured"),
                eq("security_policy"),
                isNull(),
                isNull(),
                isNull(),
                eq("privileged-mfa"),
                eq("Privileged MFA policy configured by change-123"),
                argThat(value -> value instanceof Map<?, ?> posture
                        && Boolean.TRUE.equals(posture.get("enforced"))
                        && "change-123".equals(posture.get("actor"))
                        && "malformed".equals(posture.get("configuredValue"))));
    }
}
