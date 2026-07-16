package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ooo.klae.connex.backend.beans.DataSubjectRequest;
import ooo.klae.connex.backend.dto.DataSubjectDisclosureDto;
import ooo.klae.connex.backend.dto.DataSubjectDisclosureDto.ActivityDto;
import ooo.klae.connex.backend.dto.DataSubjectDisclosureDto.AttachmentDto;
import ooo.klae.connex.backend.dto.DataSubjectDisclosureDto.AuditEntryDto;
import ooo.klae.connex.backend.dto.DataSubjectDisclosureDto.CustomFieldValueDto;
import ooo.klae.connex.backend.dto.DataSubjectDisclosureDto.DealAssociationDto;
import ooo.klae.connex.backend.dto.DataSubjectDisclosureDto.EmploymentDto;
import ooo.klae.connex.backend.dto.DataSubjectDisclosureDto.IntroductionDto;
import ooo.klae.connex.backend.dto.DataSubjectDisclosureDto.NoteDto;
import ooo.klae.connex.backend.dto.DataSubjectDisclosureDto.PersonDto;
import ooo.klae.connex.backend.dto.DataSubjectDisclosureDto.RelationshipEdgeDto;
import ooo.klae.connex.backend.dto.DataSubjectDisclosureDto.TagDto;
import ooo.klae.connex.backend.dto.DataSubjectDisclosureDto.TaskDto;
import ooo.klae.connex.backend.dto.DataSubjectDisclosureDto.ThirdPartyProvisionDto;
import ooo.klae.connex.backend.dto.DataSubjectRequestDto;
import ooo.klae.connex.backend.dto.DataSubjectRequestUpsertRequest;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.DataSubjectRequestMapper;

@ExtendWith(MockitoExtension.class)
class DataSubjectRequestServiceUnitTest {
    @Mock private DataSubjectRequestMapper dataSubjectRequestMapper;
    @Mock private OrgMemberService orgMemberService;
    @Mock private AuditService auditService;
    @Mock private SessionSecurityService sessionSecurityService;

    private DataSubjectRequestService service;

    @BeforeEach
    void setUp() {
        service = new DataSubjectRequestService(
            dataSubjectRequestMapper, orgMemberService, auditService, sessionSecurityService);
    }

    @Test
    void createGatesBeforePersistenceAndWritesMetadataOnlyAudit() {
        AtomicReference<DataSubjectRequest> inserted = new AtomicReference<>();
        doAnswer(invocation -> {
            DataSubjectRequest request = invocation.getArgument(0);
            request.setId(9);
            inserted.set(request);
            return 1;
        }).when(dataSubjectRequestMapper).insert(org.mockito.ArgumentMatchers.any(DataSubjectRequest.class));
        when(dataSubjectRequestMapper.findById(3, 9)).thenAnswer(invocation -> inserted.get());

        DataSubjectRequestDto created = service.create(3, 7, request("disclosure"));

        assertEquals(9, created.getId());
        assertEquals("received", created.getStatus());
        InOrder order = inOrder(orgMemberService, sessionSecurityService, dataSubjectRequestMapper);
        order.verify(orgMemberService).requireOrgAdmin(3, 7);
        order.verify(sessionSecurityService).requireRecentAuthentication(7);
        order.verify(dataSubjectRequestMapper).insert(inserted.get());
        verify(auditService).record(eq("appi.subject_request.create"), eq("organization"), eq(3),
            eq("Subject request 9"), eq("APPI data-subject request created"),
            eq(Map.of("requestId", 9L, "requestType", "disclosure", "status", "received")));
    }

    @Test
    void subjectLinkValidationIsDefensiveAtTheServiceBoundary() {
        DataSubjectRequestUpsertRequest oneSided = request("disclosure");
        oneSided.setSubjectWorkspaceId(4);
        assertThrows(BadRequestException.class, () -> service.create(3, 7, oneSided));

        DataSubjectRequestUpsertRequest missing = request("disclosure");
        missing.setSubjectWorkspaceId(4);
        missing.setSubjectPersonId(5);
        when(dataSubjectRequestMapper.subjectPersonInOrg(3, 4, 5)).thenReturn(false);
        assertThrows(BadRequestException.class, () -> service.create(3, 7, missing));

        verify(orgMemberService, org.mockito.Mockito.times(2)).requireOrgAdmin(3, 7);
        verify(sessionSecurityService, org.mockito.Mockito.times(2)).requireRecentAuthentication(7);
        verify(dataSubjectRequestMapper).subjectPersonInOrg(3, 4, 5);
    }

