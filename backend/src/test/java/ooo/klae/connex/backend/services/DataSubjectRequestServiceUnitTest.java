package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ooo.klae.connex.backend.beans.DataSubjectRequest;
import ooo.klae.connex.backend.dto.DataSubjectDisclosureDto;
import ooo.klae.connex.backend.dto.DataSubjectDisclosureDto.AuditEntryDto;
import ooo.klae.connex.backend.dto.DataSubjectDisclosureDto.ThirdPartyProvisionDto;
import ooo.klae.connex.backend.dto.DataSubjectRequestDto;
import ooo.klae.connex.backend.dto.DataSubjectRequestUpsertRequest;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.exceptions.ServiceUnavailableException;
import ooo.klae.connex.backend.services.DataSubjectRequestControlOperations.DisclosureControlData;
import ooo.klae.connex.backend.services.DataSubjectRequestControlOperations.WorkspaceSnapshot;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

@ExtendWith(MockitoExtension.class)
class DataSubjectRequestServiceUnitTest {
    @Mock private DataSubjectRequestControlOperations controlOperations;
    @Mock private DataSubjectDisclosureAccess disclosureAccess;
    @Mock private TenantWorkScope tenantWorkScope;

    private DataSubjectRequestService service;

    @BeforeEach
    void setUp() {
        when(tenantWorkScope.unrouted(any())).thenAnswer(invocation -> {
            Supplier<?> work = invocation.getArgument(0);
            return work.get();
        });
        lenient().when(disclosureAccess.withLockedSubjectPerson(
                anyInt(),
                anyInt(),
                anyInt(),
                anyInt(),
                any(),
                any()))
            .thenAnswer(invocation -> {
                Function<Supplier<Object>, Object> controlTransaction =
                    invocation.getArgument(4);
                Supplier<Object> work = invocation.getArgument(5);
                return controlTransaction.apply(work);
            });
        lenient().when(controlOperations.withLockedSubjectRoots(
                anyInt(),
                anyInt(),
                any(),
                any()))
            .thenAnswer(invocation -> {
                Supplier<?> work = invocation.getArgument(3);
                return work.get();
            });
        service = new DataSubjectRequestService(controlOperations, disclosureAccess, tenantWorkScope);
    }

    @Test
    void createAuthorizesBeforeDelegatingAValidatedControlWrite() {
        when(controlOperations.create(any(Integer.class), any(Integer.class), any(DataSubjectRequest.class)))
            .thenAnswer(invocation -> {
                DataSubjectRequest request = invocation.getArgument(2);
                request.setId(9);
                return DataSubjectRequestDto.from(request);
            });

        DataSubjectRequestDto created = service.create(3, 7, request("disclosure"));

        assertEquals(9, created.getId());
        assertEquals("received", created.getStatus());
        InOrder order = inOrder(controlOperations);
        order.verify(controlOperations).requireMutationAccess(3, 7);
        order.verify(controlOperations).create(any(Integer.class), any(Integer.class), any(DataSubjectRequest.class));
        verify(disclosureAccess, never()).withLockedSubjectPerson(
            anyInt(), anyInt(), anyInt(), anyInt(), any(), any());
    }

    @Test
    void subjectLinkValidationRejectsInvalidInitialTargetsBeforeTheControlWrite() {
        DataSubjectRequestUpsertRequest oneSided = request("disclosure");
        oneSided.setSubjectWorkspaceId(4);
        assertThrows(BadRequestException.class, () -> service.create(3, 7, oneSided));

        DataSubjectRequestUpsertRequest linked = request("disclosure");
        linked.setSubjectWorkspaceId(4);
        linked.setSubjectPersonId(5);
        when(controlOperations.workspaceBelongsToOrg(3, 4)).thenReturn(false, true, true);
        assertThrows(BadRequestException.class, () -> service.create(3, 7, linked));
        verify(disclosureAccess, never()).withLockedSubjectPerson(
            anyInt(), anyInt(), anyInt(), anyInt(), any(), any());
        verify(controlOperations, never()).create(
            any(Integer.class),
            any(Integer.class),
            any(DataSubjectRequest.class));

        doThrow(new BadRequestException("Subject person is missing"))
            .doAnswer(invocation -> {
                Function<Supplier<Object>, Object> controlTransaction =
                    invocation.getArgument(4);
                Supplier<Object> work = invocation.getArgument(5);
                return controlTransaction.apply(work);
            })
            .when(disclosureAccess).withLockedSubjectPerson(
                anyInt(),
                anyInt(),
                anyInt(),
                anyInt(),
                any(),
                any());
        assertThrows(BadRequestException.class, () -> service.create(3, 7, linked));

        when(controlOperations.create(any(Integer.class), any(Integer.class), any(DataSubjectRequest.class)))
            .thenAnswer(invocation -> DataSubjectRequestDto.from(invocation.getArgument(2)));
        service.create(3, 7, linked);
        verify(disclosureAccess, org.mockito.Mockito.times(2)).withLockedSubjectPerson(
            org.mockito.ArgumentMatchers.eq(3),
            org.mockito.ArgumentMatchers.eq(7),
            org.mockito.ArgumentMatchers.eq(4),
            org.mockito.ArgumentMatchers.eq(5),
            any(),
            any());
    }

