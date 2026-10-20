package ooo.klae.connex.backend.tenant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class TenantControlSelectionTest {
    private final TenantContext context = new TenantContext();

    @AfterEach
    void clearContext() {
        context.clear();
    }

    @Test
    void validatedIdentityRetainsAttributionWithoutAuthorizingATenantRoute() {
        context.set(11, 3, 7, "member", "cnx_previous");
        context.swapCatalogOverride(java.util.Optional.of("cnx_override"));

        context.setControlSelection(19, 5, 7, "admin");

        assertFalse(context.isResolved());
        assertTrue(context.hasControlSelection());
        assertEquals(19, context.getWorkspaceId());
        assertEquals(5, context.getOrgId());
        assertEquals(7, context.getUserId());
        assertEquals("admin", context.getRole());
        assertNull(context.getScopeCatalog());
        assertNull(context.getCatalog());
        assertFalse(context.hasCatalogOverride());
    }

    @Test
    void ordinaryScopeInstallationReplacesTheControlSelection() {
        context.setControlSelection(19, 5, 7, "admin");

        context.set(11, 3, 7, "member", "cnx_tenant");

        assertTrue(context.isResolved());
        assertFalse(context.hasControlSelection());
        assertEquals(11, context.getWorkspaceId());
        assertEquals("cnx_tenant", context.getCatalog());
    }

    @Test
    void clearingTheThreadRemovesTheSelectionAndItsIdentity() {
        context.setControlSelection(19, 5, 7, "admin");

        context.clear();

        assertFalse(context.isResolved());
        assertFalse(context.hasControlSelection());
        assertNull(context.getWorkspaceId());
        assertNull(context.getOrgId());
        assertNull(context.getUserId());
        assertNull(context.getRole());
        assertNull(context.getCatalog());
    }
}
