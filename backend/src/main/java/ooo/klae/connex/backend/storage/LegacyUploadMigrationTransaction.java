package ooo.klae.connex.backend.storage;

import java.util.Locale;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.mappers.LegacyControlUploadMigrationMapper;
import ooo.klae.connex.backend.mappers.LegacyTenantUploadMigrationMapper;
import ooo.klae.connex.backend.storage.ImageUploadValidator.ValidatedImage;
import ooo.klae.connex.backend.storage.ManagedObjectService.StoredBinary;
import ooo.klae.connex.backend.storage.ManagedObjectService.StoredMigratedImage;
import ooo.klae.connex.backend.storage.UploadContentInspector.InspectedUpload;
import ooo.klae.connex.backend.storage.UploadPolicy.UploadPurpose;

/**
 * Validates, copies, verifies, and atomically rewrites one legacy upload reference.
 */
@Service
@RequiredArgsConstructor
public class LegacyUploadMigrationTransaction {
    private final LegacyTenantUploadMigrationMapper tenantMapper;
    private final LegacyControlUploadMigrationMapper controlMapper;
    private final ManagedObjectService managedObjectService;
    private final UploadContentInspector uploadContentInspector;
    private final ImageUploadValidator imageUploadValidator;

    /**
     * Validates a legacy attachment without mutating storage or metadata.
     *
     * @param record attachment reference
     * @param resolved bounded local source
     * @return exact byte count that apply mode would store
     */
    public long validateAttachment(LegacyUploadRecord record, ResolvedLegacyUpload resolved) {
        int workspaceId = workspaceId(record);
        InspectedUpload upload = uploadContentInspector.inspect(
            UploadPurpose.ATTACHMENT, attachmentSource(record, resolved));
        managedObjectService.validateMigratedAttachmentTarget(
            workspaceId,
            record.getId(),
            record.getUrl(),
            upload.extension(),
            upload.content());
        return upload.contentLength();
    }

    /**
     * Validates a legacy contact image without mutating storage or metadata.
     *
     * @param record contact image reference
     * @param resolved bounded local source
     * @return exact canonical byte count that apply mode would store
     */
    public long validatePersonImage(LegacyUploadRecord record, ResolvedLegacyUpload resolved) {
        int workspaceId = workspaceId(record);
        ValidatedImage image = imageUploadValidator.validate(imageSource(resolved));
        managedObjectService.validateMigratedPersonImageTarget(
            workspaceId,
            record.getId(),
            record.getUrl(),
            image.extension(),
            image.content());
        return image.content().length;
    }

    /**
     * Validates a legacy company image without mutating storage or metadata.
     *
     * @param record company image reference
     * @param resolved bounded local source
     * @return exact canonical byte count that apply mode would store
     */
    public long validateCompanyImage(LegacyUploadRecord record, ResolvedLegacyUpload resolved) {
        int workspaceId = workspaceId(record);
        ValidatedImage image = imageUploadValidator.validate(imageSource(resolved));
        managedObjectService.validateMigratedCompanyImageTarget(
            workspaceId,
            record.getId(),
            record.getUrl(),
            image.extension(),
            image.content());
        return image.content().length;
    }

    /**
     * Validates a legacy user image without mutating storage or metadata.
     *
     * @param record user image reference
     * @param resolved bounded local source
     * @return exact canonical byte count that apply mode would store
     */
    public long validateUserImage(LegacyUploadRecord record, ResolvedLegacyUpload resolved) {
        ValidatedImage image = imageUploadValidator.validate(imageSource(resolved));
        managedObjectService.validateMigratedUserImageTarget(
            record.getId(),
            record.getUrl(),
            image.extension(),
            image.content());
        return image.content().length;
    }

