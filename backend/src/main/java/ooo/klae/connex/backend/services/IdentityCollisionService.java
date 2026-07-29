package ooo.klae.connex.backend.services;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.dao.DataAccessException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import lombok.RequiredArgsConstructor;
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
 * Permissioned workspace report over visible canonical identity collisions.
 */
@Service
@RequiredArgsConstructor
public class IdentityCollisionService {

    private static final int MAX_MEMBERS_PER_GROUP = 20;
    private static final int FIRST_MEMBER_CURSOR = 0;
    private static final int MIN_COLLISION_MEMBERS = 2;
    private static final int MYSQL_MAX_EXECUTION_TIME_EXCEEDED = 3024;

    private final IdentityCollisionMapper identityCollisionMapper;
    private final WorkspaceService workspaceService;

    /**
     * Returns one group-level page of visible identity collisions. Each group carries only a
     * bounded first page of its members plus a truncation flag, so a single response can never
     * materialize a workspace-sized member list; the remainder is reachable through
     * {@link #listMembers(IdentityCollisionMemberQuery)}. The group read and the member read are
     * pinned to one repeatable-read snapshot, which is what makes a group's {@code collisionSize}
     * and its member sample describe the same committed state.
     * @param query validated collision filters and pagination
     * @return grouped collision page
     */
    @Transactional(
        readOnly = true,
        isolation = Isolation.REPEATABLE_READ,
        timeout = 4)
    @RequirePermission(Permission.REPORT_READ)
    public PageResponse<IdentityCollisionDto> list(IdentityCollisionQuery query) {
        Objects.requireNonNull(query, "query");
        validateCompatibility(query.getRecordType(), query.getKind());
        requireRepeatableReadTransaction();
        try {
            return listWithinDeadline(query);
        } catch (QueryTimeoutException | TransactionTimedOutException exception) {
            throw new IdentityCollisionReportTimeoutException(exception);
        } catch (DataAccessException exception) {
            if (containsDatabaseDeadline(exception)) {
                throw new IdentityCollisionReportTimeoutException(exception);
            }
            throw exception;
        }
    }

