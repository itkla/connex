package ooo.klae.connex.backend.services;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.dto.IdentityCollisionDto;
import ooo.klae.connex.backend.dto.IdentityCollisionGroupRow;
import ooo.klae.connex.backend.dto.IdentityCollisionMemberDto;
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

    private final IdentityCollisionMapper identityCollisionMapper;
    private final WorkspaceService workspaceService;

    /**
     * Returns one group-level page of visible identity collisions.
     * @param query validated collision filters and pagination
     * @return grouped collision page
     */
    @Transactional(readOnly = true)
    @RequirePermission(Permission.REPORT_READ)
    public PageResponse<IdentityCollisionDto> list(IdentityCollisionQuery query) {
        Objects.requireNonNull(query, "query");
        validateCompatibility(query);
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
        List<IdentityCollisionMemberRow> memberRows =
            identityCollisionMapper.findVisibleMembers(workspaceId, groups);
        Map<CollisionKey, List<IdentityCollisionMemberDto>> members = new LinkedHashMap<>();
        for (IdentityCollisionMemberRow row : memberRows) {
            IdentityCollisionMemberRow required = Objects.requireNonNull(row, "collision member");
            CollisionKey key = new CollisionKey(
                Objects.requireNonNull(required.getRecordType(), "collision record type"),
                Objects.requireNonNull(required.getKind(), "collision kind"),
                Objects.requireNonNull(required.getNormalizedValue(), "collision value"));
            members.computeIfAbsent(key, ignored -> new ArrayList<>())
                .add(new IdentityCollisionMemberDto(
                    required.getRecordId(),
                    Objects.requireNonNull(required.getRecordName(), "collision record name")));
        }
        List<IdentityCollisionDto> items = groups.stream()
            .map(group -> toDto(group, members))
            .toList();
        return new PageResponse<>(items, total);
    }

    private IdentityCollisionDto toDto(
            IdentityCollisionGroupRow group,
            Map<CollisionKey, List<IdentityCollisionMemberDto>> members) {
        IdentityCollisionGroupRow required = Objects.requireNonNull(group, "collision group");
        CollisionKey key = new CollisionKey(
            Objects.requireNonNull(required.getRecordType(), "collision record type"),
            Objects.requireNonNull(required.getKind(), "collision kind"),
            Objects.requireNonNull(required.getNormalizedValue(), "collision value"));
        List<IdentityCollisionMemberDto> groupMembers =
            List.copyOf(members.getOrDefault(key, List.of()));
        if (groupMembers.size() != required.getCollisionSize()) {
            throw new IllegalStateException("Identity collision group changed during its read transaction");
        }
        return new IdentityCollisionDto(
            key.recordType(),
            key.kind(),
            key.normalizedValue(),
            required.getCollisionSize(),
            Objects.requireNonNull(required.getRebuiltAt(), "collision rebuild timestamp"),
            groupMembers);
    }

    private void validateCompatibility(IdentityCollisionQuery query) {
        if ("person".equals(query.getRecordType()) && "domain".equals(query.getKind())) {
            throw new BadRequestException("Person identity collisions do not support domain");
        }
        if ("company".equals(query.getRecordType()) && "email".equals(query.getKind())) {
            throw new BadRequestException("Company identity collisions do not support email");
        }
    }

    private record CollisionKey(String recordType, String kind, String normalizedValue) {
    }
}
