package ooo.klae.connex.backend.storage;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.security.MessageDigest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.mappers.LegacyControlUploadMigrationMapper;
import ooo.klae.connex.backend.mappers.LegacyTenantUploadMigrationMapper;
import ooo.klae.connex.backend.storage.ImageUploadValidator.ValidatedImage;
import ooo.klae.connex.backend.storage.ManagedObjectService.StoredBinary;
import ooo.klae.connex.backend.storage.ManagedObjectService.StoredMigratedImage;
import ooo.klae.connex.backend.storage.UploadContentInspector.InspectedUpload;
import ooo.klae.connex.backend.storage.UploadPolicy.UploadFormat;

@ExtendWith(MockitoExtension.class)
class LegacyUploadMigrationTransactionTest {
    @Mock private LegacyTenantUploadMigrationMapper tenantMapper;
    @Mock private LegacyControlUploadMigrationMapper controlMapper;
    @Mock private ManagedObjectService managedObjectService;
    @Mock private UploadContentInspector uploadContentInspector;
    @Mock private ImageUploadValidator imageUploadValidator;

    private LegacyUploadMigrationTransaction migration;

    @BeforeEach
    void setUp() {
        migration = new LegacyUploadMigrationTransaction(
            tenantMapper,
            controlMapper,
            managedObjectService,
            uploadContentInspector,
            imageUploadValidator);
    }

    @Test
    void migratesAndVerifiesAttachmentBeforeCompareAndSet() {
        LegacyUploadRecord record = record(7, 3, "/attachments/person/old.pdf");
        record.setFileName("report.pdf");
        record.setContentType("application/octet-stream");
        ResolvedLegacyUpload resolved = new ResolvedLegacyUpload("old.pdf", new byte[] {1, 2, 3});
        byte[] canonical = {9, 8, 7, 6};
        InspectedUpload upload = inspected(
            "report.pdf", "application/pdf", "pdf", UploadFormat.PDF, canonical);
        StoredBinary stored = new StoredBinary(
            "/api/attachments/content/550e8400-e29b-41d4-a716-446655440000.pdf",
            "report.pdf",
            "application/pdf",
            canonical.length);
        when(uploadContentInspector.inspectLegacyAttachment(any(UploadSource.class)))
            .thenReturn(upload);
        when(managedObjectService.storeMigratedAttachment(
                anyInt(), anyInt(), any(), any(InspectedUpload.class)))
            .thenReturn(stored);
        when(tenantMapper.updateAttachment(
                3, 7, record.getUrl(), stored.url(), stored.fileName(), stored.contentType(), canonical.length))
            .thenReturn(1);

        migration.migrateAttachment(record, resolved);

        verify(managedObjectService).verifyAttachment(
            eq(3), eq(stored.url()), aryEq(canonical));
    }

    @Test
    void compareAndSetConflictRejectsTheMetadataRewrite() {
        LegacyUploadRecord record = record(11, 4, "/contact-pictures/old.png");
        ResolvedLegacyUpload resolved = new ResolvedLegacyUpload("old.png", new byte[] {4, 5, 6});
        byte[] canonical = {10, 11, 12, 13};
        StoredMigratedImage stored = new StoredMigratedImage(
            "/api/persons/11/profile-picture/550e8400-e29b-41d4-a716-446655440000.png",
            canonical.length,
            "image/png",
            canonical);
        when(managedObjectService.storeMigratedPersonImage(
                anyInt(), anyInt(), any(), any()))
            .thenReturn(stored);

        assertThrows(ConflictException.class,
            () -> migration.migratePersonImage(record, resolved));

        verify(managedObjectService).verifyPersonImage(
            eq(4), eq(11), eq(stored.url()), aryEq(canonical));
    }

    @Test
    void userImageUsesTheDeterministicManagedTarget() {
        LegacyUploadRecord record = record(13, null, "/profile-pictures/old.jpg");
        ResolvedLegacyUpload resolved = new ResolvedLegacyUpload("old.jpg", new byte[] {7, 8, 9});
        byte[] canonical = {14, 15, 16, 17};
        StoredMigratedImage stored = new StoredMigratedImage(
            "/api/users/13/profile-picture/550e8400-e29b-41d4-a716-446655440000.jpg",
            canonical.length,
            "image/jpeg",
            canonical);
        when(managedObjectService.storeMigratedUserImage(anyInt(), any(), any()))
            .thenReturn(stored);
        when(controlMapper.updateUserImage(13, record.getUrl(), stored.url())).thenReturn(1);

        migration.migrateUserImage(record, resolved);

        verify(managedObjectService).verifyUserImage(
            eq(13), eq(stored.url()), aryEq(canonical));
    }

    @Test
    void dryRunComparesExistingImageTargetsWithCanonicalBytes() {
        LegacyUploadRecord record = record(13, null, "/profile-pictures/old.png");
        ResolvedLegacyUpload resolved = new ResolvedLegacyUpload(
            "old.png", new byte[] {7, 8, 9});
        byte[] canonical = {14, 15, 16, 17};
        when(imageUploadValidator.validate(any(UploadSource.class)))
            .thenReturn(new ValidatedImage(canonical, "image/png", "png"));

        migration.validateUserImage(record, resolved);

        verify(managedObjectService).validateMigratedUserImageTarget(
            eq(13), eq(record.getUrl()), eq("png"), aryEq(canonical));
    }

    private static LegacyUploadRecord record(int id, Integer workspaceId, String url) {
        LegacyUploadRecord record = new LegacyUploadRecord();
        record.setId(id);
        record.setWorkspaceId(workspaceId);
        record.setUrl(url);
        return record;
    }

    private static InspectedUpload inspected(
            String fileName,
            String contentType,
            String extension,
            UploadFormat format,
            byte[] content) {
        try {
            return new InspectedUpload(
                fileName,
                contentType,
                extension,
                format,
                content,
                MessageDigest.getInstance("SHA-256").digest(content));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
