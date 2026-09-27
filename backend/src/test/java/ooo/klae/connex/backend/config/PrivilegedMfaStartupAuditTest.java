package ooo.klae.connex.backend.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.dao.QueryTimeoutException;

import ooo.klae.connex.backend.beans.UnenrolledPrivilegedAccount;
import ooo.klae.connex.backend.beans.UnenrolledPrivilegedAccountCounts;
import ooo.klae.connex.backend.services.AuditService;
import ooo.klae.connex.backend.services.PasskeyBootstrapConfirmationEmailService;
import ooo.klae.connex.backend.services.PasskeyBootstrapConfirmationPolicy;
import ooo.klae.connex.backend.services.PrivilegedAccountService;

class PrivilegedMfaStartupAuditTest {
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-08-13T12:00:00Z"), ZoneOffset.UTC);
    private static final DefaultApplicationArguments NO_ARGUMENTS =
            new DefaultApplicationArguments(new String[0]);

    @Test
    void startupAuditNamesActorAndEffectiveFailClosedValue() {
        PrivilegedMfaProperties properties = new PrivilegedMfaProperties();
        properties.setEnforced("malformed");
        properties.setChangeActor("change-123");
        AuditService auditService = mock(AuditService.class);
        PrivilegedMfaStartupAudit runner = new PrivilegedMfaStartupAudit(
                properties,
                confirmationPolicy(true),
                deliverable(true),
                inventory(new UnenrolledPrivilegedAccountCounts(0, 0, 0)),
                auditService,
                CLOCK);

        start(runner);

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
                properties, confirmationPolicy(false), deliverable(false),
                inventory(new UnenrolledPrivilegedAccountCounts(0, 0, 0)), auditService, CLOCK);

        start(runner);

