package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.sql.Connection;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import ooo.klae.connex.backend.dto.IdentityCollisionDto;
import ooo.klae.connex.backend.dto.IdentityCollisionGroupKey;
import ooo.klae.connex.backend.dto.IdentityCollisionGroupRow;
import ooo.klae.connex.backend.dto.IdentityCollisionMemberDto;
import ooo.klae.connex.backend.dto.IdentityCollisionMemberQuery;
import ooo.klae.connex.backend.dto.IdentityCollisionMemberRow;
import ooo.klae.connex.backend.dto.IdentityCollisionQuery;
import ooo.klae.connex.backend.dto.PageResponse;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.mappers.IdentityCollisionMapper;
import ooo.klae.connex.backend.tenant.Permission;
import ooo.klae.connex.backend.tenant.RequirePermission;

/**
 * Unit coverage for permissioned collision report assembly.
 */
@ExtendWith(MockitoExtension.class)
class IdentityCollisionServiceTest {

    private static final int MEMBER_BOUND = 20;

    @Mock private IdentityCollisionMapper identityCollisionMapper;
    @Mock private WorkspaceService workspaceService;

    private IdentityCollisionService service;

    @BeforeEach
    void setUp() {
        service = new IdentityCollisionService(identityCollisionMapper, workspaceService);
    }

    @Test
    void listRequiresReportReadPermission() throws Exception {
        Method method = IdentityCollisionService.class.getMethod("list", IdentityCollisionQuery.class);

        RequirePermission permission = method.getAnnotation(RequirePermission.class);

        assertEquals(Permission.REPORT_READ, permission.value());
    }

    @Test
    void listMembersRequiresReportReadPermission() throws Exception {
        Method method = IdentityCollisionService.class.getMethod(
            "listMembers", IdentityCollisionMemberQuery.class);

        RequirePermission permission = method.getAnnotation(RequirePermission.class);

        assertEquals(Permission.REPORT_READ, permission.value());
    }

    @Test
    void bothReadsPinRepeatableReadSoGroupSizesAndMembersShareOneSnapshot() throws Exception {
        Transactional groupRead = IdentityCollisionService.class
            .getMethod("list", IdentityCollisionQuery.class)
            .getAnnotation(Transactional.class);
        Transactional memberRead = IdentityCollisionService.class
            .getMethod("listMembers", IdentityCollisionMemberQuery.class)
            .getAnnotation(Transactional.class);

        assertEquals(Isolation.REPEATABLE_READ, groupRead.isolation());
        assertTrue(groupRead.readOnly());
        assertEquals(Isolation.REPEATABLE_READ, memberRead.isolation());
        assertTrue(memberRead.readOnly());
    }

    @Test
    void usesCurrentWorkspaceAndLongPaginationOffset() {
        IdentityCollisionQuery query = query(null, null, 1_000_000, 100);
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(73);
        when(identityCollisionMapper.findVisibleGroups(73, null, null, 100, 99_999_900L))
            .thenReturn(List.of());
        when(identityCollisionMapper.countVisibleGroups(73, null, null)).thenReturn(0L);

        PageResponse<IdentityCollisionDto> result = service.list(query);

        assertEquals(List.of(), result.items());
        assertEquals(0L, result.total());
        verify(identityCollisionMapper).findVisibleGroups(73, null, null, 100, 99_999_900L);
        verify(identityCollisionMapper, never()).findVisibleMembers(
            anyInt(), anyList(), anyInt(), anyInt());
    }

    @Test
    void rejectsIncompatibleRecordTypeAndKindFiltersBeforePersistence() {
        IdentityCollisionQuery personDomain = query("person", "domain", 1, 50);
        IdentityCollisionQuery companyEmail = query("company", "email", 1, 50);

        assertThrows(BadRequestException.class, () -> service.list(personDomain));
        assertThrows(BadRequestException.class, () -> service.list(companyEmail));

        verify(workspaceService, never()).getCurrentWorkspaceId();
    }

