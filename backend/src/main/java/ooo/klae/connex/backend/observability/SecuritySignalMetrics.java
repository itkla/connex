package ooo.klae.connex.backend.observability;

import java.time.Clock;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.beans.AuditLog;

/** Exposes closed, content-free security signals through the existing operator metrics endpoint. */
@Component
@RequiredArgsConstructor
public class SecuritySignalMetrics {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(SecuritySignalMetrics.class);
    private static final Set<String> ROLE_ACTIONS = Set.of(
            "workspace.role.create", "workspace.role.update", "workspace.role.delete",
            "workspace.member.role", "org.member.set", "org.member.founding_owner");
    private final MeterRegistry registry;
    private final Clock clock;
    private final ConcurrentHashMap<String, AtomicLong> timestamps = new ConcurrentHashMap<>();

    /** Records audited failures immediately and successful role mutations only after commit. */
    public void observeAudit(AuditLog entry, boolean independent) {
        observeSafely(() -> observeAuditWithinBoundary(entry, independent));
    }

    private void observeAuditWithinBoundary(AuditLog entry, boolean independent) {
        String scope = scope(entry);
        if (entry.getAction() != null && entry.getAction().startsWith("auth.login")
                && "failure".equals(entry.getOutcome())) {
            registry.counter("connex.security.authentication.failures", "scope", scope).increment();
        }
        if (!ROLE_ACTIONS.contains(entry.getAction() == null ? "" : entry.getAction())
                || !"success".equals(entry.getOutcome())) {
            return;
        }
        Runnable record = () -> observeSafely(() -> timestamp("permission.change", scope));
        if (!independent && TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    record.run();
                }
            });
        } else {
            record.run();
        }
    }

    /** Records an observed invalid or unverifiable stored HMAC without exporting row content. */
    public void integrityAnomaly(AuditLog entry) {
        observeSafely(() -> timestamp("audit.integrity.anomaly", scope(entry)));
    }

    /** Keeps recoverable telemetry failures out of committed mutations and audit disclosure. */
    private void observeSafely(Runnable observation) {
        try {
            observation.run();
        } catch (RuntimeException exception) {
            log.warn("Security signal observation failed");
        }
    }

    private void timestamp(String signal, String scope) {
        timestamps.computeIfAbsent(signal + ":" + scope, key -> {
            AtomicLong value = new AtomicLong();
            Gauge.builder("connex.security." + signal + ".timestamp", value, AtomicLong::doubleValue)
                    .tag("scope", scope).register(registry);
            return value;
        }).set(clock.instant().getEpochSecond());
    }

    private static String scope(AuditLog entry) {
        if (entry.getWorkspaceId() != null) {
            return "workspace:" + entry.getWorkspaceId();
        }
        if (entry.getOrgId() != null) {
            return "organization:" + entry.getOrgId();
        }
        return "unattributed";
    }
}
