package ooo.klae.connex.backend.services;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.mappers.RecordSearchMapper;
import ooo.klae.connex.backend.mappers.RecordSearchMapper.Kind;
import ooo.klae.connex.backend.mappers.RecordSearchMapper.Row;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.tenant.TenantWorkScope;
import ooo.klae.connex.backend.util.SearchNormalizer;

/** Repairs search projections continuously, routing before each independently committed page. */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "connex.record-search", name = "scheduling-enabled",
    havingValue = "true", matchIfMissing = true)
public class RecordSearchReconciler {
    private static final Logger log = LoggerFactory.getLogger(RecordSearchReconciler.class);
    private static final int PAGE_SIZE = 500;
    private static final int WORKSPACES_PER_SWEEP = 25;

    private final WorkspaceMapper workspaceMapper;
    private final TenantWorkScope workScope;
    private final RecordSearchMapper recordSearchMapper;
    private final PlatformTransactionManager transactionManager;
    private final Map<Scope, Cursor> cursors = new HashMap<>();
    private int afterWorkspaceId;
    private int throughWorkspaceId;

    private record Scope(int workspaceId, Kind kind) { }
    private record Cursor(int afterId, int throughId) { }

    /** Processes at most one 500-row page per table in each of 25 active workspaces. */
    @Scheduled(fixedDelayString = "${connex.record-search.reconciliation-delay-ms:60000}",
        initialDelayString = "${connex.record-search.initial-delay-ms:60000}")
    public synchronized void sweep() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Record search reconciliation requires no ambient transaction");
        }
        if (throughWorkspaceId == 0) {
            Integer lastId = workScope.unroutedRead(workspaceMapper::findLastWorkspaceId);
            if (lastId == null) {
                return;
            }
            throughWorkspaceId = lastId;
        }
        List<Integer> workspaceIds = workScope.unroutedRead(
            () -> workspaceMapper.findWorkspaceIdsThroughPage(afterWorkspaceId, throughWorkspaceId, WORKSPACES_PER_SWEEP));
        if (workspaceIds.isEmpty()) {
            afterWorkspaceId = 0;
            throughWorkspaceId = 0;
            return;
        }
        for (int workspaceId : workspaceIds) {
            try {
                workScope.inWorkspace(workspaceId, () -> {
                    for (Kind kind : Kind.values()) {
                        try {
                            reconcilePage(new Scope(workspaceId, kind));
                        } catch (RuntimeException exception) {
                            log.warn("Record search page failed workspace={} kind={} exceptionClass={}",
                                workspaceId, kind, exception.getClass().getSimpleName());
                        }
                    }
                });
            } catch (RuntimeException exception) {
                log.warn("Record search reconciliation failed workspace={} exceptionClass={}",
                    workspaceId, exception.getClass().getSimpleName());
            }
            afterWorkspaceId = workspaceId;
        }
        if (workspaceIds.size() < WORKSPACES_PER_SWEEP || afterWorkspaceId == throughWorkspaceId) {
            afterWorkspaceId = 0;
            throughWorkspaceId = 0;
        }
    }

    private void reconcilePage(Scope scope) {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        Cursor next = transaction.execute(status -> {
            Cursor cursor = cursors.get(scope);
            if (cursor == null) {
                Integer maxId = recordSearchMapper.maxId(scope.workspaceId(), scope.kind());
                if (maxId == null) {
                    return null;
                }
                cursor = new Cursor(0, maxId);
            }
            List<Integer> pageIds = recordSearchMapper.pageIds(scope.workspaceId(), scope.kind(),
                cursor.afterId(), cursor.throughId(), PAGE_SIZE);
            if (pageIds.isEmpty()) {
                return null;
            }
            int pageEnd = pageIds.getLast();
            List<Row> rows = recordSearchMapper.findMissing(scope.workspaceId(), scope.kind(),
                cursor.afterId(), pageEnd, PAGE_SIZE);
            for (Row row : rows) {
                recordSearchMapper.reconcile(scope.workspaceId(), scope.kind(), row,
                    SearchNormalizer.name(row.name()), SearchNormalizer.phone(row.phone()));
            }
            return pageIds.size() < PAGE_SIZE || pageEnd == cursor.throughId() ? null
                : new Cursor(pageEnd, cursor.throughId());
        });
        if (next == null) {
            cursors.remove(scope);
        } else {
            cursors.put(scope, next);
        }
    }
}
