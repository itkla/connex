package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.tenant.TenantCatalogResolver;

@ExtendWith(MockitoExtension.class)
class DataSubjectDisclosurePlacementReadTest {
    @Mock private WorkspaceMapper workspaces;
    @Mock private TenantCatalogResolver catalogs;
    @InjectMocks private DataSubjectDisclosurePlacementRead read;

    @Test
    void returnsOnlyTheControlValidatedImmutableRoute() {
        when(workspaces.getOrgId(4)).thenReturn(7);
        when(catalogs.resolveCatalog(7)).thenReturn("cnx_subject");
        assertEquals(new DataSubjectDisclosurePlacementRead.Placement(7, "cnx_subject"), read.resolve(7, 4));
    }

    @Test
    void missingWorkspaceFailsBeforeResolvingPlacement() {
        when(workspaces.getOrgId(4)).thenReturn(null);
        assertThrows(IllegalStateException.class, () -> read.resolve(7, 4));
        verifyNoInteractions(catalogs);
    }

    @Test
    void foreignWorkspaceFailsBeforeResolvingPlacement() {
        when(workspaces.getOrgId(4)).thenReturn(8);
        assertThrows(ResourceNotFoundException.class, () -> read.resolve(7, 4));
        verifyNoInteractions(catalogs);
    }
}
