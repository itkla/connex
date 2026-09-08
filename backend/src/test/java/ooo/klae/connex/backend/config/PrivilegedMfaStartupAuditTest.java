package ooo.klae.connex.backend.config;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

import ooo.klae.connex.backend.services.AuditService;
import ooo.klae.connex.backend.services.PasskeyBootstrapConfirmationPolicy;

class PrivilegedMfaStartupAuditTest {
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-08-13T12:00:00Z"), ZoneOffset.UTC);

    @Test
    void startupAuditNamesActorAndEffectiveFailClosedValue() {
        PrivilegedMfaProperties properties = new PrivilegedMfaProperties();
        properties.setEnforced("malformed");
        properties.setChangeActor("change-123");
        AuditService auditService = mock(AuditService.class);
        PrivilegedMfaStartupAudit runner = new PrivilegedMfaStartupAudit(
                properties,
                confirmationPolicy(true),
                auditService,
                CLOCK);

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
                        && Boolean.TRUE.equals(posture.get("bootstrapConfirmationEnabled"))
                        && "change-123".equals(posture.get("actor"))
                        && "malformed".equals(posture.get("configuredValue"))));
    }

    @Test
    void startupAuditRecordsAnAttributedConfirmationExceptionWithEnforcementStillOn() {
        PrivilegedMfaProperties properties = new PrivilegedMfaProperties();
        properties.setChangeActor(" change-456 ");
        AuditService auditService = mock(AuditService.class);
        PrivilegedMfaStartupAudit runner = new PrivilegedMfaStartupAudit(
                properties, confirmationPolicy(false), auditService, CLOCK);

        runner.run(new DefaultApplicationArguments(new String[0]));

        verify(auditService).recordStrictIndependentScoped(
                eq("auth.mfa.policy.configured"),
                eq("security_policy"),
                isNull(),
                isNull(),
                isNull(),
                eq("privileged-mfa"),
                eq("Privileged MFA policy configured by change-456"),
                eq(Map.of("actor", "change-456", "configuredValue", "true",
                        "enforced", true, "bootstrapConfirmationEnabled", false)));
    }

    @Test
    void startupRejectsAnUnattributedConfirmationExceptionBeforeRecordingSuccess() {
        AuditService auditService = mock(AuditService.class);
        PrivilegedMfaStartupAudit runner = new PrivilegedMfaStartupAudit(
                new PrivilegedMfaProperties(), confirmationPolicy(false), auditService, CLOCK);

        assertThrows(IllegalStateException.class,
                () -> runner.run(new DefaultApplicationArguments(new String[0])));

        verifyNoInteractions(auditService);
    }

    @Test
    void failureToPersistThePolicyAuditAbortsStartup() {
        AuditService auditService = mock(AuditService.class);
        IllegalStateException persistenceFailure = new IllegalStateException("audit unavailable");
        doThrow(persistenceFailure).when(auditService).recordStrictIndependentScoped(
                eq("auth.mfa.policy.configured"), eq("security_policy"),
                isNull(), isNull(), isNull(), eq("privileged-mfa"), any(), any());
        PrivilegedMfaStartupAudit runner = new PrivilegedMfaStartupAudit(
                new PrivilegedMfaProperties(), confirmationPolicy(true), auditService, CLOCK);

        assertSame(persistenceFailure, assertThrows(IllegalStateException.class,
                () -> runner.run(new DefaultApplicationArguments(new String[0]))));
    }

    private static PasskeyBootstrapConfirmationPolicy confirmationPolicy(boolean enabled) {
        PasskeyBootstrapConfirmationPolicy policy = mock(PasskeyBootstrapConfirmationPolicy.class);
        when(policy.isConfirmationEnabled()).thenReturn(enabled);
        return policy;
    }
}
