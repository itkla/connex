package ooo.klae.connex.backend.services;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.mappers.ControlWorkspaceLifecycleMapper;
import ooo.klae.connex.backend.tenant.ControlWorkspaceLifecycleRegistry;
import ooo.klae.connex.backend.tenant.ControlWorkspaceLifecycleRegistry.TableLifecycle;

/** Bounded control-catalog transactions for workspace-data teardown and residual verification. */
@Service
@RequiredArgsConstructor
public class ControlWorkspaceLifecycleTransaction {
    private final ControlWorkspaceLifecycleMapper mapper;

    /** Deletes at most one bounded batch from a registered table. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int deleteBatch(int workspaceId, TableLifecycle declaration, int limit) {
        return mapper.deleteBatch(
            workspaceId,
            ControlWorkspaceLifecycleRegistry.requireRegistered(declaration),
            limit);
    }

    /** Counts the workspace rows remaining in one registered table. */
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public long count(int workspaceId, TableLifecycle declaration) {
        return mapper.countRows(
            workspaceId,
            ControlWorkspaceLifecycleRegistry.requireRegistered(declaration));
    }
}
