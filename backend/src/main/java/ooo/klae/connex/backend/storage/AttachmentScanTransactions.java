package ooo.klae.connex.backend.storage;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.beans.Attachment;
import ooo.klae.connex.backend.mappers.AttachmentMapper;
import ooo.klae.connex.backend.mappers.AttachmentScanMapper;
import ooo.klae.connex.backend.services.AuditService;
import ooo.klae.connex.backend.storage.malware.MalwareScanReport;

/** Short exact-object claim and owner-fenced decision transactions. */
@Service
@RequiredArgsConstructor
public class AttachmentScanTransactions {
    private final AttachmentMapper attachments;
    private final AttachmentScanMapper scans;
    private final AuditService audit;

    /** Claims every reference to one object after locking its reference set. */
    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public Attachment claim(int workspaceId, int id) {
        Attachment selected = scans.getById(workspaceId, id);
        if (selected == null || selected.getUrl() == null
                || !selected.getUrl().startsWith("/api/attachments/content/")) {
            return null;
        }
        attachments.lockIdsByUrl(workspaceId, selected.getUrl());
        Attachment locked = scans.lockById(workspaceId, id);
        if (locked == null || !selected.getUrl().equals(locked.getUrl())
                || !scans.isClaimable(workspaceId, locked.getUrl())) {
            return null;
        }
        String owner = UUID.randomUUID().toString();
        scans.claim(workspaceId, locked.getUrl(), owner);
        locked.setScanOwner(owner);
        return locked;
    }

    /** Commits one verdict only while its exact claim remains live. */
    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public boolean decide(Attachment claimed, MalwareScanReport report) {
        attachments.lockIdsByUrl(claimed.getWorkspaceId(), claimed.getUrl());
        int changed = scans.decide(claimed.getWorkspaceId(), claimed.getUrl(), claimed.getScanOwner(),
            report.verdict().name().toLowerCase(java.util.Locale.ROOT), report.databaseVersion(),
            report.signature(), LocalDateTime.ofInstant(report.validUntil(), ZoneOffset.UTC));
        if (changed > 0) {
            audit.recordStrictScoped("malware.decided", "attachment", claimed.getId(),
                claimed.getWorkspaceId(), null, null, "Stored attachment security scan completed",
                java.util.Map.of("verdict", report.verdict().name()));
        }
        return changed > 0;
    }

    /** Keeps scanner failures unreadable and schedules a bounded increasing retry delay. */
    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public void retry(Attachment claimed) {
        scans.retry(claimed.getWorkspaceId(), claimed.getUrl(), claimed.getScanOwner());
    }
}
