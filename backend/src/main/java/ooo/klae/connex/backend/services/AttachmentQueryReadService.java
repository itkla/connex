package ooo.klae.connex.backend.services;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.ResourceTransactionManager;
import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.beans.Attachment;
import ooo.klae.connex.backend.dto.AttachmentFacets;
import ooo.klae.connex.backend.dto.PageResponse;
import ooo.klae.connex.backend.exceptions.TenantScopeSnapshotException;
import ooo.klae.connex.backend.mappers.AttachmentMapper;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

/**
 * Keeps attachment discovery, global user facts and result/count reads in one read-only REPEATABLE
 * READ transaction in single-database mode. The default-catalog control boundary uses that same
 * connection, so every SELECT observes one InnoDB read view. These GET reads take no locks; the
 * pre-lock read-view hazard in #2176 therefore does not apply. Page/count now share a snapshot.
 * Routed operations always refuse before opening or joining a transaction until #2200 supplies
 * cross-plane snapshot coordination. An existing single-database transaction must already be
 * read-only at REPEATABLE READ; REQUIRED propagation cannot upgrade either property.
 */
@Service
@RequiredArgsConstructor
public class AttachmentQueryReadService {
    private final AttachmentMapper attachmentMapper;
    private final AttachmentQueryControlAccess controlAccess;
    private final TenantWorkScope tenantWorkScope;
    private final PlatformTransactionManager transactionManager;

    /** Searches complete uploader-name membership before applying the original SQL ten-row limit. */
    public List<Attachment> search(int workspaceId, String query, int currentUserId) {
        return read(() -> {
            String facts = facts(workspaceId, currentUserId, true, false, query);
            return attachmentMapper.search(workspaceId, query, currentUserId, facts);
        });
    }

    /** Returns a page and exact total using the same user facts and single-database snapshot. */
    public PageResponse<Attachment> getPage(int workspaceId, int currentUserId, String query, String sort,
            List<String> types, List<String> kinds, List<Integer> tagIds, Boolean orphaned, int limit, int offset) {
        return read(() -> {
            String facts = facts(workspaceId, currentUserId, true, true, query);
            List<Attachment> items = attachmentMapper.getPage(workspaceId, currentUserId, query, sort,
                types, kinds, tagIds, orphaned, limit, offset, facts);
            long total = attachmentMapper.countPage(workspaceId, currentUserId, query,
                types, kinds, tagIds, orphaned, facts);
            return new PageResponse<>(items, total);
        });
    }

    /** Computes all facets in one operation; orphan classification retains global target names. */
    public AttachmentFacets facets(int workspaceId, int currentUserId) {
        return read(() -> {
            String facts = facts(workspaceId, currentUserId, false, true, null);
            return new AttachmentFacets(
                attachmentMapper.countsBySource(workspaceId, currentUserId),
                attachmentMapper.countsByKind(workspaceId, currentUserId),
                attachmentMapper.countsByTag(workspaceId, currentUserId),
                attachmentMapper.countOrphaned(workspaceId, currentUserId, facts),
                attachmentMapper.totalCount(workspaceId, currentUserId),
                attachmentMapper.totalSize(workspaceId, currentUserId));
        });
    }

    private String facts(int workspaceId, int currentUserId, boolean uploaders, boolean targets, String query) {
        return controlAccess.userFactsJson(attachmentMapper.getQueryUserIds(
            workspaceId, currentUserId, uploaders, targets), query);
    }

    private <T> T read(AttachmentQueryRead<T> read) {
        if (tenantWorkScope.isCatalogPerPlacement()) {
            throw new TenantScopeSnapshotException("Routed attachment library snapshots are unavailable until #2200");
        }
        tenantWorkScope.requireScopePreparationAllowed("attachment library operation");
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && !TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new TenantScopeSnapshotException("Attachment library requires a read-only transaction");
        }
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && existingIsolation() != Connection.TRANSACTION_REPEATABLE_READ) {
            throw new TenantScopeSnapshotException("Attachment library requires a repeatable-read snapshot");
        }
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setReadOnly(true);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        return Objects.requireNonNull(transaction.execute(status -> read.read()));
    }

    /** Verifies DEFAULT isolation on the already-bound connection without another checkout. */
    private int existingIsolation() {
        Integer isolation = TransactionSynchronizationManager.getCurrentTransactionIsolationLevel();
        if (isolation != null) {
            return isolation;
        }
        if (transactionManager instanceof ResourceTransactionManager resources
                && TransactionSynchronizationManager.getResource(resources.getResourceFactory()) instanceof ConnectionHolder holder) {
            try {
                return holder.getConnection().getTransactionIsolation();
            } catch (SQLException failure) {
                var refusal = new TenantScopeSnapshotException("Cannot verify the attachment library snapshot isolation");
                refusal.initCause(failure);
                throw refusal;
            }
        }
        throw new TenantScopeSnapshotException("Attachment library requires a known repeatable-read snapshot");
    }
}
