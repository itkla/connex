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
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ooo.klae.connex.backend.dto.DataSubjectDisclosureDto;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.tenant.ControlPlaneRead;
import ooo.klae.connex.backend.tenant.TenantContext;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

@ExtendWith(MockitoExtension.class)
class DataSubjectDisclosureAccessTest {
    @Mock private TenantWorkScope tenantWorkScope;
    @Mock private DataSubjectDisclosureReadTransaction readTransaction;
    @Mock private DataSubjectDisclosurePlacementRead placementRead;

    private final TenantContext tenantContext = new TenantContext();
    private DataSubjectDisclosureAccess access;

    @BeforeEach
    void setUp() {
        tenantContext.clear();
        access = new DataSubjectDisclosureAccess(
            tenantWorkScope,
            tenantContext,
            readTransaction, placementRead);
    }

    @AfterEach
    void clearContext() {
        tenantContext.clear();
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
        when(tenantWorkScope.unroutedRead(any())).thenAnswer(invocation -> {
            ControlPlaneRead<?> read = invocation.getArgument(0);
            return read.read();
        });
        when(placementRead.resolve(7, 4)).thenThrow(new ResourceNotFoundException("Foreign workspace"));

        assertThrows(ResourceNotFoundException.class,
            () -> access.withSnapshot(7, 9, 4, 5, List.of(4), value -> null));
        verify(readTransaction, never()).withSnapshot(any(Integer.class), any(Integer.class), any(), any());
    }

    @Test
    void restoresAnUnresolvedCallerWhenTenantAssemblyThrows() {
        routeSnapshot();
        when(readTransaction.withSnapshot(org.mockito.ArgumentMatchers.eq(4),
                org.mockito.ArgumentMatchers.eq(5), org.mockito.ArgumentMatchers.eq(List.of(4)), any()))
            .thenThrow(new IllegalArgumentException("broken tenant row"));

        assertThrows(IllegalArgumentException.class,
            () -> access.withSnapshot(7, 9, 4, 5, List.of(4), value -> null));
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

    @Test
    void snapshotCallbackKeepsSubjectContextUntilSynchronousWorkFinishes() {
        tenantContext.set(1, 2, 3, "member", "cnx_previous");
        routeSnapshot();
        when(readTransaction.withSnapshot(org.mockito.ArgumentMatchers.eq(4),
                org.mockito.ArgumentMatchers.eq(5), org.mockito.ArgumentMatchers.eq(List.of(4)), any()))
            .thenAnswer(invocation -> {
                java.util.function.Function<DataSubjectDisclosureDto, ?> work = invocation.getArgument(3);
                return work.apply(new DataSubjectDisclosureDto());
            });
        String result = access.withSnapshot(7, 9, 4, 5, List.of(4), value -> {
            assertEquals(4, tenantContext.getWorkspaceId());
            assertEquals(7, tenantContext.getOrgId());
            assertEquals(9, tenantContext.getUserId());
            assertEquals("org_admin", tenantContext.getRole());
            assertEquals("cnx_subject", tenantContext.getCatalog());
            return "complete";
        });
        assertEquals("complete", result);
        assertEquals(1, tenantContext.getWorkspaceId());
        assertEquals(2, tenantContext.getOrgId());
        assertEquals(3, tenantContext.getUserId());
        assertEquals("member", tenantContext.getRole());
        assertEquals("cnx_previous", tenantContext.getCatalog());
    }

    private void routeSnapshot() {
        when(tenantWorkScope.unroutedRead(any())).thenAnswer(invocation -> {
            ControlPlaneRead<?> read = invocation.getArgument(0);
            return read.read();
        });
        when(placementRead.resolve(7, 4)).thenReturn(new DataSubjectDisclosurePlacementRead.Placement(7, "cnx_subject"));
        when(tenantWorkScope.withCatalog(org.mockito.ArgumentMatchers.eq("cnx_subject"), any()))
            .thenAnswer(invocation -> {
                Supplier<?> work = invocation.getArgument(1);
                return work.get();
            });
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
