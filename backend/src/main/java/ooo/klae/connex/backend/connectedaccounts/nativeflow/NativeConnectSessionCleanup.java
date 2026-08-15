package ooo.klae.connex.backend.connectedaccounts.nativeflow;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.beans.NativeConnectSession;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

/** Periodically removes native authorization sessions beyond their recovery grace. */
@Component
@RequiredArgsConstructor
public class NativeConnectSessionCleanup {
    private static final Logger log = LoggerFactory.getLogger(NativeConnectSessionCleanup.class);
    private static final Duration RETENTION_GRACE = Duration.ofDays(1);
    private static final int DELETE_BATCH = 100;

    private final NativeConnectSessionPersistence sessionPersistence;
    private final TenantWorkScope tenantWorkScope;
    private final Clock clock;

    /** Purges one bounded batch of expired sessions and their retained verifier secrets. */
    @Scheduled(
        fixedDelayString = "${connex.connected-accounts.native.cleanup-delay-ms:3600000}",
        initialDelayString = "${connex.connected-accounts.native.cleanup-initial-delay-ms:3600000}")
    public void purgeExpired() {
        LocalDateTime cutoff = LocalDateTime.ofInstant(
            clock.instant().minus(RETENTION_GRACE), ZoneOffset.UTC);
        try {
            List<NativeConnectSession> sessions = tenantWorkScope.unrouted(
                () -> sessionPersistence.findExpiredBefore(cutoff, DELETE_BATCH));
            int removed = 0;
            for (NativeConnectSession session : sessions) {
                boolean deleted = tenantWorkScope.unrouted(
                    () -> sessionPersistence.deleteExpired(
                        session.getId(), session.getUserId(), cutoff));
                if (deleted) {
                    removed += 1;
                }
            }
            if (removed > 0) {
                log.info("Purged {} expired native authorization sessions", removed);
            }
        } catch (RuntimeException exception) {
            log.error("Scheduled native authorization session purge failed", exception);
        }
    }
}