        verify(auditService).recordStrictIndependentScoped(
                eq("auth.mfa.policy.configured"),
                eq("security_policy"),
                isNull(),
                isNull(),
                isNull(),
                eq("privileged-mfa"),
                eq("Privileged MFA policy configured by change-456"),
                eq(Map.of("actor", "change-456", "configuredValue", "true",
                        "enforced", true, "bootstrapConfirmationEnabled", false,
                        "unenrolledPrivilegedCount", 0L, "unenrolledWithoutSelfServiceCount", 0L)));
    }

    @Test
    void startupRejectsAnUnattributedConfirmationExceptionBeforeRecordingSuccess() {
        AuditService auditService = mock(AuditService.class);
        PrivilegedMfaStartupAudit runner = new PrivilegedMfaStartupAudit(
                new PrivilegedMfaProperties(), confirmationPolicy(false), deliverable(false),
                inventory(new UnenrolledPrivilegedAccountCounts(0, 0, 0)), auditService, CLOCK);

        assertThrows(IllegalStateException.class, () -> runner.run(NO_ARGUMENTS));

        verifyNoInteractions(auditService);
    }

    /**
     * The runner phase precedes bootstrap owner provisioning, so it only validates: it neither
     * takes the inventory nor records the posture.
     */
    @Test
    void theRunnerPhaseValidatesWithoutTakingTheInventoryOrRecordingThePosture() {
        AuditService auditService = mock(AuditService.class);
        PrivilegedAccountService privilegedAccounts = inventory(new UnenrolledPrivilegedAccountCounts(1, 1, 0));
        PrivilegedMfaStartupAudit runner = new PrivilegedMfaStartupAudit(
                new PrivilegedMfaProperties(), confirmationPolicy(true), deliverable(true),
                privilegedAccounts, auditService, CLOCK);

        runner.run(NO_ARGUMENTS);

        verifyNoInteractions(auditService);
        verify(privilegedAccounts, never()).unenrolledPrivilegedAccountCounts();
    }

    @Test
    void failureToPersistThePolicyAuditAbortsStartup() {
        AuditService auditService = mock(AuditService.class);
        IllegalStateException persistenceFailure = new IllegalStateException("audit unavailable");
        doThrow(persistenceFailure).when(auditService).recordStrictIndependentScoped(
                eq("auth.mfa.policy.configured"), eq("security_policy"),
                isNull(), isNull(), isNull(), eq("privileged-mfa"), any(), any());
        PrivilegedMfaStartupAudit runner = new PrivilegedMfaStartupAudit(
                new PrivilegedMfaProperties(), confirmationPolicy(true), deliverable(true),
                inventory(new UnenrolledPrivilegedAccountCounts(0, 0, 0)), auditService, CLOCK);

        runner.run(NO_ARGUMENTS);

        assertSame(persistenceFailure, assertThrows(IllegalStateException.class, runner::recordPosture));
    }

    /**
     * A non-empty unenrolled population is rollout guidance: startup continues and the strict
     * policy event carries both counts. With delivery available, only the password-backed accounts
     * that have no address to confirm through are counted as unable to self-enroll.
     */
    @Test
    void anUnenrolledPrivilegedPopulationIsRecordedWithoutFailingStartup() {
        AuditService auditService = mock(AuditService.class);
        PrivilegedAccountService privilegedAccounts = inventory(new UnenrolledPrivilegedAccountCounts(5, 3, 1));
        when(privilegedAccounts.unenrolledPrivilegedAccounts(PrivilegedMfaStartupAudit.INVENTORY_LOG_LIMIT))
                .thenReturn(List.of(
                        new UnenrolledPrivilegedAccount(11, true, true),
                        new UnenrolledPrivilegedAccount(12, false, true)));
        PrivilegedMfaStartupAudit runner = new PrivilegedMfaStartupAudit(
                new PrivilegedMfaProperties(), confirmationPolicy(true), deliverable(true),
                privilegedAccounts, auditService, CLOCK);

        assertDoesNotThrow(() -> start(runner));

        verify(auditService).recordStrictIndependentScoped(
                eq("auth.mfa.policy.configured"), eq("security_policy"),
                isNull(), isNull(), isNull(), eq("privileged-mfa"), any(),
                argThat(value -> value instanceof Map<?, ?> posture
                        && Long.valueOf(5).equals(posture.get("unenrolledPrivilegedCount"))
                        && Long.valueOf(1).equals(posture.get("unenrolledWithoutSelfServiceCount"))));
        verify(privilegedAccounts).unenrolledPrivilegedAccounts(50);
    }

    /**
     * Without a deliverable confirmation, every password-backed unenrolled account is stuck;
     * passwordless accounts still enroll through a fresh federated sign-in.
     */
    @Test
    void undeliverableConfirmationCountsEveryPasswordBackedAccountAsUnableToSelfEnroll() {
        Map<?, ?> posture = recordedPosture(true, false, new UnenrolledPrivilegedAccountCounts(5, 3, 1));

        assertEquals(5L, posture.get("unenrolledPrivilegedCount"));
        assertEquals(3L, posture.get("unenrolledWithoutSelfServiceCount"));
    }

    /** A disabled confirmation leaves the password alone sufficient, so no account is stuck. */
    @Test
    void disabledConfirmationCountsNoAccountAsUnableToSelfEnroll() {
        PrivilegedMfaProperties properties = new PrivilegedMfaProperties();
        properties.setChangeActor("change-789");
        Map<?, ?> posture = recordedPosture(
                properties, false, false, new UnenrolledPrivilegedAccountCounts(5, 3, 1));

        assertEquals(5L, posture.get("unenrolledPrivilegedCount"));
        assertEquals(0L, posture.get("unenrolledWithoutSelfServiceCount"));
    }

    /** A failing inventory is recorded as unavailable and never blocks the strict policy event. */
    @Test
    void aFailingInventoryIsRecordedAsUnavailableWithoutFailingStartup() {
        AuditService auditService = mock(AuditService.class);
        PrivilegedAccountService privilegedAccounts = mock(PrivilegedAccountService.class);
        when(privilegedAccounts.unenrolledPrivilegedAccountCounts())
                .thenThrow(new QueryTimeoutException("inventory timed out"));
        PrivilegedMfaStartupAudit runner = new PrivilegedMfaStartupAudit(
                new PrivilegedMfaProperties(), confirmationPolicy(true), deliverable(true),
                privilegedAccounts, auditService, CLOCK);

        assertDoesNotThrow(() -> start(runner));

        verify(auditService).recordStrictIndependentScoped(
                eq("auth.mfa.policy.configured"), eq("security_policy"),
                isNull(), isNull(), isNull(), eq("privileged-mfa"), any(),
                argThat(value -> value instanceof Map<?, ?> posture
                        && "unavailable".equals(posture.get("unenrolledPrivilegedInventory"))
                        && !posture.containsKey("unenrolledPrivilegedCount")));
    }

    private static Map<?, ?> recordedPosture(
            boolean confirmationEnabled, boolean canDeliver, UnenrolledPrivilegedAccountCounts counts) {
        return recordedPosture(new PrivilegedMfaProperties(), confirmationEnabled, canDeliver, counts);
    }

    private static Map<?, ?> recordedPosture(PrivilegedMfaProperties properties,
            boolean confirmationEnabled, boolean canDeliver, UnenrolledPrivilegedAccountCounts counts) {
        AuditService auditService = mock(AuditService.class);
        start(new PrivilegedMfaStartupAudit(properties, confirmationPolicy(confirmationEnabled),
                deliverable(canDeliver), inventory(counts), auditService, CLOCK));
        ArgumentCaptor<Object> posture = ArgumentCaptor.forClass(Object.class);
        verify(auditService).recordStrictIndependentScoped(
                eq("auth.mfa.policy.configured"), eq("security_policy"),
                isNull(), isNull(), isNull(), eq("privileged-mfa"), any(), posture.capture());
        return assertInstanceOf(Map.class, posture.getValue());
    }

    /** Drives both startup phases in Spring Boot's order: runners first, then the ready listeners. */
    private static void start(PrivilegedMfaStartupAudit audit) {
        audit.run(NO_ARGUMENTS);
        audit.recordPosture();
    }

    private static PasskeyBootstrapConfirmationPolicy confirmationPolicy(boolean enabled) {
        PasskeyBootstrapConfirmationPolicy policy = mock(PasskeyBootstrapConfirmationPolicy.class);
        when(policy.isConfirmationEnabled()).thenReturn(enabled);
        return policy;
    }

    private static PasskeyBootstrapConfirmationEmailService deliverable(boolean canDeliver) {
        PasskeyBootstrapConfirmationEmailService emailService =
                mock(PasskeyBootstrapConfirmationEmailService.class);
        when(emailService.canDeliver()).thenReturn(canDeliver);
        return emailService;
    }

    private static PrivilegedAccountService inventory(UnenrolledPrivilegedAccountCounts counts) {
        PrivilegedAccountService privilegedAccounts = mock(PrivilegedAccountService.class);
        when(privilegedAccounts.unenrolledPrivilegedAccountCounts()).thenReturn(counts);
        return privilegedAccounts;
    }
}
