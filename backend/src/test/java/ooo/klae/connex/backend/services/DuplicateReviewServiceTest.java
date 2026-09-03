package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ooo.klae.connex.backend.dto.DuplicateMatchKind;
import ooo.klae.connex.backend.dto.DuplicateMatchStrength;
import ooo.klae.connex.backend.dto.DuplicateReviewDecisionRequest;
import ooo.klae.connex.backend.dto.DuplicateReviewItemDto;
import ooo.klae.connex.backend.dto.DuplicateReviewItemRow;
import ooo.klae.connex.backend.dto.DuplicateReviewMaterializationKey;
import ooo.klae.connex.backend.dto.DuplicateReviewQuery;
import ooo.klae.connex.backend.dto.IdentityCollisionGroupKey;
import ooo.klae.connex.backend.dto.PageResponse;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.mappers.DuplicateReviewMapper;
import ooo.klae.connex.backend.mappers.IdentityCollisionMapper;
import ooo.klae.connex.backend.tenant.Permission;
import ooo.klae.connex.backend.tenant.RequirePermission;

@ExtendWith(MockitoExtension.class)
class DuplicateReviewServiceTest {

    private static final Clock CLOCK = Clock.fixed(
        Instant.parse("2026-09-02T12:00:00Z"), ZoneOffset.UTC);
    private static final String EMAIL_FINGERPRINT =
        DuplicateReviewService.evidenceFingerprint("person", "email", "pair@example.com");

    @Mock private DuplicateReviewMapper duplicateReviewMapper;
    @Mock private IdentityCollisionMapper identityCollisionMapper;
    @Mock private DuplicateDecisionLockService duplicateDecisionLockService;
    @Mock private WorkspaceService workspaceService;

    private DuplicateReviewService service;

    @BeforeEach
    void setUp() {
        service = new DuplicateReviewService(
            duplicateReviewMapper,
            identityCollisionMapper,
            duplicateDecisionLockService,
            workspaceService,
            CLOCK);
    }

    @Test
    void fingerprintIsStableAndSensitiveToEveryEvidenceComponent() {
        String first = DuplicateReviewService.evidenceFingerprint(
            "person", "email", "pair@example.com");
        String repeated = DuplicateReviewService.evidenceFingerprint(
            "person", "email", "pair@example.com");

        assertEquals(first, repeated);
        assertEquals(64, first.length());
        assertNotEquals(first, DuplicateReviewService.evidenceFingerprint(
            "company", "email", "pair@example.com"));
        assertNotEquals(first, DuplicateReviewService.evidenceFingerprint(
            "person", "phone", "pair@example.com"));
        assertNotEquals(first, DuplicateReviewService.evidenceFingerprint(
            "person", "email", "changed@example.com"));
    }

    @Test
    void listProjectsStoredStateAndUsesDatabasePagination() {
        DuplicateReviewQuery query = new DuplicateReviewQuery();
        query.setRecordType("person");
        query.setKind("email");
        query.setState("dismissed");
        query.setPage(3);
        query.setSize(25);
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(7);
        when(duplicateReviewMapper.countVisibleItems(
                7, "person", "email", "dismissed", 20))
            .thenReturn(52L);
        when(duplicateReviewMapper.findVisibleItems(
                7, "person", "email", "dismissed", 25, 50L, 20))
            .thenReturn(List.of(row("dismissed", EMAIL_FINGERPRINT)));

        PageResponse<DuplicateReviewItemDto> result = service.list(query);

        assertEquals(52L, result.total());
        assertEquals("dismissed", result.items().getFirst().state());
        assertEquals(DuplicateMatchStrength.STRONG, result.items().getFirst().confidence());
        assertEquals(DuplicateMatchKind.EMAIL, result.items().getFirst().evidence().kind());
        assertEquals(List.of(11, 19), result.items().getFirst().members().stream()
            .map(member -> member.recordId())
            .toList());
        assertEquals(List.of(true, true), result.items().getFirst().members().stream()
            .map(member -> member.ownedByActiveWorkspace())
            .toList());
    }

    @Test
    void evidenceRefreshDeactivatesOldFingerprintAndCreatesOpenMaterializedRows() {
        LocalDateTime detectedAt = LocalDateTime.of(2026, 9, 2, 12, 0);

        service.refreshPersonEvidence(7, "email", "old@example.com", detectedAt);
        service.refreshPersonEvidence(7, "email", "new@example.com", detectedAt);

        String oldFingerprint = DuplicateReviewService.evidenceFingerprint(
            "person", "email", "old@example.com");
        String newFingerprint = DuplicateReviewService.evidenceFingerprint(
            "person", "email", "new@example.com");
        verify(duplicateReviewMapper).deactivateEvidence(
            7, "person", "email", oldFingerprint);
        verify(duplicateReviewMapper).deactivateEvidence(
            7, "person", "email", newFingerprint);
        verify(duplicateReviewMapper).upsertPersonPairs(
            7, "email", "new@example.com", newFingerprint, detectedAt, 20);
        verify(duplicateReviewMapper).upsertPersonOversizedGroup(
            7, "email", "new@example.com", newFingerprint, detectedAt, 20);
        assertNotEquals(oldFingerprint, newFingerprint);
    }