    @Test
    void updateAllowsTypeCorrectionAndWritesMetadataOnlyAudit() {
        DataSubjectRequest stored = storedRequest();
        when(dataSubjectRequestMapper.findById(3, 9)).thenReturn(stored);
        DataSubjectRequestUpsertRequest update = request("cease_use");
        update.setStatus("closed");

        DataSubjectRequestDto updated = service.update(3, 9, 7, update);

        assertEquals("cease_use", updated.getRequestType());
        assertEquals("closed", updated.getStatus());
        verify(auditService).record(eq("appi.subject_request.update"), eq("organization"), eq(3),
            eq("Subject request 9"), eq("APPI data-subject request updated"),
            eq(Map.of("requestId", 9L, "requestType", "cease_use", "status", "closed")));
    }

    @Test
    void disclosureRequiresALinkAndRevalidatesThePerson() {
        DataSubjectRequest unlinked = storedVerifiedRequest();
        when(dataSubjectRequestMapper.findById(3, 9)).thenReturn(unlinked);
        assertThrows(BadRequestException.class, () -> service.disclosure(3, 9, 7));

        DataSubjectRequest linked = storedVerifiedRequest();
        linked.setSubjectWorkspaceId(4);
        linked.setSubjectPersonId(5);
        when(dataSubjectRequestMapper.findById(3, 10)).thenReturn(linked);
        when(dataSubjectRequestMapper.findDisclosurePerson(3, 4, 5)).thenReturn(null);
        assertThrows(ResourceNotFoundException.class, () -> service.disclosure(3, 10, 7));

        verify(orgMemberService, org.mockito.Mockito.times(2)).requireOrgAdmin(3, 7);
        verify(sessionSecurityService, org.mockito.Mockito.times(2)).requireRecentAuthentication(7);
    }