    @Test
    void subjectTargetLostAfterInitialValidationRemainsAConflict() {
        DataSubjectRequestUpsertRequest linked = request("disclosure");
        linked.setSubjectWorkspaceId(4);
        linked.setSubjectPersonId(5);
        when(controlOperations.workspaceBelongsToOrg(3, 4)).thenReturn(true);
        doThrow(new ConflictException("Subject person was removed"))
            .when(disclosureAccess).withLockedSubjectPerson(
                anyInt(),
                anyInt(),
                anyInt(),
                anyInt(),
                any(),
                any());

        assertThrows(ConflictException.class, () -> service.create(3, 7, linked));
        verify(controlOperations, never()).create(
            any(Integer.class),
            any(Integer.class),
            any(DataSubjectRequest.class));
    }

    @Test
    void updatePreservesOmittedFieldsAndDelegatesSnapshotsSeparately() {
        DataSubjectRequest stored = storedVerifiedRequest();
        stored.setSubjectWorkspaceId(4);
        stored.setSubjectPersonId(5);
        stored.setSummary("Original summary");
        when(controlOperations.loadForMutation(3, 9, 7)).thenReturn(stored);
        when(controlOperations.workspaceBelongsToOrg(3, 4)).thenReturn(true);
        when(controlOperations.update(any(Integer.class), any(Long.class), any(Integer.class),
                any(DataSubjectRequest.class), any(DataSubjectRequest.class)))
            .thenAnswer(invocation -> DataSubjectRequestDto.from(invocation.getArgument(4)));
        DataSubjectRequestUpsertRequest update = request("disclosure");
        update.setResolution("   ");

        DataSubjectRequestDto updated = service.update(3, 9, 7, update);

        assertEquals(4, updated.getSubjectWorkspaceId());
        assertEquals(5, updated.getSubjectPersonId());
        assertEquals("Original summary", updated.getSummary());
        ArgumentCaptor<DataSubjectRequest> before = ArgumentCaptor.forClass(DataSubjectRequest.class);
        ArgumentCaptor<DataSubjectRequest> after = ArgumentCaptor.forClass(DataSubjectRequest.class);
        verify(controlOperations).update(
            org.mockito.ArgumentMatchers.eq(3), org.mockito.ArgumentMatchers.eq(9L),
            org.mockito.ArgumentMatchers.eq(7), before.capture(), after.capture());
        assertEquals("Original summary", before.getValue().getSummary());
        assertEquals(4, after.getValue().getSubjectWorkspaceId());
    }

    @Test
    void updateTargetLostAfterInitialValidationRemainsAConflict() {
        DataSubjectRequest stored = storedVerifiedRequest();
        stored.setSubjectWorkspaceId(4);
        stored.setSubjectPersonId(5);
        when(controlOperations.loadForMutation(3, 9, 7)).thenReturn(stored);
        when(controlOperations.workspaceBelongsToOrg(3, 4)).thenReturn(true);
        doThrow(new ConflictException("Subject person was removed"))
            .when(disclosureAccess).withLockedSubjectPerson(
                anyInt(),
                anyInt(),
                anyInt(),
                anyInt(),
                any(),
                any());
        DataSubjectRequestUpsertRequest update = request("disclosure");
        update.setStatus("closed");

        assertThrows(ConflictException.class, () -> service.update(3, 9, 7, update));

        verify(controlOperations, never()).update(
            any(Integer.class),
            any(Long.class),
            any(Integer.class),
            any(DataSubjectRequest.class),
            any(DataSubjectRequest.class));
    }

    @Test
    void updateRetainsPreviousAndRequestedWorkspaceRootsBeforePersonValidation() {
        DataSubjectRequest stored = storedVerifiedRequest();
        stored.setSubjectWorkspaceId(11);
        stored.setSubjectPersonId(12);
        when(controlOperations.loadForMutation(3, 9, 7)).thenReturn(stored);
        when(controlOperations.workspaceBelongsToOrg(3, 4)).thenReturn(true);
        when(controlOperations.update(
                any(Integer.class),
                any(Long.class),
                any(Integer.class),
                any(DataSubjectRequest.class),
                any(DataSubjectRequest.class)))
            .thenAnswer(invocation -> DataSubjectRequestDto.from(invocation.getArgument(4)));
        DataSubjectRequestUpsertRequest update = request("disclosure");
        update.setSubjectWorkspaceId(4);
        update.setSubjectPersonId(5);

        service.update(3, 9, 7, update);

        verify(controlOperations).withLockedSubjectRoots(
            org.mockito.ArgumentMatchers.eq(3),
            org.mockito.ArgumentMatchers.eq(7),
            org.mockito.ArgumentMatchers.eq(Set.of(4, 11)),
            any());
    }

