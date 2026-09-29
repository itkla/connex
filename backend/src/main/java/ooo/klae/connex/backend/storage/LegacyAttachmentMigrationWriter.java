package ooo.klae.connex.backend.storage;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.mappers.AttachmentScanMapper;
import ooo.klae.connex.backend.mappers.LegacyTenantUploadMigrationMapper;
import ooo.klae.connex.backend.storage.ManagedObjectService.StoredBinary;

/** Commits verified legacy attachment bytes, metadata and their clean scan proof together. */
@Service
@RequiredArgsConstructor
public class LegacyAttachmentMigrationWriter {
    private final LegacyTenantUploadMigrationMapper tenantMapper;
    private final ManagedObjectService managedObjectService;
    private final AttachmentScanMapper scanMapper;

    /** Stores an already scanned attachment and atomically replaces its legacy reference. */
    @Transactional
    public void migrate(LegacyUploadRecord record, ScannedUpload scanned) {
        Integer workspaceId = record.getWorkspaceId();
        if (workspaceId == null || workspaceId <= 0) {
            throw new IllegalStateException("Legacy upload workspace is invalid");
        }
        Objects.requireNonNull(scanned, "scanned");
        StoredBinary stored = managedObjectService.storeMigratedAttachment(
            workspaceId, record.getId(), record.getUrl(), scanned);
        managedObjectService.verifyAttachment(
            workspaceId, stored.url(), scanned.upload().content());
        if (tenantMapper.updateAttachment(workspaceId, record.getId(), record.getUrl(),
                stored.url(), stored.fileName(), stored.contentType(), stored.size()) != 1) {
            throw new ConflictException("Legacy upload reference changed during migration");
        }
        if (scanMapper.recordLegacyClean(workspaceId, record.getId(), scanned.report(),
                LocalDateTime.ofInstant(scanned.report().validUntil(), ZoneOffset.UTC)) != 1) {
            throw new ConflictException("Legacy upload scan metadata changed during migration");
        }
    }
}