    @Test
    void disclosureRequiresADisclosureTypeRequestWithVerifiedIdentity() {
        DataSubjectRequest wrongType = storedVerifiedRequest();
        wrongType.setRequestType("correction");
        wrongType.setSubjectWorkspaceId(4);
        wrongType.setSubjectPersonId(5);
        when(dataSubjectRequestMapper.findById(3, 9)).thenReturn(wrongType);
        assertThrows(BadRequestException.class, () -> service.disclosure(3, 9, 7));

        DataSubjectRequest unverified = storedRequest();
        unverified.setSubjectWorkspaceId(4);
        unverified.setSubjectPersonId(5);
        when(dataSubjectRequestMapper.findById(3, 10)).thenReturn(unverified);
        assertThrows(BadRequestException.class, () -> service.disclosure(3, 10, 7));

        verify(dataSubjectRequestMapper, org.mockito.Mockito.never())
            .findDisclosurePerson(org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void disclosureAssemblesAndAuditsLinkedSubjectIdentifiers() {
        DataSubjectRequest linked = storedVerifiedRequest();
        linked.setSubjectWorkspaceId(4);
        linked.setSubjectPersonId(5);
        PersonDto person = new PersonDto();
        person.setId(5);
        person.setWorkspaceId(4);
        TagDto tag = new TagDto();
        CustomFieldValueDto customField = new CustomFieldValueDto();
        ActivityDto activity = new ActivityDto();
        NoteDto note = new NoteDto();
        TaskDto task = new TaskDto();
        AttachmentDto attachment = new AttachmentDto();
        EmploymentDto employment = new EmploymentDto();
        RelationshipEdgeDto edge = new RelationshipEdgeDto();
        DealAssociationDto deal = new DealAssociationDto();
        IntroductionDto introduction = new IntroductionDto();
        ThirdPartyProvisionDto provision = new ThirdPartyProvisionDto();
        AuditEntryDto audit = new AuditEntryDto();
        when(dataSubjectRequestMapper.findById(3, 9)).thenReturn(linked);
        when(dataSubjectRequestMapper.findDisclosurePerson(3, 4, 5)).thenReturn(person);
        when(dataSubjectRequestMapper.findDisclosureTags(3, 4, 5)).thenReturn(List.of(tag));
        when(dataSubjectRequestMapper.findDisclosureCustomFields(3, 4, 5)).thenReturn(List.of(customField));
        when(dataSubjectRequestMapper.findDisclosureActivities(3, 4, 5)).thenReturn(List.of(activity));
        when(dataSubjectRequestMapper.findDisclosureNotes(3, 4, 5)).thenReturn(List.of(note));
        when(dataSubjectRequestMapper.findDisclosureTasks(3, 4, 5)).thenReturn(List.of(task));
        when(dataSubjectRequestMapper.findDisclosureAttachments(3, 4, 5)).thenReturn(List.of(attachment));
        when(dataSubjectRequestMapper.findDisclosureEmployment(3, 4, 5)).thenReturn(List.of(employment));
        when(dataSubjectRequestMapper.findDisclosureEdges(3, 4, 5)).thenReturn(List.of(edge));
        when(dataSubjectRequestMapper.findDisclosureDeals(3, 4, 5)).thenReturn(List.of(deal));
        when(dataSubjectRequestMapper.findDisclosureIntroductions(3, 4, 5)).thenReturn(List.of(introduction));
        when(dataSubjectRequestMapper.findDisclosureProvisions(3, 4, 5)).thenReturn(List.of(provision));
        when(dataSubjectRequestMapper.findDisclosureAudit(3, 4, 5, 1_000)).thenReturn(List.of(audit));
        when(dataSubjectRequestMapper.countDisclosureAudit(3, 4, 5)).thenReturn(1_234L);

        DataSubjectDisclosureDto disclosure = service.disclosure(3, 9, 7);

        assertEquals(5, disclosure.getPerson().getId());
        assertEquals(List.of(tag), disclosure.getTags());
        assertEquals(List.of(customField), disclosure.getCustomFieldValues());
        assertEquals(List.of(activity), disclosure.getActivities());
        assertEquals(List.of(note), disclosure.getNotes());
        assertEquals(List.of(task), disclosure.getTasks());
        assertEquals(List.of(attachment), disclosure.getAttachments());
        assertEquals(List.of(employment), disclosure.getEmploymentHistory());
        assertEquals(List.of(edge), disclosure.getRelationshipEdges());
        assertEquals(List.of(deal), disclosure.getDealAssociations());
        assertEquals(List.of(introduction), disclosure.getIntroductions());
        assertEquals(List.of(provision), disclosure.getThirdPartyProvisions());
        assertEquals(List.of(audit), disclosure.getAuditTrail());
        assertEquals(1_234, disclosure.getAuditTrailTotal());
        assertNotNull(disclosure.getGeneratedAt());
        verify(auditService).recordStrict(eq("appi.subject_request.disclosure"), eq("organization"), eq(3),
            eq("Subject request 9"), eq("Subject-scoped disclosure export assembled"),
            eq(Map.of("requestId", 9L, "subjectPersonId", 5, "subjectWorkspaceId", 4)));
    }

    @Test
    void updatePreservesOmittedFieldsClearsBlankTextAndAuditsTheFieldDiff() {
        DataSubjectRequest stored = storedVerifiedRequest();
        stored.setSubjectWorkspaceId(4);
        stored.setSubjectPersonId(5);
        stored.setDueAt(LocalDateTime.of(2026, 2, 1, 0, 0));
        stored.setSummary("Original summary");
        when(dataSubjectRequestMapper.findById(3, 9)).thenReturn(stored);
        when(dataSubjectRequestMapper.subjectPersonInOrg(3, 4, 5)).thenReturn(true);
        when(auditService.diff(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.anySet())).thenReturn(Map.of("dueAt", Map.of()));
        DataSubjectRequestUpsertRequest update = request("disclosure");
        update.setResolution("   ");

        DataSubjectRequestDto updated = service.update(3, 9, 7, update);

        assertEquals("received", updated.getStatus());
        assertEquals(4, updated.getSubjectWorkspaceId());
        assertEquals(5, updated.getSubjectPersonId());
        assertNotNull(updated.getIdentityVerifiedAt());
        assertEquals(LocalDateTime.of(2026, 2, 1, 0, 0), updated.getDueAt());
        assertEquals("Original summary", updated.getSummary());
        assertNull(updated.getResolution());
        assertNotNull(updated.getReceivedAt());
        verify(auditService).record(eq("appi.subject_request.update"), eq("organization"), eq(3),
            eq("Subject request 9"), eq("APPI data-subject request updated"),
            eq(Map.of("requestId", 9L, "requestType", "disclosure", "status", "received",
                "fields", Map.of("dueAt", Map.of()))));
    }

    private static DataSubjectRequest storedRequest() {
        DataSubjectRequest request = new DataSubjectRequest();
        request.setId(9);
        request.setOrgId(3);
        request.setRequestType("disclosure");
        request.setStatus("received");
        request.setRequesterName("Requester");
        request.setSubjectName("Subject");
        request.setReceivedAt(LocalDateTime.of(2026, 1, 2, 3, 4));
        return request;
    }

    private static DataSubjectRequest storedVerifiedRequest() {
        DataSubjectRequest request = storedRequest();
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
