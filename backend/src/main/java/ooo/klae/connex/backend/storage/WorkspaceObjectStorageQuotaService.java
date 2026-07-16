package ooo.klae.connex.backend.storage;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.mappers.ObjectStorageQuotaMapper;

/**
 * Serializes workspace object admission and exact quota release.
 */
@Service
@RequiredArgsConstructor
public class WorkspaceObjectStorageQuotaService {
    private final ObjectStorageQuotaMapper quotaMapper;
    private final ObjectStorageProperties properties;

    /**
     * Checks a maintenance migration's projected additions without changing the quota ledger.
     *
     * @param workspaceId target workspace
     * @param additionalBytes bytes that would be added
     * @param additionalObjects objects that would be added
     */
    public void validateProjectedAddition(
            int workspaceId,
            long additionalBytes,
            int additionalObjects) {
        if (workspaceId <= 0 || additionalBytes < 0 || additionalObjects < 0) {
            throw new BadRequestException("Managed object quota projection is invalid");
        }
        WorkspaceObjectStorageQuota quota = quotaMapper.findQuota(workspaceId);
        long usedBytes = quota == null ? 0 : quota.usedBytes();
        int objectCount = quota == null ? 0 : quota.objectCount();
        if (additionalObjects > properties.getMaxWorkspaceObjects() - objectCount
                || additionalBytes > properties.getMaxWorkspaceBytes() - usedBytes) {
            throw new BadRequestException(
                "The workspace private-storage quota would be exceeded by legacy uploads");
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void reserve(int workspaceId, String objectKey, long sizeBytes) {
        String validKey = ObjectStorageKey.requireValid(objectKey);
        if (workspaceId <= 0 || sizeBytes <= 0) {
            throw new BadRequestException("Managed object quota reservation is invalid");
        }
        quotaMapper.ensureQuota(workspaceId);
        WorkspaceObjectStorageQuota quota = quotaMapper.lockQuota(workspaceId);
        if (quota == null) {
            throw new IllegalStateException("Workspace object-storage quota row was not created");
        }
        if (quotaMapper.lockUsageSize(workspaceId, validKey) != null) {
            throw new ConflictException("Managed object key is already reserved");
        }
        if (quota.objectCount() >= properties.getMaxWorkspaceObjects()
                || sizeBytes > properties.getMaxWorkspaceBytes() - quota.usedBytes()) {
            throw new BadRequestException("The workspace private-storage quota has been reached");
        }
        if (quotaMapper.insertUsage(workspaceId, validKey, sizeBytes) != 1
                || quotaMapper.addToQuota(workspaceId, sizeBytes) != 1) {
            throw new IllegalStateException("Workspace object-storage quota could not be reserved");
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void release(int workspaceId, String objectKey) {
        String validKey = ObjectStorageKey.requireValid(objectKey);
        quotaMapper.ensureQuota(workspaceId);
        WorkspaceObjectStorageQuota quota = quotaMapper.lockQuota(workspaceId);
        if (quota == null) {
            throw new IllegalStateException("Workspace object-storage quota row is unavailable");
        }
        Long sizeBytes = quotaMapper.lockUsageSize(workspaceId, validKey);
        if (sizeBytes == null) {
            return;
        }
        if (quota.usedBytes() < sizeBytes || quota.objectCount() <= 0
                || quotaMapper.deleteUsage(workspaceId, validKey) != 1
                || quotaMapper.subtractFromQuota(workspaceId, sizeBytes) != 1) {
            throw new IllegalStateException("Workspace object-storage quota ledger is inconsistent");
        }
    }
}
