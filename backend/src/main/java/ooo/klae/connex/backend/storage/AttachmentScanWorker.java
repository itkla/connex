package ooo.klae.connex.backend.storage;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ooo.klae.connex.backend.beans.Attachment;
import ooo.klae.connex.backend.mappers.AttachmentScanMapper;
import ooo.klae.connex.backend.services.PlacementRegistry;
import ooo.klae.connex.backend.services.SystemActor;
import ooo.klae.connex.backend.storage.malware.MalwareScanProperties;
import ooo.klae.connex.backend.storage.malware.MalwareScannerClient;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

/** Bounded, resumable scanning of retained objects with no provider I/O in transactions. */
@Component
@RequiredArgsConstructor
public class AttachmentScanWorker {
    private final AttachmentScanMapper scans;
    private final AttachmentScanTransactions transactions;
    private final ManagedObjectService managedObjects;
    private final ObjectStorage storage;
    private final ManagedObjectReadAdmissionService readAdmission;
    private final MalwareScannerClient scanner;
    private final MalwareScanProperties properties;
    private final PlacementRegistry placements;
    private final TenantWorkScope workScope;
    private final SystemActor systemActor;
    private final AtomicBoolean running = new AtomicBoolean();

    /** Gives each workspace one object per sweep; persisted due times order retries and backfill. */
    @Scheduled(fixedDelayString = "${connex.malware-scan.sweep-delay-ms:60000}",
        initialDelayString = "${connex.malware-scan.sweep-delay-ms:60000}")
    public void sweep() {
        if (!properties.isEnabled() || !running.compareAndSet(false, true)) {
            return;
        }
        try {
            int actor = workScope.unrouted(() -> systemActor.user().getId());
            for (String catalog : placements.activeCatalogs()) {
                for (int workspaceId : workScope.withCatalog(catalog,
                        () -> scans.workspaceIdsWithDueTasks(25))) {
                    try {
                        workScope.inWorkspace(workspaceId, () -> sweepWorkspace(workspaceId, actor, 1));
                    } catch (RuntimeException exception) {
                        org.slf4j.LoggerFactory.getLogger(AttachmentScanWorker.class)
                            .warn("Attachment scan workspace sweep could not complete workspace={}", workspaceId);
                    }
                }
            }
        } finally {
            running.set(false);
        }
    }

    /** Processes a bounded page; committed decisions themselves form the durable resume checkpoint. */
    public void sweepWorkspace(int workspaceId, int actorId, int limit) {
        if (limit < 1 || limit > 25) {
            throw new IllegalArgumentException("Scan batch size must be between 1 and 25");
        }
        for (int id : scans.findDue(workspaceId, limit)) {
            scan(workspaceId, id, actorId);
        }
    }

    /** Scans at most one owned object and refuses stale or duplicate completion. */
    public boolean scan(int workspaceId, int id, int actorId) {
        Attachment claimed = transactions.claim(workspaceId, id);
        if (claimed == null) {
            return false;
        }
        try {
            String key = managedObjects.managedAttachmentKey(workspaceId, claimed.getUrl())
                .orElseThrow(() -> new IllegalStateException("Invalid attachment object reference"));
            byte[] bytes;
            int maximum = Math.toIntExact(properties.getMaxScanBytes());
            try (StoredObject object = readAdmission.admit(actorId, Duration.ofSeconds(30),
                    () -> storage.get(key))) {
                if (object.contentLength() > maximum) {
                    throw new IOException("Stored attachment exceeds scan limit");
                }
                bytes = object.inputStream().readNBytes(maximum + 1);
                if (bytes.length > maximum || bytes.length != object.contentLength()) {
                    throw new IOException("Stored attachment length changed");
                }
            }
            return transactions.decide(claimed, scanner.scan(bytes));
        } catch (IOException | RuntimeException exception) {
            transactions.retry(claimed);
            return false;
        }
    }
}
