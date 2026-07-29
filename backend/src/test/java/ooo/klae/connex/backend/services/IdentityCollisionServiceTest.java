package ooo.klae.connex.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import ooo.klae.connex.backend.dto.IdentityCollisionDto;
import ooo.klae.connex.backend.dto.IdentityCollisionGroupKey;
import ooo.klae.connex.backend.dto.IdentityCollisionGroupPageRow;
import ooo.klae.connex.backend.dto.IdentityCollisionMemberDto;
import ooo.klae.connex.backend.dto.IdentityCollisionMemberPageDto;
import ooo.klae.connex.backend.dto.IdentityCollisionMemberQuery;
import ooo.klae.connex.backend.dto.IdentityCollisionMemberRow;
import ooo.klae.connex.backend.dto.IdentityCollisionQuery;
import ooo.klae.connex.backend.dto.PageResponse;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.IdentityCollisionReportTimeoutException;
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
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(
            Connection.TRANSACTION_REPEATABLE_READ);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
    }

    @AfterEach
    void tearDown() {
        TransactionSynchronizationManager.clear();
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
    void bothReadsUseReadOnlyRepeatableReadTransactions() throws Exception {
        Transactional groupRead = IdentityCollisionService.class
            .getMethod("list", IdentityCollisionQuery.class)
            .getAnnotation(Transactional.class);
        Transactional memberRead = IdentityCollisionService.class
            .getMethod("listMembers", IdentityCollisionMemberQuery.class)
            .getAnnotation(Transactional.class);

        assertEquals(Isolation.REPEATABLE_READ, groupRead.isolation());
        assertTrue(groupRead.readOnly());
        assertEquals(4, groupRead.timeout());
        assertEquals(Isolation.REPEATABLE_READ, memberRead.isolation());
        assertTrue(memberRead.readOnly());
        assertEquals(-1, memberRead.timeout());
    }

    @Test
    void usesCurrentWorkspaceAndLongPaginationOffset() {
        IdentityCollisionQuery query = query(null, null, 1_000_000, 100);
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(73);
        when(identityCollisionMapper.findVisibleGroupPage(
                73, null, null, 100, 99_999_900L))
            .thenReturn(List.of(sentinel(0L, 1L)));

        PageResponse<IdentityCollisionDto> result = service.list(query);

        assertEquals(List.of(), result.items());
        assertEquals(0L, result.total());
        verify(identityCollisionMapper).findVisibleGroupPage(
            73, null, null, 100, 99_999_900L);
        verify(identityCollisionMapper, never()).findVisibleMembers(
            anyInt(), anyList(), anyInt(), anyInt());
    }

    @Test
    void outOfRangePageReturnsTheExactVisibleTotalWithoutMemberHydration() {
        IdentityCollisionQuery query = query("company", "domain", 4, 2);
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(74);
        when(identityCollisionMapper.findVisibleGroupPage(
                74, "company", "domain", 2, 6L))
            .thenReturn(List.of(sentinel(5L, 6L)));

        PageResponse<IdentityCollisionDto> result = service.list(query);

        assertEquals(List.of(), result.items());
        assertEquals(5L, result.total());
        verify(identityCollisionMapper).findVisibleGroupPage(
            74, "company", "domain", 2, 6L);
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
    void listRejectsMissingTransactionBeforePersistence() {
        TransactionSynchronizationManager.clear();

        IllegalStateException exception = assertThrows(
            IllegalStateException.class,
            () -> service.list(query(null, null, 1, 50)));

        assertEquals(
            "Identity collision reads require an active REPEATABLE_READ transaction",
            exception.getMessage());
        verifyNoInteractions(workspaceService, identityCollisionMapper);
    }

    @Test
    void groupsStableMemberRowsWithoutSurfacingPersistenceMetadata() {
        LocalDateTime rebuiltAt = LocalDateTime.of(2026, 7, 25, 12, 30);
        IdentityCollisionQuery query = query("person", "email", 2, 2);
        IdentityCollisionGroupPageRow first = group(
            "person", "email", "a@example.com", 2, rebuiltAt, 7L, 3L);
        IdentityCollisionGroupPageRow second = group(
            "person", "email", "b@example.com", 3, rebuiltAt, 7L, 4L);
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(41);
        when(identityCollisionMapper.findVisibleGroupPage(
                41, "person", "email", 2, 2L))
            .thenReturn(List.of(first, second));
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
        verify(identityCollisionMapper).findVisibleGroupPage(
            41, "person", "email", 2, 2L);
    }

    @Test
    void boundsMembersPerGroupWithoutHidingTheVisibleCollisionSize() {
        IdentityCollisionQuery query = query("company", "domain", 1, 50);
        IdentityCollisionGroupPageRow group = group(
            "company",
            "domain",
            "crowded.example.com",
            5_000,
            LocalDateTime.of(2026, 7, 25, 9, 0),
            1L,
            1L);
        IdentityCollisionGroupKey key = key("company", "domain", "crowded.example.com");
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(5);
        when(identityCollisionMapper.findVisibleGroupPage(
                5, "company", "domain", 50, 0L))
            .thenReturn(List.of(group));
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
        IdentityCollisionGroupPageRow group = group(
            "company",
            "domain",
            "example.com",
            2,
            LocalDateTime.of(2026, 7, 25, 9, 0),
            1L,
            1L);
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(9);
        when(identityCollisionMapper.findVisibleGroupPage(9, null, null, 50, 0L))
            .thenReturn(List.of(group));
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
        IdentityCollisionGroupPageRow group = group(
            "company",
            "domain",
            "example.com",
            5_000,
            LocalDateTime.of(2026, 7, 25, 9, 0),
            1L,
            1L);
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(9);
        when(identityCollisionMapper.findVisibleGroupPage(9, null, null, 50, 0L))
            .thenReturn(List.of(group));
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
    void malformedSentinelAndGroupPageMetadataFailClosed() {
        IdentityCollisionQuery query = query(null, null, 1, 2);
        LocalDateTime rebuiltAt = LocalDateTime.of(2026, 7, 25, 9, 0);
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(10);

        when(identityCollisionMapper.findVisibleGroupPage(10, null, null, 2, 0L))
            .thenReturn(List.of(sentinel(0L, 1L), sentinel(0L, 1L)));
        assertThrows(IllegalStateException.class, () -> service.list(query));

        IdentityCollisionGroupPageRow malformedSentinel = sentinel(0L, 1L);
        malformedSentinel.setKind("domain");
        when(identityCollisionMapper.findVisibleGroupPage(10, null, null, 2, 0L))
            .thenReturn(List.of(malformedSentinel));
        assertThrows(IllegalStateException.class, () -> service.list(query));

        when(identityCollisionMapper.findVisibleGroupPage(10, null, null, 2, 0L))
            .thenReturn(List.of(
                group("company", "domain", "a.example", 2, rebuiltAt, 2L, 1L),
                group("company", "domain", "b.example", 2, rebuiltAt, 3L, 2L)));
        assertThrows(IllegalStateException.class, () -> service.list(query));

        when(identityCollisionMapper.findVisibleGroupPage(10, null, null, 2, 0L))
            .thenReturn(List.of(
                group("company", "domain", "a.example", 2, rebuiltAt, 2L, 1L)));
        assertThrows(IllegalStateException.class, () -> service.list(query));

        when(identityCollisionMapper.findVisibleGroupPage(10, null, null, 2, 0L))
            .thenReturn(List.of());
        assertThrows(IllegalStateException.class, () -> service.list(query));

        verify(identityCollisionMapper, never()).findVisibleMembers(
            anyInt(), anyList(), anyInt(), anyInt());
    }

    @Test
    void groupQueryDeadlineFailuresAreTranslatedButUnrelatedDatabaseFailuresPropagate() {
        IdentityCollisionQuery query = query(null, null, 1, 50);
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(11);

        QueryTimeoutException queryTimeout = new QueryTimeoutException("canonical@example.com");
        doThrow(queryTimeout).when(identityCollisionMapper)
            .findVisibleGroupPage(11, null, null, 50, 0L);
        IdentityCollisionReportTimeoutException translatedQuery = assertThrows(
            IdentityCollisionReportTimeoutException.class,
            () -> service.list(query));
        assertSame(queryTimeout, translatedQuery.getCause());

        TransactionTimedOutException transactionTimeout =
            new TransactionTimedOutException("canonical@example.com");
        doThrow(transactionTimeout).when(identityCollisionMapper)
            .findVisibleGroupPage(11, null, null, 50, 0L);
        IdentityCollisionReportTimeoutException translatedTransaction = assertThrows(
            IdentityCollisionReportTimeoutException.class,
            () -> service.list(query));
        assertSame(transactionTimeout, translatedTransaction.getCause());

        DataAccessResourceFailureException sqlTimeout =
            new DataAccessResourceFailureException(
                "canonical@example.com",
                new SQLTimeoutException("canonical@example.com"));
        doThrow(sqlTimeout).when(identityCollisionMapper)
            .findVisibleGroupPage(11, null, null, 50, 0L);
        IdentityCollisionReportTimeoutException translatedSqlTimeout = assertThrows(
            IdentityCollisionReportTimeoutException.class,
            () -> service.list(query));
        assertSame(sqlTimeout, translatedSqlTimeout.getCause());

        DataAccessResourceFailureException mysqlDeadline =
            new DataAccessResourceFailureException(
                "canonical@example.com",
                new SQLException("canonical@example.com", "HY000", 3024));
        doThrow(mysqlDeadline).when(identityCollisionMapper)
            .findVisibleGroupPage(11, null, null, 50, 0L);
        IdentityCollisionReportTimeoutException translatedMysqlDeadline = assertThrows(
            IdentityCollisionReportTimeoutException.class,
            () -> service.list(query));
        assertSame(mysqlDeadline, translatedMysqlDeadline.getCause());

        DataAccessResourceFailureException unrelated =
            new DataAccessResourceFailureException(
                "canonical@example.com",
                new SQLException("canonical@example.com", "HY000", 1205));
        doThrow(unrelated).when(identityCollisionMapper)
            .findVisibleGroupPage(11, null, null, 50, 0L);
        assertSame(
            unrelated,
            assertThrows(DataAccessResourceFailureException.class, () -> service.list(query)));
    }

    @Test
    void overallGroupListDeadlineAlsoTranslatesMemberPreviewTimeouts() {
        IdentityCollisionQuery query = query("company", "domain", 1, 50);
        LocalDateTime rebuiltAt = LocalDateTime.of(2026, 7, 25, 9, 0);
        IdentityCollisionGroupPageRow group = group(
            "company",
            "domain",
            "deadline.example",
            2,
            rebuiltAt,
            1L,
            1L);
        IdentityCollisionGroupKey key =
            key("company", "domain", "deadline.example");
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(12);
        when(identityCollisionMapper.findVisibleGroupPage(
                12, "company", "domain", 50, 0L))
            .thenReturn(List.of(group));
        QueryTimeoutException memberTimeout =
            new QueryTimeoutException("canonical@example.com");
        doThrow(memberTimeout).when(identityCollisionMapper)
            .findVisibleMembers(12, List.of(key), 0, MEMBER_BOUND);

        IdentityCollisionReportTimeoutException translated = assertThrows(
            IdentityCollisionReportTimeoutException.class,
            () -> service.list(query));

        assertSame(memberTimeout, translated.getCause());
    }

    @Test
    void firstMemberPageUsesSizePlusOneToDeriveHasMoreAndNextCursor() {
        IdentityCollisionMemberQuery query =
            memberQuery("company", "domain", "crowded.example.com", 0, 2);
        IdentityCollisionGroupKey key = key("company", "domain", "crowded.example.com");
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(17);
        when(identityCollisionMapper.findVisibleMembers(17, List.of(key), 0, 3))
            .thenReturn(List.of(
                member("company", "domain", "crowded.example.com", 121, "Company 121"),
                member("company", "domain", "crowded.example.com", 122, "Company 122"),
                member("company", "domain", "crowded.example.com", 123, "Company 123")));

        IdentityCollisionMemberPageDto result = service.listMembers(query);

        verify(identityCollisionMapper).findVisibleMembers(17, List.of(key), 0, 3);
        verify(identityCollisionMapper, never()).findVisibleMembers(17, List.of(key), 0, 2);
        assertEquals(List.of(121, 122),
            result.items().stream().map(member -> member.recordId()).toList());
        assertEquals(List.of("Company 121", "Company 122"),
            result.items().stream().map(member -> member.recordName()).toList());
        assertTrue(result.hasMore());
        assertEquals(122, result.nextAfterRecordId());
    }

    @Test
    void memberPageResponseRejectsNullItemsAndInconsistentCursorState() {
        IdentityCollisionMemberPageDto terminal = new IdentityCollisionMemberPageDto(
            List.of(new IdentityCollisionMemberDto(1, "One")),
            false,
            null);

        assertThrows(UnsupportedOperationException.class, () -> terminal.items().clear());
        assertThrows(NullPointerException.class,
            () -> new IdentityCollisionMemberPageDto(null, false, null));
        assertThrows(IllegalArgumentException.class,
            () -> new IdentityCollisionMemberPageDto(List.of(), true, null));
        assertThrows(IllegalArgumentException.class,
            () -> new IdentityCollisionMemberPageDto(List.of(), false, 1));
    }

    @Test
    void terminalMemberPageHasNoNextCursor() {
        IdentityCollisionMemberQuery query =
            memberQuery("company", "domain", "crowded.example.com", 122, 25);
        IdentityCollisionGroupKey key = key("company", "domain", "crowded.example.com");
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(17);
        when(identityCollisionMapper.findVisibleMembers(17, List.of(key), 0, 2))
            .thenReturn(List.of(
                member("company", "domain", "crowded.example.com", 121, "Company 121"),
                member("company", "domain", "crowded.example.com", 122, "Company 122")));
        when(identityCollisionMapper.findVisibleMembers(17, List.of(key), 122, 26))
            .thenReturn(List.of(
                member("company", "domain", "crowded.example.com", 123, "Company 123")));

        IdentityCollisionMemberPageDto result = service.listMembers(query);

        assertEquals(List.of(123),
            result.items().stream().map(member -> member.recordId()).toList());
        assertFalse(result.hasMore());
        assertNull(result.nextAfterRecordId());
    }

    @Test
    void firstMemberPageHidesGroupsRestrictionsHaveDroppedBelowACollision() {
        IdentityCollisionMemberQuery query =
            memberQuery("person", "email", "restricted@example.com", 0, 50);
        IdentityCollisionGroupKey key = key("person", "email", "restricted@example.com");
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(23);
        when(identityCollisionMapper.findVisibleMembers(23, List.of(key), 0, 51))
            .thenReturn(List.of(
                member("person", "email", "restricted@example.com", 7, "Only visible")));

        IdentityCollisionMemberPageDto result = service.listMembers(query);

        assertEquals(List.of(), result.items());
        assertFalse(result.hasMore());
        assertNull(result.nextAfterRecordId());
        verify(identityCollisionMapper).findVisibleMembers(23, List.of(key), 0, 51);
    }

    @Test
    void continuationPageHidesGroupWhenTheBoundedProbeFindsFewerThanTwoVisibleMembers() {
        IdentityCollisionMemberQuery query =
            memberQuery("person", "email", "restricted@example.com", 100, 50);
        IdentityCollisionGroupKey key = key("person", "email", "restricted@example.com");
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(23);
        when(identityCollisionMapper.findVisibleMembers(23, List.of(key), 0, 2))
            .thenReturn(List.of(
                member("person", "email", "restricted@example.com", 7, "Only visible")));

        IdentityCollisionMemberPageDto result = service.listMembers(query);

        assertEquals(List.of(), result.items());
        assertFalse(result.hasMore());
        assertNull(result.nextAfterRecordId());
        verify(identityCollisionMapper, never()).findVisibleMembers(
            23, List.of(key), 100, 51);
    }

    @Test
    void continuationReturnsOneRowSuffixWhenTheGroupProbeStillFindsACollision() {
        IdentityCollisionMemberQuery query =
            memberQuery("company", "domain", "terminal.example.com", 20, 50);
        IdentityCollisionGroupKey key = key("company", "domain", "terminal.example.com");
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(29);
        when(identityCollisionMapper.findVisibleMembers(29, List.of(key), 0, 2))
            .thenReturn(List.of(
                member("company", "domain", "terminal.example.com", 10, "First"),
                member("company", "domain", "terminal.example.com", 20, "Second")));
        when(identityCollisionMapper.findVisibleMembers(29, List.of(key), 20, 51))
            .thenReturn(List.of(
                member("company", "domain", "terminal.example.com", 30, "Last")));

        IdentityCollisionMemberPageDto result = service.listMembers(query);

        assertEquals(List.of(30),
            result.items().stream().map(member -> member.recordId()).toList());
        assertFalse(result.hasMore());
        assertNull(result.nextAfterRecordId());
    }

    @Test
    void veryLargeLogicalGroupTraversalOnlyRequestsPageSizePlusOneAndABoundedProbe() {
        IdentityCollisionMemberQuery query =
            memberQuery("company", "domain", "million.example.com", 500_000, 100);
        IdentityCollisionGroupKey key = key("company", "domain", "million.example.com");
        when(workspaceService.getCurrentWorkspaceId()).thenReturn(31);
        when(identityCollisionMapper.findVisibleMembers(31, List.of(key), 0, 2))
            .thenReturn(List.of(
                member("company", "domain", "million.example.com", 1, "First"),
                member("company", "domain", "million.example.com", 2, "Second")));
        when(identityCollisionMapper.findVisibleMembers(31, List.of(key), 500_000, 101))
            .thenReturn(IntStream.rangeClosed(500_001, 500_101)
                .mapToObj(recordId ->
                    member("company", "domain", "million.example.com", recordId,
                        "Company " + recordId))
                .toList());

        IdentityCollisionMemberPageDto result = service.listMembers(query);

        verify(identityCollisionMapper).findVisibleMembers(31, List.of(key), 0, 2);
        verify(identityCollisionMapper).findVisibleMembers(
            31, List.of(key), 500_000, 101);
        verifyNoMoreInteractions(identityCollisionMapper);
        assertEquals(100, result.items().size());
        assertTrue(result.hasMore());
        assertEquals(500_100, result.nextAfterRecordId());
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
        verifyNoInteractions(identityCollisionMapper);
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

    private static IdentityCollisionGroupPageRow group(
            String recordType,
            String kind,
            String normalizedValue,
            int collisionSize,
            LocalDateTime rebuiltAt,
            long total,
            long rowNumber) {
        IdentityCollisionGroupPageRow row = new IdentityCollisionGroupPageRow();
        row.setRecordType(recordType);
        row.setKind(kind);
        row.setNormalizedValue(normalizedValue);
        row.setCollisionSize(collisionSize);
        row.setRebuiltAt(rebuiltAt);
        row.setTotal(total);
        row.setPageOrdinal(rowNumber);
        return row;
    }

    private static IdentityCollisionGroupPageRow sentinel(long total, long rowNumber) {
        IdentityCollisionGroupPageRow row = new IdentityCollisionGroupPageRow();
        row.setTotal(total);
        row.setPageOrdinal(rowNumber);
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
