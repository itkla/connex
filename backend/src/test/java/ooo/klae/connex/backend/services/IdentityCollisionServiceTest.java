package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ooo.klae.connex.backend.dto.IdentityCollisionDto;
import ooo.klae.connex.backend.dto.IdentityCollisionGroupRow;
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
            anyInt(), anyList(), anyInt());
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
        when(identityCollisionMapper.findVisibleMembers(eq(41), eq(List.of(first, second)), anyInt()))
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
        assertEquals(3, result.items().get(1).members().size());
    }

    @Test
    void boundsMembersPerGroupWithoutHidingTheVisibleCollisionSize() {
        IdentityCollisionQuery query = query("company", "domain", 1, 50);
        IdentityCollisionGroupRow group = group(
            "company", "domain", "crowded.example.com", 5_000, LocalDateTime.of(2026, 7, 25, 9, 0));
        ArgumentCaptor<Integer> memberLimit = ArgumentCaptor.forClass(Integer.class);
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(5);
        when(identityCollisionMapper.findVisibleGroups(5, "company", "domain", 50, 0L))
            .thenReturn(List.of(group));
        when(identityCollisionMapper.countVisibleGroups(5, "company", "domain")).thenReturn(1L);
        when(identityCollisionMapper.findVisibleMembers(eq(5), eq(List.of(group)), anyInt()))
            .thenAnswer(invocation -> IntStream.rangeClosed(1, invocation.<Integer>getArgument(2))
                .mapToObj(index ->
                    member("company", "domain", "crowded.example.com", index, "Company " + index))
                .toList());

        PageResponse<IdentityCollisionDto> result = service.list(query);

        verify(identityCollisionMapper).findVisibleMembers(
            eq(5), eq(List.of(group)), memberLimit.capture());
        int appliedLimit = memberLimit.getValue();
        assertTrue(appliedLimit >= 1 && appliedLimit < 5_000);
        IdentityCollisionDto dto = result.items().getFirst();
        assertEquals(5_000, dto.collisionSize());
        assertEquals(appliedLimit, dto.members().size());
    }

    @Test
    void detectsAGroupAndMemberSnapshotMismatch() {
        IdentityCollisionQuery query = query(null, null, 1, 50);
        IdentityCollisionGroupRow group = group(
            "company", "domain", "example.com", 2, LocalDateTime.now());
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(9);
        when(identityCollisionMapper.findVisibleGroups(9, null, null, 50, 0L))
            .thenReturn(List.of(group));
        when(identityCollisionMapper.countVisibleGroups(9, null, null)).thenReturn(1L);
        when(identityCollisionMapper.findVisibleMembers(eq(9), eq(List.of(group)), anyInt()))
            .thenReturn(List.of(
                member("company", "domain", "example.com", 4, "Only one")));

        IllegalStateException exception =
            assertThrows(IllegalStateException.class, () -> service.list(query));

        assertTrue(exception.getMessage().contains("changed during its read transaction"));
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
