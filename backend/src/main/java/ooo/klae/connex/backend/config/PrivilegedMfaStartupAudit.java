package ooo.klae.connex.backend.config;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.time.Clock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.beans.UnenrolledPrivilegedAccount;
import ooo.klae.connex.backend.beans.UnenrolledPrivilegedAccountCounts;
import ooo.klae.connex.backend.services.AuditService;
import ooo.klae.connex.backend.services.PasskeyBootstrapConfirmationEmailService;
import ooo.klae.connex.backend.services.PasskeyBootstrapConfirmationPolicy;
import ooo.klae.connex.backend.services.PrivilegedAccountService;

/**
 * Validates and durably records the privileged-MFA posture applied at startup.
 *
 * <p>The posture also carries a read-only inventory of privileged accounts that hold no passkey
 * (#1533), so an operator can see which accounts confinement holds at enrollment, and how many of
 * those cannot enroll on their own, before they surface as support tickets. The inventory is
 * rollout guidance, not a control: a non-empty population, or an inventory query that fails, never
 * prevents startup. The policy audit itself remains strict.
 */
@Component
@RequiredArgsConstructor
public class PrivilegedMfaStartupAudit implements ApplicationRunner {
    /** Most account ids the startup warning names; the audit carries only counts. */
    static final int INVENTORY_LOG_LIMIT = 50;

    private static final Logger log = LoggerFactory.getLogger(PrivilegedMfaStartupAudit.class);

    private final PrivilegedMfaProperties properties;
    private final PasskeyBootstrapConfirmationPolicy bootstrapConfirmationPolicy;
    private final PasskeyBootstrapConfirmationEmailService bootstrapConfirmationEmailService;
    private final PrivilegedAccountService privilegedAccountService;
    private final AuditService auditService;
    private final Clock clock;

    @Override
    public void run(ApplicationArguments args) {
        properties.validate(clock);
        boolean bootstrapConfirmationEnabled = bootstrapConfirmationPolicy.isConfirmationEnabled();
        properties.validateBootstrapConfirmation(bootstrapConfirmationEnabled);
        Map<String, Object> posture = new LinkedHashMap<>();
        posture.put("actor", properties.getChangeActor());
        posture.put("configuredValue", properties.configuredEnforcedValue());
        posture.put("enforced", properties.isEnforced());
        posture.put("bootstrapConfirmationEnabled", bootstrapConfirmationEnabled);
        recordUnenrolledInventory(posture, bootstrapConfirmationEnabled);
        auditService.recordStrictIndependentScoped(
                "auth.mfa.policy.configured",
                "security_policy",
                null,
                null,
                null,
                "privileged-mfa",
                "Privileged MFA policy configured by " + properties.getChangeActor(),
                posture);
    }

    /**
     * Adds the unenrolled-privileged counts to the posture and warns when any exist.
     *
     * <p>An account cannot enroll on its own when the emailed first-passkey confirmation applies
     * to it — confirmation is enabled and the account is password-backed — and that email cannot
     * reach it, because the account has no address or this instance cannot deliver. Such an
     * account needs operator break-glass recovery or removal of its privilege. A passwordless
     * account is outside the confirmation and enrolls through a fresh federated sign-in.
     *
     * @param posture the audit posture being assembled
     * @param bootstrapConfirmationEnabled the effective first-passkey confirmation setting
     */
    private void recordUnenrolledInventory(Map<String, Object> posture, boolean bootstrapConfirmationEnabled) {
        UnenrolledPrivilegedAccountCounts counts;
        long withoutSelfService;
        List<Integer> sampleIds;
        try {
            counts = privilegedAccountService.unenrolledPrivilegedAccountCounts();
            withoutSelfService = withoutSelfService(counts, bootstrapConfirmationEnabled,
                    bootstrapConfirmationEnabled && bootstrapConfirmationEmailService.canDeliver());
            sampleIds = counts.total() == 0
                    ? List.of()
                    : privilegedAccountService.unenrolledPrivilegedAccounts(INVENTORY_LOG_LIMIT).stream()
                            .map(UnenrolledPrivilegedAccount::id)
                            .toList();
        } catch (RuntimeException exception) {
            posture.put("unenrolledPrivilegedInventory", "unavailable");
            log.warn("Unenrolled privileged account inventory could not be computed: {}",
                    exception.getClass().getSimpleName());
            return;
        }
        posture.put("unenrolledPrivilegedCount", counts.total());
        posture.put("unenrolledWithoutSelfServiceCount", withoutSelfService);
        if (counts.total() > 0) {
            log.warn("{} privileged accounts have no passkey and {} of them cannot self-enroll; "
                    + "first {} account ids: {}",
                    counts.total(), withoutSelfService, sampleIds.size(), sampleIds);
        }
    }

    private static long withoutSelfService(
            UnenrolledPrivilegedAccountCounts counts, boolean confirmationEnabled, boolean canDeliver) {
        if (!confirmationEnabled) {
            return 0;
        }
        return canDeliver ? counts.passwordBackedWithoutEmail() : counts.passwordBacked();
    }
}