    @Test
    void workspaceRefreshKeysetPagesAndUsesOneBoundedMaterializationStatementPerPage() {
        LocalDateTime detectedAt = LocalDateTime.of(2026, 9, 2, 12, 0);
        List<IdentityCollisionGroupKey> firstPage = IntStream.range(0, 500)
            .mapToObj(index -> new IdentityCollisionGroupKey(
                "company", "domain", "page-%03d.example".formatted(index)))
            .toList();
        IdentityCollisionGroupKey last = firstPage.getLast();
        List<IdentityCollisionGroupKey> secondPage = List.of(
            new IdentityCollisionGroupKey("person", "email", "last@example.com"));
        when(identityCollisionMapper.findVisibleGroupKeysAfter(
                7, null, null, null, 500))
            .thenReturn(firstPage);
        when(identityCollisionMapper.findVisibleGroupKeysAfter(
                7, last.recordType(), last.kind(), last.normalizedValue(), 500))
            .thenReturn(secondPage);

        service.refreshWorkspaceItems(7, detectedAt);

        verify(duplicateReviewMapper).deactivateWorkspace(7);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<DuplicateReviewMaterializationKey>> pages =
            ArgumentCaptor.forClass(List.class);
        verify(duplicateReviewMapper, times(2)).upsertEvidenceGroups(
            org.mockito.ArgumentMatchers.eq(7),
            pages.capture(),
            org.mockito.ArgumentMatchers.eq(detectedAt),
            org.mockito.ArgumentMatchers.eq(20));
        assertEquals(List.of(500, 1), pages.getAllValues().stream().map(List::size).toList());
        assertEquals(
            DuplicateReviewService.evidenceFingerprint("person", "email", "last@example.com"),
            pages.getAllValues().getLast().getFirst().evidenceFingerprint());
        verify(duplicateReviewMapper, never()).deactivateEvidence(
            org.mockito.ArgumentMatchers.anyInt(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void liveStateReconciliationIsIdempotentWhenAppliedTwice() {
        LocalDateTime detectedAt = LocalDateTime.of(2026, 9, 2, 12, 0);
        String fingerprint = DuplicateReviewService.evidenceFingerprint(
            "person", "external_id", "source:person-41");

        service.refreshPersonEvidence(
            7, "external_id", "source:person-41", detectedAt);
        List<MapperCall> appliedOnce = mapperInvocations();
        clearInvocations(duplicateReviewMapper);
        service.refreshPersonEvidence(
            7, "external_id", "source:person-41", detectedAt);

        assertEquals(List.of(
            "deactivateEvidence", "upsertPersonPairs", "upsertPersonOversizedGroup"),
            appliedOnce.stream().map(MapperCall::method).toList());
        assertEquals(appliedOnce, mapperInvocations());
        verify(duplicateReviewMapper).deactivateEvidence(
            7, "person", "external_id", fingerprint);
    }

    @Test
    void dismissNormalizesPairAndLocksReadAndUpdatePermissionsBeforeOrganizationAndDecision() {
        DuplicateReviewDecisionRequest request = new DuplicateReviewDecisionRequest(
            "person", "email", 19, 11, EMAIL_FINGERPRINT, " reviewed ");
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(7);
        when(workspaceService.getCurrentUserId()).thenReturn(5);
        when(duplicateReviewMapper.lockCurrentPair(
                7, "person", "email", 11, 19, EMAIL_FINGERPRINT))
            .thenReturn(91L);
        when(duplicateReviewMapper.findVisibleItemById(7, 91L, 20))
            .thenReturn(row("open", EMAIL_FINGERPRINT), row("dismissed", EMAIL_FINGERPRINT));
        when(duplicateReviewMapper.dismiss(
                7, 91L, 5, "reviewed", LocalDateTime.of(2026, 9, 2, 12, 0)))
            .thenReturn(1);

        DuplicateReviewItemDto result = service.dismiss(request);

        assertEquals("dismissed", result.state());
        verify(workspaceService).lockAndRequirePermissions(
            7, Map.of(5, Set.of(Permission.REPORT_READ, Permission.PERSON_UPDATE)));
        InOrder order = inOrder(
            workspaceService, duplicateDecisionLockService, duplicateReviewMapper);
        order.verify(workspaceService).lockAndRequirePermissions(
            7, Map.of(5, Set.of(Permission.REPORT_READ, Permission.PERSON_UPDATE)));
        order.verify(duplicateDecisionLockService).lockCurrentOrganization();
        order.verify(duplicateReviewMapper).lockCurrentPair(
            7, "person", "email", 11, 19, EMAIL_FINGERPRINT);
    }

    @Test
    void reopenDispatchesCompanyPermissionAndClearsOnlyExactEvidence() {
        String fingerprint = DuplicateReviewService.evidenceFingerprint(
            "company", "domain", "example.com");
        DuplicateReviewDecisionRequest request = new DuplicateReviewDecisionRequest(
            "company", "domain", 31, 29, fingerprint, null);
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(7);
        when(workspaceService.getCurrentUserId()).thenReturn(5);
        when(duplicateReviewMapper.lockCurrentPair(
                7, "company", "domain", 29, 31, fingerprint))
            .thenReturn(101L);
        DuplicateReviewItemRow dismissed = companyRow("dismissed", fingerprint);
        DuplicateReviewItemRow open = companyRow("open", fingerprint);
        when(duplicateReviewMapper.findVisibleItemById(7, 101L, 20))
            .thenReturn(dismissed, open);
        when(duplicateReviewMapper.reopen(7, 101L)).thenReturn(1);

        DuplicateReviewItemDto result = service.reopen(request);

        assertEquals("open", result.state());
        verify(workspaceService).lockAndRequirePermissions(
            7, Map.of(5, Set.of(Permission.REPORT_READ, Permission.COMPANY_UPDATE)));
        verify(duplicateReviewMapper).reopen(7, 101L);
    }

    @Test
    void staleFingerprintFailsWithConflictWithoutWriting() {
        DuplicateReviewDecisionRequest request = new DuplicateReviewDecisionRequest(
            "person", "email", 11, 19, "a".repeat(64), null);
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(7);
        when(workspaceService.getCurrentUserId()).thenReturn(5);
        when(duplicateReviewMapper.lockCurrentPair(
                7, "person", "email", 11, 19, "a".repeat(64)))
            .thenReturn(null);

        assertThrows(ConflictException.class, () -> service.dismiss(request));
    }

    @Test
    void rejectsSameRecordAndIncompatibleKinds() {
        assertThrows(BadRequestException.class, () -> service.dismiss(
            new DuplicateReviewDecisionRequest(
                "person", "email", 11, 11, EMAIL_FINGERPRINT, null)));
        DuplicateReviewQuery query = new DuplicateReviewQuery();
        query.setRecordType("person");
        query.setKind("domain");
        assertThrows(BadRequestException.class, () -> service.list(query));
    }

    @Test
    void publicHttpServiceMethodsRetainReportReadGate() throws Exception {
        for (Method method : List.of(
                DuplicateReviewService.class.getMethod("list", DuplicateReviewQuery.class),
                DuplicateReviewService.class.getMethod("summary"),
                DuplicateReviewService.class.getMethod(
                    "dismiss", DuplicateReviewDecisionRequest.class),
                DuplicateReviewService.class.getMethod(
                    "reopen", DuplicateReviewDecisionRequest.class))) {
            assertEquals(
                Permission.REPORT_READ,
                method.getAnnotation(RequirePermission.class).value());
        }
    }

    private static DuplicateReviewItemRow row(String state, String fingerprint) {
        DuplicateReviewItemRow row = new DuplicateReviewItemRow();
        row.setId(91L);
        row.setItemType("pair");
        row.setRecordType("person");
        row.setKind("email");
        row.setLowRecordId(11);
        row.setLowName("First Person");
        row.setLowCompanyName("First Company");
        row.setLowOwnerId(3);
        row.setLowOwnedByActiveWorkspace(true);
        row.setHighRecordId(19);
        row.setHighName("Second Person");
        row.setHighCompanyName(null);
        row.setHighOwnerId(null);
        row.setHighOwnedByActiveWorkspace(true);
        row.setCollisionSize(2);
        row.setDetectedAt(LocalDateTime.of(2026, 9, 1, 8, 0));
        row.setState(state);
        row.setEvidenceFingerprint(fingerprint);
        if ("dismissed".equals(state)) {
            row.setDismissedAt(LocalDateTime.of(2026, 9, 2, 12, 0));
            row.setDismissedByUserId(5);
        }
        return row;
    }

    private List<MapperCall> mapperInvocations() {
        return mockingDetails(duplicateReviewMapper).getInvocations().stream()
            .map(invocation -> new MapperCall(
                invocation.getMethod().getName(), List.of(invocation.getArguments())))
            .toList();
    }

    private record MapperCall(String method, List<Object> arguments) {
    }

    private static DuplicateReviewItemRow companyRow(String state, String fingerprint) {
        DuplicateReviewItemRow row = new DuplicateReviewItemRow();
        row.setId(101L);
        row.setItemType("pair");
        row.setRecordType("company");
        row.setKind("domain");
        row.setLowRecordId(29);
        row.setLowName("First Company");
        row.setLowOwnedByActiveWorkspace(true);
        row.setHighRecordId(31);
        row.setHighName("Second Company");
        row.setHighOwnedByActiveWorkspace(true);
        row.setCollisionSize(2);
        row.setDetectedAt(LocalDateTime.of(2026, 9, 1, 8, 0));
        row.setState(state);
        row.setEvidenceFingerprint(fingerprint);
        return row;
    }
}