    private PageResponse<IdentityCollisionDto> listWithinDeadline(
            IdentityCollisionQuery query) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        long offset = Math.multiplyExact((long) query.getPage() - 1L, query.getSize());
        VisibleGroupPage page = findVisibleGroupPage(workspaceId, query, offset);
        List<IdentityCollisionGroupPageRow> groups = page.groups();
        long total = page.total();
        if (groups.isEmpty()) {
            return new PageResponse<>(List.of(), total);
        }
        List<IdentityCollisionMemberRow> memberRows = identityCollisionMapper.findVisibleMembers(
            workspaceId,
            groups.stream().map(IdentityCollisionService::keyOf).toList(),
            FIRST_MEMBER_CURSOR,
            MAX_MEMBERS_PER_GROUP);
        Map<IdentityCollisionGroupKey, List<IdentityCollisionMemberDto>> members =
            new LinkedHashMap<>();
        for (IdentityCollisionMemberRow row : memberRows) {
            IdentityCollisionMemberRow required = Objects.requireNonNull(row, "collision member");
            List<IdentityCollisionMemberDto> groupMembers = members.computeIfAbsent(
                new IdentityCollisionGroupKey(
                    Objects.requireNonNull(required.getRecordType(), "collision record type"),
                    Objects.requireNonNull(required.getKind(), "collision kind"),
                    Objects.requireNonNull(required.getNormalizedValue(), "collision value")),
                ignored -> new ArrayList<>());
            groupMembers.add(toMemberDto(required));
        }
        List<IdentityCollisionDto> items = groups.stream()
            .map(group -> toDto(group, members))
            .toList();
        return new PageResponse<>(items, total);
    }

    /**
     * Returns one keyset page of a single collision group's visible members, so a group larger
     * than the report's per-group bound stays fully reachable. Members are ordered by record ID;
     * pass {@code nextAfterRecordId} back as {@code afterRecordId} to continue. Every invocation
     * uses a fresh current-visibility repeatable-read snapshot and reapplies tenant, permission,
     * and processing restrictions. Continuation is weakly consistent across requests: it never
     * retains or replays members hidden from the current request, but concurrent identity or
     * restriction changes may cause affected visible rows to be skipped or repeated.
     * @param query validated group identity and member cursor
     * @return bounded member page and nullable continuation cursor
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    @RequirePermission(Permission.REPORT_READ)
    public IdentityCollisionMemberPageDto listMembers(IdentityCollisionMemberQuery query) {
        Objects.requireNonNull(query, "query");
        String recordType = requireGroupFilter(query.getRecordType(), "record type");
        String kind = requireGroupFilter(query.getKind(), "kind");
        String normalizedValue = requireGroupFilter(query.getNormalizedValue(), "value");
        if (!"person".equals(recordType) && !"company".equals(recordType)) {
            throw new BadRequestException("Identity collision members require person or company");
        }
        validateCompatibility(recordType, kind);
        requireRepeatableReadTransaction();
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        IdentityCollisionGroupKey group =
            new IdentityCollisionGroupKey(recordType, kind, normalizedValue);
        int afterRecordId = query.getAfterRecordId();
        if (afterRecordId > FIRST_MEMBER_CURSOR) {
            List<IdentityCollisionMemberRow> visibleGroupProbe =
                identityCollisionMapper.findVisibleMembers(
                    workspaceId,
                    List.of(group),
                    FIRST_MEMBER_CURSOR,
                    MIN_COLLISION_MEMBERS);
            if (visibleGroupProbe.size() < MIN_COLLISION_MEMBERS) {
                return exhaustedMemberPage();
            }
        }
        List<IdentityCollisionMemberRow> memberRows = identityCollisionMapper.findVisibleMembers(
            workspaceId,
            List.of(group),
            afterRecordId,
            Math.addExact(query.getSize(), 1));
        if (afterRecordId == FIRST_MEMBER_CURSOR
                && memberRows.size() < MIN_COLLISION_MEMBERS) {
            return exhaustedMemberPage();
        }
        boolean hasMore = memberRows.size() > query.getSize();
        List<IdentityCollisionMemberDto> items = memberRows.stream()
            .limit(query.getSize())
            .map(row -> toMemberDto(Objects.requireNonNull(row, "collision member")))
            .toList();
        Integer nextAfterRecordId = hasMore
            ? items.getLast().recordId()
            : null;
        return new IdentityCollisionMemberPageDto(items, hasMore, nextAfterRecordId);
    }

    private IdentityCollisionDto toDto(
            IdentityCollisionGroupPageRow group,
            Map<IdentityCollisionGroupKey, List<IdentityCollisionMemberDto>> members) {
        IdentityCollisionGroupPageRow required = Objects.requireNonNull(
            group, "collision group");
        IdentityCollisionGroupKey key = keyOf(required);
        List<IdentityCollisionMemberDto> groupMembers =
            List.copyOf(members.getOrDefault(key, List.of()));
        int collisionSize = Objects.requireNonNull(
            required.getCollisionSize(), "collision size");
        int expectedMembers = Math.min(collisionSize, MAX_MEMBERS_PER_GROUP);
        if (groupMembers.size() != expectedMembers) {
            throw new IllegalStateException(
                "Identity collision group changed during its read transaction");
        }
        return new IdentityCollisionDto(
            key.recordType(),
            key.kind(),
            key.normalizedValue(),
            collisionSize,
            Objects.requireNonNull(required.getRebuiltAt(), "collision rebuild timestamp"),
            groupMembers,
            collisionSize > MAX_MEMBERS_PER_GROUP);
    }

    private static IdentityCollisionGroupKey keyOf(IdentityCollisionGroupPageRow group) {
        IdentityCollisionGroupPageRow required = Objects.requireNonNull(
            group, "collision group");
        return new IdentityCollisionGroupKey(
            Objects.requireNonNull(required.getRecordType(), "collision record type"),
            Objects.requireNonNull(required.getKind(), "collision kind"),
            Objects.requireNonNull(required.getNormalizedValue(), "collision value"));
    }

    private static IdentityCollisionMemberDto toMemberDto(IdentityCollisionMemberRow row) {
        return new IdentityCollisionMemberDto(
            row.getRecordId(),
            Objects.requireNonNull(row.getRecordName(), "collision record name"));
    }

    private static IdentityCollisionMemberPageDto exhaustedMemberPage() {
        return new IdentityCollisionMemberPageDto(List.of(), false, null);
    }

    private VisibleGroupPage findVisibleGroupPage(
            int workspaceId,
            IdentityCollisionQuery query,
            long offset) {
        List<IdentityCollisionGroupPageRow> rows =
            identityCollisionMapper.findVisibleGroupPage(
                workspaceId,
                query.getRecordType(),
                query.getKind(),
                query.getSize(),
                offset);
        return requireVisibleGroupPage(rows, query.getSize(), offset);
    }

    private static VisibleGroupPage requireVisibleGroupPage(
            List<IdentityCollisionGroupPageRow> rows,
            int limit,
            long offset) {
        if (rows == null || rows.isEmpty()) {
            throw invalidGroupPage();
        }
        Long total = null;
        List<IdentityCollisionGroupPageRow> groups = new ArrayList<>();
        for (int index = 0; index < rows.size(); index++) {
            IdentityCollisionGroupPageRow row =
                Objects.requireNonNull(rows.get(index), "collision group page row");
            long rowTotal = requireNonNegative(row.getTotal());
            if (total != null && total.longValue() != rowTotal) {
                throw invalidGroupPage();
            }
            total = rowTotal;
            if (isSentinel(row)) {
                if (rows.size() != 1
                        || row.getCollisionSize() != null
                        || row.getRebuiltAt() != null
                        || rowTotal > offset
                        || requirePositive(row.getPageOrdinal()) != rowTotal + 1L) {
                    throw invalidGroupPage();
                }
                return new VisibleGroupPage(List.of(), rowTotal);
            }
            if (row.getRecordType() == null
                    || row.getKind() == null
                    || row.getNormalizedValue() == null
                    || row.getCollisionSize() == null
                    || row.getCollisionSize() < MIN_COLLISION_MEMBERS
                    || row.getRebuiltAt() == null
                    || rowTotal <= offset
                    || requirePositive(row.getPageOrdinal()) != offset + index + 1L) {
                throw invalidGroupPage();
            }
            groups.add(row);
        }
        long requiredRows = Math.min((long) limit, total - offset);
        if (requiredRows <= 0L || groups.size() != requiredRows) {
            throw invalidGroupPage();
        }
        return new VisibleGroupPage(List.copyOf(groups), total);
    }

    private static boolean isSentinel(IdentityCollisionGroupPageRow row) {
        boolean anyNullKey = row.getRecordType() == null
            || row.getKind() == null
            || row.getNormalizedValue() == null;
        boolean allNullKeys = row.getRecordType() == null
            && row.getKind() == null
            && row.getNormalizedValue() == null;
        if (anyNullKey != allNullKeys) {
            throw invalidGroupPage();
        }
        return allNullKeys;
    }

    private static long requireNonNegative(Long value) {
        if (value == null || value < 0L) {
            throw invalidGroupPage();
        }
        return value;
    }

    private static long requirePositive(Long value) {
        if (value == null || value <= 0L) {
            throw invalidGroupPage();
        }
        return value;
    }

    private static IllegalStateException invalidGroupPage() {
        return new IllegalStateException(
            "Identity collision group page metadata is inconsistent");
    }

    private static boolean containsDatabaseDeadline(DataAccessException exception) {
        Throwable current = exception.getCause();
        while (current != null) {
            if (current instanceof SQLTimeoutException
                    || current instanceof SQLException sqlException
                        && sqlException.getErrorCode()
                            == MYSQL_MAX_EXECUTION_TIME_EXCEEDED) {
                return true;
            }
            current = current.getCause() == current ? null : current.getCause();
        }
        return false;
    }

    private static String requireGroupFilter(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new BadRequestException("Identity collision members require a " + label);
        }
        return value;
    }

    private static void requireRepeatableReadTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                "Identity collision reads require an active REPEATABLE_READ transaction");
        }
        Integer isolation = TransactionSynchronizationManager.getCurrentTransactionIsolationLevel();
        if (isolation == null || isolation != Connection.TRANSACTION_REPEATABLE_READ) {
            throw new IllegalStateException(
                "Identity collision reads require REPEATABLE_READ isolation");
        }
    }

    private static void validateCompatibility(String recordType, String kind) {
        if ("person".equals(recordType) && "domain".equals(kind)) {
            throw new BadRequestException("Person identity collisions do not support domain");
        }
        if ("company".equals(recordType) && "email".equals(kind)) {
            throw new BadRequestException("Company identity collisions do not support email");
        }
    }

    private record VisibleGroupPage(
        List<IdentityCollisionGroupPageRow> groups,
        long total) {
    }
}
