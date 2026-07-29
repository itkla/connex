package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.function.BiFunction;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ooo.klae.connex.backend.dto.DataSubjectDisclosureDto;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.tenant.TenantContext;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

@ExtendWith(MockitoExtension.class)
class DataSubjectDisclosureAccessTest {
    @Mock private TenantWorkScope tenantWorkScope;
    @Mock private DataSubjectDisclosureReadTransaction readTransaction;

    private final TenantContext tenantContext = new TenantContext();
    private DataSubjectDisclosureAccess access;

    @BeforeEach
    void setUp() {
        access = new DataSubjectDisclosureAccess(
            tenantWorkScope,
            tenantContext,
            readTransaction);
    }

    @Test
    void installsTheSubjectScopeBeforeTheReadAndRestoresTheCaller() {
        tenantContext.set(1, 2, 3, "member", "cnx_previous");
        routeWorkspace(4, 7, "cnx_subject");
        when(readTransaction.subjectPersonExists(4, 5)).thenAnswer(invocation -> {
            assertEquals(4, tenantContext.getWorkspaceId());
            assertEquals(7, tenantContext.getOrgId());
            assertEquals(9, tenantContext.getUserId());
            assertEquals("org_admin", tenantContext.getRole());
            assertEquals("cnx_subject", tenantContext.getCatalog());
            return true;
        });

        assertTrue(access.subjectPersonExists(7, 9, 4, 5));

        assertEquals(1, tenantContext.getWorkspaceId());
        assertEquals(2, tenantContext.getOrgId());
        assertEquals(3, tenantContext.getUserId());
        assertEquals("member", tenantContext.getRole());
        assertEquals("cnx_previous", tenantContext.getCatalog());
    }

    @Test
    void rejectsAPlacementWhoseOrganizationDoesNotMatchTheControlLink() {
        routeWorkspace(4, 8, "cnx_foreign");

        assertThrows(ResourceNotFoundException.class,
            () -> access.assemble(7, 9, 4, 5, List.of(4)));
        verify(readTransaction, never()).assemble(any(Integer.class), any(Integer.class), any());
    }

    @Test
    void restoresAnUnresolvedCallerWhenTenantAssemblyThrows() {
        routeWorkspace(4, 7, "cnx_subject");
        when(readTransaction.assemble(4, 5, List.of(4)))
            .thenThrow(new IllegalArgumentException("broken tenant row"));

        assertThrows(IllegalArgumentException.class,
            () -> access.assemble(7, 9, 4, 5, List.of(4)));
        assertTrue(!tenantContext.isResolved());
    }

    @Test
    void initiallyMissingPersonIsABadRequest() {
        routeWorkspace(4, 7, "cnx_subject");
        when(readTransaction.withLockedSubjectPerson(
                org.mockito.ArgumentMatchers.eq(4),
                org.mockito.ArgumentMatchers.eq(5),
                any(),
                any()))
            .thenThrow(new BadRequestException("Subject person is missing"));

        assertThrows(
            BadRequestException.class,
            () -> access.withLockedSubjectPerson(
                7,
                9,
                4,
                5,
                work -> work.get(),
                () -> "unused"));
    }

    @Test
    void routeLossAfterPreliminaryProofIsAConflict() {
        when(tenantWorkScope.withWorkspacePlacement(
                org.mockito.ArgumentMatchers.eq(4),
                any()))
            .thenThrow(new IllegalStateException("Workspace 4 does not exist"));

        assertThrows(
            ConflictException.class,
            () -> access.withLockedSubjectPerson(
                7,
                9,
                4,
                5,
                work -> work.get(),
                () -> "unused"));
        verify(readTransaction, never()).withLockedSubjectPerson(
            any(Integer.class),
            any(Integer.class),
            any(),
            any());
    }

    @Test
    void routeOrganizationChangeAfterPreliminaryProofIsAConflict() {
        routeWorkspace(4, 8, "cnx_foreign");

        assertThrows(
            ConflictException.class,
            () -> access.withLockedSubjectPerson(
                7,
                9,
                4,
                5,
                work -> work.get(),
                () -> "unused"));
        verify(readTransaction, never()).withLockedSubjectPerson(
            any(Integer.class),
            any(Integer.class),
            any(),
            any());
    }

    private void routeWorkspace(int workspaceId, int orgId, String catalog) {
        when(tenantWorkScope.withWorkspacePlacement(
                org.mockito.ArgumentMatchers.eq(workspaceId), any()))
            .thenAnswer(invocation -> {
                BiFunction<Integer, String, ?> work = invocation.getArgument(1);
                return work.apply(orgId, catalog);
            });
    }
}
