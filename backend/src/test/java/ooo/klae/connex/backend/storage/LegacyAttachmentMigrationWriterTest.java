package ooo.klae.connex.backend.storage;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.mappers.AttachmentScanMapper;
import ooo.klae.connex.backend.mappers.LegacyTenantUploadMigrationMapper;
import ooo.klae.connex.backend.storage.ManagedObjectService.StoredBinary;
import ooo.klae.connex.backend.storage.UploadContentInspector.InspectedUpload;
import ooo.klae.connex.backend.storage.UploadPolicy.UploadFormat;
import ooo.klae.connex.backend.storage.malware.MalwareScanReport;
import ooo.klae.connex.backend.storage.malware.MalwareScanVerdict;

@ExtendWith(MockitoExtension.class)
class LegacyAttachmentMigrationWriterTest {
    @Mock private LegacyTenantUploadMigrationMapper tenantMapper;
    @Mock private ManagedObjectService storage;
    @Mock private AttachmentScanMapper scanMapper;
    private LegacyAttachmentMigrationWriter writer;
    private LegacyUploadRecord record;
    private ScannedUpload scanned;
    private StoredBinary stored;

    @BeforeEach
    void setUp() throws Exception {
        writer = new LegacyAttachmentMigrationWriter(tenantMapper, storage, scanMapper);
        record = new LegacyUploadRecord();
        record.setId(7);
        record.setWorkspaceId(3);
        record.setUrl("/attachments/person/old.pdf");
        byte[] bytes = {1, 2, 3};
        scanned = new ScannedUpload(new InspectedUpload("report.pdf", "application/pdf", "pdf",
            UploadFormat.PDF, bytes, MessageDigest.getInstance("SHA-256").digest(bytes)),
            new MalwareScanReport(MalwareScanVerdict.CLEAN, null, null, "test", false));
        stored = new StoredBinary("/api/attachments/content/test.pdf", "report.pdf", "application/pdf", 3);
        when(storage.storeMigratedAttachment(3, 7, record.getUrl(), scanned)).thenReturn(stored);
    }

    @Test
    void verifiesBytesAndCasBeforePersistingCleanDecision() {
        when(tenantMapper.updateAttachment(3, 7, record.getUrl(), stored.url(),
            stored.fileName(), stored.contentType(), stored.size())).thenReturn(1);
        when(scanMapper.recordLegacyClean(anyInt(), anyInt(), any(), any())).thenReturn(1);

        writer.migrate(record, scanned);

        var order = inOrder(storage, tenantMapper, scanMapper);
        order.verify(storage).storeMigratedAttachment(3, 7, record.getUrl(), scanned);
        order.verify(storage).verifyAttachment(eq(3), eq(stored.url()), any(byte[].class));
        order.verify(tenantMapper).updateAttachment(3, 7, record.getUrl(), stored.url(),
            stored.fileName(), stored.contentType(), stored.size());
        order.verify(scanMapper).recordLegacyClean(3, 7, scanned.report(),
            LocalDateTime.ofInstant(scanned.report().validUntil(), ZoneOffset.UTC));
    }

    @Test
    void staleReferenceCannotAcquireCleanDecision() {
        assertThrows(ConflictException.class, () -> writer.migrate(record, scanned));
        verifyNoInteractions(scanMapper);
    }

    @Test
    void missingDecisionRowRejectsTheMigration() {
        when(tenantMapper.updateAttachment(3, 7, record.getUrl(), stored.url(),
            stored.fileName(), stored.contentType(), stored.size())).thenReturn(1);

        assertThrows(ConflictException.class, () -> writer.migrate(record, scanned));
    }
}