    @Test
    void disclosureHydratesControlLabelsAndAuditsOnlyAfterTenantAssembly() {
        DataSubjectRequest request = storedVerifiedRequest();
        request.setSubjectWorkspaceId(4);
        request.setSubjectPersonId(5);
        AuditEntryDto audit = new AuditEntryDto();
        WorkspaceSnapshot workspaces = new WorkspaceSnapshot(List.of(4, 6), Map.of(4, "Owner", 6, "Overlay"));
        when(controlOperations.prepareDisclosure(3, 9, 7))
            .thenReturn(new DisclosureControlData(request, workspaces, List.of(audit), 1_234));
        ThirdPartyProvisionDto provision = new ThirdPartyProvisionDto();
        provision.setTargetWorkspaceId(6);
        DataSubjectDisclosureDto assembled = new DataSubjectDisclosureDto();
        assembled.setThirdPartyProvisions(List.of(provision));
        when(disclosureAccess.assemble(3, 7, 4, 5, List.of(4, 6))).thenReturn(assembled);

        DataSubjectDisclosureDto disclosure = service.disclosure(3, 9, 7);

        assertEquals("Overlay", disclosure.getThirdPartyProvisions().getFirst().getTargetWorkspaceName());
        assertEquals(List.of(audit), disclosure.getAuditTrail());
        assertEquals(1_234, disclosure.getAuditTrailTotal());
        assertNotNull(disclosure.getGeneratedAt());
        InOrder order = inOrder(controlOperations, disclosureAccess);
        order.verify(controlOperations).prepareDisclosure(3, 9, 7);
        order.verify(disclosureAccess).assemble(3, 7, 4, 5, List.of(4, 6));
        order.verify(controlOperations).recordDisclosureAudit(3, 9, 5, 4);
    }

    @Test
    void disclosureFailsClosedWhenTheFinalAuditCannotBeWritten() {
        DataSubjectRequest request = storedVerifiedRequest();
        request.setSubjectWorkspaceId(4);
        request.setSubjectPersonId(5);
        WorkspaceSnapshot workspaces = new WorkspaceSnapshot(List.of(4), Map.of(4, "Owner"));
        when(controlOperations.prepareDisclosure(3, 9, 7))
            .thenReturn(new DisclosureControlData(request, workspaces, List.of(), 0));
        DataSubjectDisclosureDto assembled = new DataSubjectDisclosureDto();
        assembled.setThirdPartyProvisions(List.of());
        when(disclosureAccess.assemble(3, 7, 4, 5, List.of(4))).thenReturn(assembled);
        org.mockito.Mockito.doThrow(new IllegalStateException("audit unavailable"))
            .when(controlOperations).recordDisclosureAudit(3, 9, 5, 4);

        assertThrows(ServiceUnavailableException.class, () -> service.disclosure(3, 9, 7));
    }

    @Test
    void tenantAssemblyFailureNeverWritesTheDisclosureAudit() {
        DataSubjectRequest request = storedVerifiedRequest();
        request.setSubjectWorkspaceId(4);
        request.setSubjectPersonId(5);
        WorkspaceSnapshot workspaces = new WorkspaceSnapshot(List.of(4), Map.of(4, "Owner"));
        when(controlOperations.prepareDisclosure(3, 9, 7))
            .thenReturn(new DisclosureControlData(request, workspaces, List.of(), 0));
        when(disclosureAccess.assemble(3, 7, 4, 5, List.of(4)))
            .thenThrow(new IllegalStateException("tenant unavailable"));

        assertThrows(IllegalStateException.class, () -> service.disclosure(3, 9, 7));
        verify(controlOperations, never()).recordDisclosureAudit(any(Integer.class), any(Long.class),
            any(Integer.class), any(Integer.class));
    }

    private static DataSubjectRequest storedVerifiedRequest() {
        DataSubjectRequest request = new DataSubjectRequest();
        request.setId(9);
        request.setOrgId(3);
        request.setRequestType("disclosure");
        request.setStatus("received");
        request.setRequesterName("Requester");
        request.setSubjectName("Subject");
        request.setReceivedAt(LocalDateTime.of(2026, 1, 2, 3, 4));
        request.setIdentityVerifiedAt(LocalDateTime.of(2026, 1, 3, 3, 4));
        return request;
    }

    private static DataSubjectRequestUpsertRequest request(String requestType) {
        DataSubjectRequestUpsertRequest request = new DataSubjectRequestUpsertRequest();
        request.setRequestType(requestType);
        request.setRequesterName("Requester");
        request.setSubjectName("Subject");
        return request;
    }
}
