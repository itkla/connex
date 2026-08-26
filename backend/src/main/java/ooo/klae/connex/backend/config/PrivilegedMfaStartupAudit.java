package ooo.klae.connex.backend.config;

import java.util.LinkedHashMap;
import java.util.Map;
import java.time.Clock;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.services.AuditService;

/**
 * Validates and durably records the privileged-MFA posture applied at startup.
 */
@Component
@RequiredArgsConstructor
public class PrivilegedMfaStartupAudit implements ApplicationRunner {
    private final PrivilegedMfaProperties properties;
    private final AuditService auditService;
    private final Clock clock;

    @Override
    public void run(ApplicationArguments args) {
        properties.validate(clock);
        Map<String, Object> posture = new LinkedHashMap<>();
        posture.put("actor", properties.getChangeActor());
        posture.put("configuredValue", properties.configuredEnforcedValue());
        posture.put("enforced", properties.isEnforced());
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
}
