package ooo.klae.connex.backend.services;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.dto.TenantStorageResidual;
import ooo.klae.connex.backend.mappers.TenantLifecycleMapper;
import ooo.klae.connex.backend.storage.ObjectDeletionRetryQueue;
import ooo.klae.connex.backend.tenant.TenantLifecycleRegistry;
import ooo.klae.connex.backend.tenant.TenantLifecycleRegistry.NullifyReference;
import ooo.klae.connex.backend.tenant.TenantLifecycleRegistry.TableLifecycle;

/** Bounded routed transactions for tenant-content teardown and verification. */
@Service
@RequiredArgsConstructor
public class TenantTeardownTenantTransaction {
    private final TenantLifecycleMapper mapper;
    private final ObjectDeletionRetryQueue deletionRetryQueue;

    /** Applies one registry-declared nullable reference preparation. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void prepare(
            int workspaceId,
            TableLifecycle declaration,
            NullifyReference preparation) {
        TableLifecycle registered = TenantLifecycleRegistry.requireRegistered(declaration);
        if (!registered.preparations().contains(preparation)) {
            throw new IllegalArgumentException("Lifecycle preparation is not registered");
        }
        mapper.nullifyReference(workspaceId, registered, preparation);
    }

    /** Deletes at most one configured batch from a direct table. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int deleteBatch(
            int workspaceId,
            TableLifecycle declaration,
            int limit) {
        TableLifecycle registered = TenantLifecycleRegistry.requireRegistered(declaration);
        if (!registered.direct()) {
            throw new IllegalArgumentException("Cascade lifecycle tables are not deleted directly");
        }
        return mapper.deleteDirectBatch(workspaceId, registered, limit);
    }

    /** Reads one bounded keyset page of lifecycle-owned object keys. */
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public List<String> objectKeys(
            int workspaceId,
            String afterKey,
            int limit) {
        return List.copyOf(
            mapper.findLifecycleObjectKeysAfter(workspaceId, afterKey, limit));
    }

    /** Idempotently enqueues one bounded page through the existing retry ledger. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void enqueueObjects(int workspaceId, List<String> keys) {
        for (String key : keys) {
            deletionRetryQueue.enqueueTenantInCurrentTransaction(workspaceId, key);
        }
    }

    /** Counts one registry-verified table in an isolated routed transaction. */
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public long count(int workspaceId, TableLifecycle declaration) {
        return mapper.countRows(
            workspaceId,
            TenantLifecycleRegistry.requireRegistered(declaration));
    }

    /** Returns detailed remaining object-storage metadata. */
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public TenantStorageResidual storageResidual(int workspaceId) {
        return mapper.findStorageResidual(workspaceId);
    }
}
