package ooo.klae.connex.backend.support;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.services.RecordVisibilityScopeControlAccess;
import ooo.klae.connex.backend.services.ShareWorkspaceControlAccess;
import ooo.klae.connex.backend.tenant.TenantCatalogResolver;
import ooo.klae.connex.backend.tenant.TenantContext;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

/** Real single-database scope and transaction boundaries for operation read-count tests. */
public final class RecordVisibilityOperationFixture implements AutoCloseable {
    public final WorkspaceMapper control = mock(WorkspaceMapper.class);
    public final TestTransactionManager transactions = new TestTransactionManager();
    public final TenantContext context = new TenantContext();
    public final RecordVisibilityScopeControlAccess scopes;

    /** Supplies an unfiltered organization ceiling for this operation's anchor. */
    public RecordVisibilityOperationFixture(int workspaceId) {
        context.clear();
        Workspace anchor = new Workspace();
        anchor.setId(workspaceId);
        anchor.setOrgId(90);
        anchor.setName("Anchor");
        Workspace sibling = new Workspace();
        sibling.setId(workspaceId + 10);
        sibling.setOrgId(90);
        sibling.setName("Sibling");
        when(control.findOrganizationWorkspacesForShare(workspaceId)).thenReturn(List.of(anchor, sibling));
        TenantWorkScope work = new TenantWorkScope(context, mock(TenantCatalogResolver.class), control);
        scopes = new RecordVisibilityScopeControlAccess(
            new ShareWorkspaceControlAccess(control, work, context, transactions), context);
    }

    @Override
    public void close() {
        context.clear();
    }
}
