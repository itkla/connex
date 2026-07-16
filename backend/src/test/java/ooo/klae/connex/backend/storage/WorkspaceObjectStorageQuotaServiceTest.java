package ooo.klae.connex.backend.storage;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.mappers.ObjectStorageQuotaMapper;

@ExtendWith(MockitoExtension.class)
class WorkspaceObjectStorageQuotaServiceTest {
    private static final String KEY = "workspaces/7/attachments/object.pdf";

    @Mock ObjectStorageQuotaMapper quotaMapper;

    private ObjectStorageProperties properties;
    private WorkspaceObjectStorageQuotaService service;

    @BeforeEach
    void setUp() {
        properties = new ObjectStorageProperties();
        properties.setMaxWorkspaceBytes(100);
        properties.setMaxWorkspaceObjects(2);
        service = new WorkspaceObjectStorageQuotaService(quotaMapper, properties);
    }

    @Test
    void reservesBytesAndCountUnderTheLockedWorkspaceAggregate() {
        when(quotaMapper.lockQuota(7)).thenReturn(new WorkspaceObjectStorageQuota(7, 25, 1));
        when(quotaMapper.lockUsageSize(7, KEY)).thenReturn(null);
        when(quotaMapper.insertUsage(7, KEY, 50)).thenReturn(1);
        when(quotaMapper.addToQuota(7, 50)).thenReturn(1);

        service.reserve(7, KEY, 50);

        verify(quotaMapper).ensureQuota(7);
        verify(quotaMapper).insertUsage(7, KEY, 50);
        verify(quotaMapper).addToQuota(7, 50);
    }

    @Test
    void rejectsByteAndObjectCountOverrunsBeforeWritingUsage() {
        when(quotaMapper.lockQuota(7))
            .thenReturn(new WorkspaceObjectStorageQuota(7, 75, 1))
            .thenReturn(new WorkspaceObjectStorageQuota(7, 25, 2));
        when(quotaMapper.lockUsageSize(7, KEY)).thenReturn(null);

        assertThrows(BadRequestException.class, () -> service.reserve(7, KEY, 26));
        assertThrows(BadRequestException.class, () -> service.reserve(7, KEY, 1));

        verify(quotaMapper, never()).insertUsage(
            org.mockito.ArgumentMatchers.anyInt(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void rejectsAnAlreadyReservedObjectKey() {
        when(quotaMapper.lockQuota(7)).thenReturn(new WorkspaceObjectStorageQuota(7, 25, 1));
        when(quotaMapper.lockUsageSize(7, KEY)).thenReturn(25L);

        assertThrows(ConflictException.class, () -> service.reserve(7, KEY, 25));
    }

    @Test
    void validatesProjectedMigrationUsageWithoutWritingTheLedger() {
        when(quotaMapper.findQuota(7)).thenReturn(new WorkspaceObjectStorageQuota(7, 25, 1));

        service.validateProjectedAddition(7, 75, 1);

        assertThrows(BadRequestException.class,
            () -> service.validateProjectedAddition(7, 76, 1));
        assertThrows(BadRequestException.class,
            () -> service.validateProjectedAddition(7, 1, 2));
        verify(quotaMapper, never()).ensureQuota(7);
    }

    @Test
    void releasesTheExactLedgerEntryOnlyOnce() {
        when(quotaMapper.lockQuota(7)).thenReturn(new WorkspaceObjectStorageQuota(7, 75, 2));
        when(quotaMapper.lockUsageSize(7, KEY)).thenReturn(50L);
        when(quotaMapper.deleteUsage(7, KEY)).thenReturn(1);
        when(quotaMapper.subtractFromQuota(7, 50)).thenReturn(1);

        service.release(7, KEY);

        verify(quotaMapper).deleteUsage(7, KEY);
        verify(quotaMapper).subtractFromQuota(7, 50);
    }

    @Test
    void missingLedgerEntryMakesReleaseIdempotent() {
        when(quotaMapper.lockQuota(7)).thenReturn(new WorkspaceObjectStorageQuota(7, 0, 0));
        when(quotaMapper.lockUsageSize(7, KEY)).thenReturn(null);

        service.release(7, KEY);

        verify(quotaMapper, never()).deleteUsage(7, KEY);
        verify(quotaMapper, never()).subtractFromQuota(
            org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyLong());
    }
}