    /**
     * Migrates one tenant attachment and its quota reservation in one metadata transaction.
     *
     * @param record attachment reference
     * @param resolved bounded local source
     */
    @Transactional
    public void migrateAttachment(LegacyUploadRecord record, ResolvedLegacyUpload resolved) {
        int workspaceId = workspaceId(record);
        byte[] content = resolved.content();
        StoredBinary stored = managedObjectService.storeMigratedAttachment(
            workspaceId,
            record.getId(),
            record.getUrl(),
            attachmentSource(record, resolved));
        managedObjectService.verifyAttachment(workspaceId, stored.url(), content);
        int updated = tenantMapper.updateAttachment(
            workspaceId,
            record.getId(),
            record.getUrl(),
            stored.url(),
            stored.fileName(),
            stored.contentType(),
            stored.size());
        requireUpdated(updated);
    }

    /**
     * Migrates one contact image and its quota reservation in one metadata transaction.
     *
     * @param record contact image reference
     * @param resolved bounded local source
     */
    @Transactional
    public void migratePersonImage(LegacyUploadRecord record, ResolvedLegacyUpload resolved) {
        int workspaceId = workspaceId(record);
        StoredMigratedImage stored = managedObjectService.storeMigratedPersonImage(
            workspaceId,
            record.getId(),
            record.getUrl(),
            imageSource(resolved));
        managedObjectService.verifyPersonImage(
            workspaceId, record.getId(), stored.url(), stored.content());
        requireUpdated(tenantMapper.updatePersonImage(
            workspaceId, record.getId(), record.getUrl(), stored.url()));
    }

    /**
     * Migrates one company image and its quota reservation in one metadata transaction.
     *
     * @param record company image reference
     * @param resolved bounded local source
     */
    @Transactional
    public void migrateCompanyImage(LegacyUploadRecord record, ResolvedLegacyUpload resolved) {
        int workspaceId = workspaceId(record);
        StoredMigratedImage stored = managedObjectService.storeMigratedCompanyImage(
            workspaceId,
            record.getId(),
            record.getUrl(),
            imageSource(resolved));
        managedObjectService.verifyCompanyImage(
            workspaceId, record.getId(), stored.url(), stored.content());
        requireUpdated(tenantMapper.updateCompanyImage(
            workspaceId, record.getId(), record.getUrl(), stored.url()));
    }

    /**
     * Migrates one control-plane user image in one metadata transaction.
     *
     * @param record user image reference
     * @param resolved bounded local source
     */
    @Transactional
    public void migrateUserImage(LegacyUploadRecord record, ResolvedLegacyUpload resolved) {
        StoredMigratedImage stored = managedObjectService.storeMigratedUserImage(
            record.getId(), record.getUrl(), imageSource(resolved));
        managedObjectService.verifyUserImage(record.getId(), stored.url(), stored.content());
        requireUpdated(controlMapper.updateUserImage(
            record.getId(), record.getUrl(), stored.url()));
    }

    private static UploadSource attachmentSource(
            LegacyUploadRecord record,
            ResolvedLegacyUpload resolved) {
        String fileName = record.getFileName() == null
            ? resolved.fileName()
            : record.getFileName();
        return UploadSource.from(fileName, record.getContentType(), resolved.content());
    }

    private static UploadSource imageSource(ResolvedLegacyUpload resolved) {
        String fileName = resolved.fileName();
        return UploadSource.from(
            fileName, legacyImageContentType(fileName), resolved.content());
    }

    private static String legacyImageContentType(String fileName) {
        String normalized = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        if (normalized.endsWith(".jpg") || normalized.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (normalized.endsWith(".png")) {
            return "image/png";
        }
        if (normalized.endsWith(".webp")) {
            return "image/webp";
        }
        return "application/octet-stream";
    }

    private static int workspaceId(LegacyUploadRecord record) {
        Integer workspaceId = record.getWorkspaceId();
        if (workspaceId == null || workspaceId <= 0) {
            throw new IllegalStateException("Legacy upload workspace is invalid");
        }
        return workspaceId;
    }

    private static void requireUpdated(int updated) {
        if (updated != 1) {
            throw new ConflictException("Legacy upload reference changed during migration");
        }
    }
}
