package ooo.klae.connex.backend.services;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import lombok.RequiredArgsConstructor;
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
 * Permissioned workspace report over visible canonical identity collisions.
 */
@Service
@RequiredArgsConstructor
public class IdentityCollisionService {

    private static final int MAX_MEMBERS_PER_GROUP = 20;
    private static final int FIRST_MEMBER_CURSOR = 0;

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
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    @RequirePermission(Permission.REPORT_READ)
    public PageResponse<IdentityCollisionDto> list(IdentityCollisionQuery query) {
        Objects.requireNonNull(query, "query");
        validateCompatibility(query.getRecordType(), query.getKind());
        requireRepeatableReadTransaction();
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        long offset = Math.multiplyExact((long) query.getPage() - 1L, query.getSize());
        List<IdentityCollisionGroupRow> groups = identityCollisionMapper.findVisibleGroups(
            workspaceId,
            query.getRecordType(),
            query.getKind(),
            query.getSize(),
            offset);
        long total = identityCollisionMapper.countVisibleGroups(
            workspaceId,
            query.getRecordType(),
            query.getKind());
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
     * pass the last returned ID back as {@code afterRecordId} to continue.
     * @param query validated group identity and member cursor
     * @return member page whose total is the group's currently visible size
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    @RequirePermission(Permission.REPORT_READ)
    public PageResponse<IdentityCollisionMemberDto> listMembers(IdentityCollisionMemberQuery query) {
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
        long total = identityCollisionMapper.countVisibleGroupMembers(
            workspaceId, recordType, kind, normalizedValue);
        if (total < 2L) {
            return new PageResponse<>(List.of(), 0L);
        }
        List<IdentityCollisionMemberRow> memberRows = identityCollisionMapper.findVisibleMembers(
            workspaceId,
            List.of(new IdentityCollisionGroupKey(recordType, kind, normalizedValue)),
            query.getAfterRecordId(),
            query.getSize());
        List<IdentityCollisionMemberDto> items = memberRows.stream()
            .map(row -> toMemberDto(Objects.requireNonNull(row, "collision member")))
            .toList();
        return new PageResponse<>(items, total);
    }

    private IdentityCollisionDto toDto(
            IdentityCollisionGroupRow group,
            Map<IdentityCollisionGroupKey, List<IdentityCollisionMemberDto>> members) {
        IdentityCollisionGroupRow required = Objects.requireNonNull(group, "collision group");
        IdentityCollisionGroupKey key = keyOf(required);
        List<IdentityCollisionMemberDto> groupMembers =
            List.copyOf(members.getOrDefault(key, List.of()));
        int collisionSize = required.getCollisionSize();
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

    private static IdentityCollisionGroupKey keyOf(IdentityCollisionGroupRow group) {
        IdentityCollisionGroupRow required = Objects.requireNonNull(group, "collision group");
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

    private static String requireGroupFilter(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new BadRequestException("Identity collision members require a " + label);
        }
        return value;
    }

    private static void requireRepeatableReadTransaction() {
        Integer isolation = TransactionSynchronizationManager.getCurrentTransactionIsolationLevel();
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && (isolation == null || isolation != Connection.TRANSACTION_REPEATABLE_READ)) {
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
}