    @Test
    void listRejectsActiveReadCommittedTransactionBeforePersistence() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(
            Connection.TRANSACTION_READ_COMMITTED);

        IllegalStateException exception;
        try {
            exception = assertThrows(
                IllegalStateException.class,
                () -> service.list(query(null, null, 1, 50)));
        } finally {
            TransactionSynchronizationManager.clear();
        }

        assertEquals(
            "Identity collision reads require REPEATABLE_READ isolation",
            exception.getMessage());
        verifyNoInteractions(workspaceService, identityCollisionMapper);
    }

    @Test
    void listMembersRejectsActiveTransactionWithUnspecifiedIsolationBeforePersistence() {
        TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(null);
        TransactionSynchronizationManager.setActualTransactionActive(true);

        IllegalStateException exception;
        try {
            exception = assertThrows(
                IllegalStateException.class,
                () -> service.listMembers(
                    memberQuery("company", "domain", "example.com", 0, 50)));
        } finally {
            TransactionSynchronizationManager.clear();
        }

        assertEquals(
            "Identity collision reads require REPEATABLE_READ isolation",
            exception.getMessage());
        verifyNoInteractions(workspaceService, identityCollisionMapper);
    }

    @Test
    void groupsStableMemberRowsWithoutSurfacingPersistenceMetadata() {
        LocalDateTime rebuiltAt = LocalDateTime.of(2026, 7, 25, 12, 30);
        IdentityCollisionQuery query = query("person", "email", 2, 2);
        IdentityCollisionGroupRow first = group(
            "person", "email", "a@example.com", 2, rebuiltAt);
        IdentityCollisionGroupRow second = group(
            "person", "email", "b@example.com", 3, rebuiltAt);
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(41);
        when(identityCollisionMapper.findVisibleGroups(41, "person", "email", 2, 2L))
            .thenReturn(List.of(first, second));
        when(identityCollisionMapper.countVisibleGroups(41, "person", "email")).thenReturn(7L);
        when(identityCollisionMapper.findVisibleMembers(
                eq(41),
                eq(List.of(key("person", "email", "a@example.com"),
                    key("person", "email", "b@example.com"))),
                eq(0),
                eq(MEMBER_BOUND)))
            .thenReturn(List.of(
                member("person", "email", "a@example.com", 11, "A"),
                member("person", "email", "a@example.com", 13, "B"),
                member("person", "email", "b@example.com", 17, "C"),
                member("person", "email", "b@example.com", 19, "D"),
                member("person", "email", "b@example.com", 23, "E")));

        PageResponse<IdentityCollisionDto> result = service.list(query);

        assertEquals(7L, result.total());
        assertEquals(2, result.items().size());
        IdentityCollisionDto firstDto = result.items().getFirst();
        assertEquals("person", firstDto.recordType());
        assertEquals("email", firstDto.kind());
        assertEquals("a@example.com", firstDto.normalizedValue());
        assertEquals(2, firstDto.collisionSize());
        assertEquals(rebuiltAt, firstDto.rebuiltAt());
        assertEquals(List.of(11, 13), firstDto.members().stream().map(member -> member.recordId()).toList());
        assertEquals(List.of("A", "B"), firstDto.members().stream().map(member -> member.recordName()).toList());
        assertFalse(firstDto.membersTruncated());
        assertEquals(3, result.items().get(1).members().size());
        assertFalse(result.items().get(1).membersTruncated());
    }

    @Test
    void boundsMembersPerGroupWithoutHidingTheVisibleCollisionSize() {
        IdentityCollisionQuery query = query("company", "domain", 1, 50);
        IdentityCollisionGroupRow group = group(
            "company", "domain", "crowded.example.com", 5_000, LocalDateTime.of(2026, 7, 25, 9, 0));
        IdentityCollisionGroupKey key = key("company", "domain", "crowded.example.com");
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(5);
        when(identityCollisionMapper.findVisibleGroups(5, "company", "domain", 50, 0L))
            .thenReturn(List.of(group));
        when(identityCollisionMapper.countVisibleGroups(5, "company", "domain")).thenReturn(1L);
        when(identityCollisionMapper.findVisibleMembers(
                eq(5), eq(List.of(key)), eq(0), eq(MEMBER_BOUND)))
            .thenReturn(IntStream.rangeClosed(1, MEMBER_BOUND)
                .mapToObj(index ->
                    member("company", "domain", "crowded.example.com", index, "Company " + index))
                .toList());

        PageResponse<IdentityCollisionDto> result = service.list(query);

        verify(identityCollisionMapper).findVisibleMembers(
            5, List.of(key), 0, MEMBER_BOUND);
        IdentityCollisionDto dto = result.items().getFirst();
        assertEquals(5_000, dto.collisionSize());
        assertEquals(MEMBER_BOUND, dto.members().size());
        assertEquals(
            IntStream.rangeClosed(1, MEMBER_BOUND).boxed().toList(),
            dto.members().stream().map(member -> member.recordId()).toList());
        assertTrue(dto.membersTruncated());
    }

    @Test
    void rejectsUnderCountWhenGroupAndMemberSnapshotsDisagree() {
        IdentityCollisionQuery query = query(null, null, 1, 50);
        IdentityCollisionGroupRow group = group(
            "company", "domain", "example.com", 2, LocalDateTime.of(2026, 7, 25, 9, 0));
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(9);
        when(identityCollisionMapper.findVisibleGroups(9, null, null, 50, 0L))
            .thenReturn(List.of(group));
        when(identityCollisionMapper.countVisibleGroups(9, null, null)).thenReturn(1L);
        when(identityCollisionMapper.findVisibleMembers(
                eq(9),
                eq(List.of(key("company", "domain", "example.com"))),
                eq(0),
                eq(MEMBER_BOUND)))
            .thenReturn(List.of(member("company", "domain", "example.com", 4, "Only one")));

        IllegalStateException exception =
            assertThrows(IllegalStateException.class, () -> service.list(query));

        assertEquals(
            "Identity collision group changed during its read transaction",
            exception.getMessage());
    }

    @Test
    void rejectsOverCountInsteadOfSilentlyCappingMapperResults() {
        IdentityCollisionQuery query = query(null, null, 1, 50);
        IdentityCollisionGroupRow group = group(
            "company", "domain", "example.com", 5_000, LocalDateTime.of(2026, 7, 25, 9, 0));
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(9);
        when(identityCollisionMapper.findVisibleGroups(9, null, null, 50, 0L))
            .thenReturn(List.of(group));
        when(identityCollisionMapper.countVisibleGroups(9, null, null)).thenReturn(1L);
        when(identityCollisionMapper.findVisibleMembers(
                eq(9),
                eq(List.of(key("company", "domain", "example.com"))),
                eq(0),
                eq(MEMBER_BOUND)))
            .thenReturn(IntStream.rangeClosed(1, MEMBER_BOUND + 1)
                .mapToObj(index ->
                    member("company", "domain", "example.com", index, "Company " + index))
                .toList());

        IllegalStateException exception =
            assertThrows(IllegalStateException.class, () -> service.list(query));

        assertEquals(
            "Identity collision group changed during its read transaction",
            exception.getMessage());
    }

    @Test
    void memberPageForwardsTheKeysetCursorAndReportsTheVisibleGroupSize() {
        IdentityCollisionMemberQuery query =
            memberQuery("company", "domain", "crowded.example.com", 120, 25);
        IdentityCollisionGroupKey key = key("company", "domain", "crowded.example.com");
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(17);
        when(identityCollisionMapper.countVisibleGroupMembers(
            17, "company", "domain", "crowded.example.com")).thenReturn(5_000L);
        when(identityCollisionMapper.findVisibleMembers(17, List.of(key), 120, 25))
            .thenReturn(List.of(
                member("company", "domain", "crowded.example.com", 121, "Company 121"),
                member("company", "domain", "crowded.example.com", 122, "Company 122")));

        PageResponse<IdentityCollisionMemberDto> result = service.listMembers(query);

        verify(identityCollisionMapper).findVisibleMembers(17, List.of(key), 120, 25);
        assertEquals(5_000L, result.total());
        assertEquals(List.of(121, 122),
            result.items().stream().map(member -> member.recordId()).toList());
        assertEquals(List.of("Company 121", "Company 122"),
            result.items().stream().map(member -> member.recordName()).toList());
    }

    @Test
    void memberPageHidesGroupsRestrictionsHaveDroppedBelowACollision() {
        IdentityCollisionMemberQuery query =
            memberQuery("person", "email", "restricted@example.com", 0, 50);
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(23);
        when(identityCollisionMapper.countVisibleGroupMembers(
            23, "person", "email", "restricted@example.com")).thenReturn(1L);

        PageResponse<IdentityCollisionMemberDto> result = service.listMembers(query);

        assertEquals(List.of(), result.items());
        assertEquals(0L, result.total());
        verify(identityCollisionMapper, never()).findVisibleMembers(
            anyInt(), anyList(), anyInt(), anyInt());
    }

    @Test
    void memberPageRejectsUnsupportedOrIncompatibleGroupIdentitiesBeforePersistence() {
        assertThrows(BadRequestException.class, () -> service.listMembers(
            memberQuery("deal", "email", "a@example.com", 0, 50)));
        assertThrows(BadRequestException.class, () -> service.listMembers(
            memberQuery("person", "email", "  ", 0, 50)));
        assertThrows(BadRequestException.class, () -> service.listMembers(
            memberQuery("person", "domain", "example.com", 0, 50)));
        assertThrows(BadRequestException.class, () -> service.listMembers(
            memberQuery("company", "email", "a@example.com", 0, 50)));

        verify(workspaceService, never()).getCurrentWorkspaceId();
        verify(identityCollisionMapper, never()).countVisibleGroupMembers(
            anyInt(), anyString(), anyString(), anyString());
    }

    private static IdentityCollisionQuery query(
            String recordType, String kind, int page, int size) {
        IdentityCollisionQuery query = new IdentityCollisionQuery();
        query.setRecordType(recordType);
        query.setKind(kind);
        query.setPage(page);
        query.setSize(size);
        return query;
    }

    private static IdentityCollisionMemberQuery memberQuery(
            String recordType, String kind, String normalizedValue, int afterRecordId, int size) {
        IdentityCollisionMemberQuery query = new IdentityCollisionMemberQuery();
        query.setRecordType(recordType);
        query.setKind(kind);
        query.setNormalizedValue(normalizedValue);
        query.setAfterRecordId(afterRecordId);
        query.setSize(size);
        return query;
    }

    private static IdentityCollisionGroupKey key(
            String recordType, String kind, String normalizedValue) {
        return new IdentityCollisionGroupKey(recordType, kind, normalizedValue);
    }

    private static IdentityCollisionGroupRow group(
            String recordType,
            String kind,
            String normalizedValue,
            int collisionSize,
            LocalDateTime rebuiltAt) {
        IdentityCollisionGroupRow row = new IdentityCollisionGroupRow();
        row.setRecordType(recordType);
        row.setKind(kind);
        row.setNormalizedValue(normalizedValue);
        row.setCollisionSize(collisionSize);
        row.setRebuiltAt(rebuiltAt);
        return row;
    }

    private static IdentityCollisionMemberRow member(
            String recordType,
            String kind,
            String normalizedValue,
            int recordId,
            String recordName) {
        IdentityCollisionMemberRow row = new IdentityCollisionMemberRow();
        row.setRecordType(recordType);
        row.setKind(kind);
        row.setNormalizedValue(normalizedValue);
        row.setRecordId(recordId);
        row.setRecordName(recordName);
        return row;
    }
}
