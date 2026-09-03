package ooo.klae.connex.backend.services;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import ooo.klae.connex.backend.beans.DisqualificationReason;
import ooo.klae.connex.backend.beans.PersonDisqualificationReason;
import ooo.klae.connex.backend.dto.DisqualificationReasonDto;
import ooo.klae.connex.backend.dto.DisqualificationReasonRequest;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.DuplicateResourceException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.DisqualificationReasonMapper;
import ooo.klae.connex.backend.tenant.Permission;
import ooo.klae.connex.backend.tenant.RequirePermission;

/** Maintains and resolves one workspace's disqualification vocabulary (#559). */
@Service
@RequiredArgsConstructor
public class DisqualificationReasonService {
    private final DisqualificationReasonMapper reasonMapper;
    private final WorkspaceService workspaceService;
    private final AuditService auditService;

    /** Active reasons in configured order, with built-ins synthesized for an untouched workspace. */
    public List<DisqualificationReasonDto> getActive() {
        return resolved(workspaceService.getCurrentWorkspaceId()).stream()
            .filter(reason -> reason.archivedAt() == null)
            .toList();
    }

    /** All reasons, including archived entries, for configuration and historical resolution. */
    public List<DisqualificationReasonDto> getAll() {
        return resolved(workspaceService.getCurrentWorkspaceId());
    }

    /** Adds a workspace-authored reason after materializing the built-in vocabulary. */
    @Transactional
    @RequirePermission(Permission.WORKSPACE_SETTINGS)
    public DisqualificationReasonDto create(DisqualificationReasonRequest request) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        WorkspaceService.LockedPermissionSnapshot authorization =
            lockSettingsPermission(workspaceId);
        materializeBuiltIns(workspaceId);
        DisqualificationReason reason = validated(request, new DisqualificationReason(), true);
        reason.setWorkspaceId(workspaceId);
        if (reasonMapper.getByCodeForUpdate(workspaceId, reason.getCode()) != null) {
            throw duplicateCode();
        }
        authorization.revalidate();
        try {
            reasonMapper.insert(reason);
        } catch (DuplicateKeyException exception) {
            throw duplicateCode();
        }
        auditService.record("disqualification.reason.create", "disqualification_reason",
            reason.getId(), auditTarget(reason), "Added disqualification reason " + reason.getCode(),
            Map.of("code", reason.getCode(), "requiresNote", reason.isRequiresNote()));
        return DisqualificationReasonDto.from(reason);
    }

    /** Relabels, reorders, or changes the note requirement without changing the stored code. */
    @Transactional
    @RequirePermission(Permission.WORKSPACE_SETTINGS)
    public DisqualificationReasonDto update(int id, DisqualificationReasonRequest request) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        String syntheticCode = syntheticCode(id);
        WorkspaceService.LockedPermissionSnapshot authorization =
            lockSettingsPermission(workspaceId);
        materializeBuiltIns(workspaceId);
        DisqualificationReason before = syntheticCode == null
            ? requireForUpdate(workspaceId, id)
            : requireByCodeForUpdate(workspaceId, syntheticCode);
        DisqualificationReason updated = validated(request, new DisqualificationReason(), false);
        if (!before.getCode().equals(updated.getCode())) {
            throw new BadRequestException(
                "A disqualification reason code cannot change because lifecycle history uses it");
        }
        if (!before.isBuiltIn() && updated.getLabel() == null) {
            throw new BadRequestException("A custom disqualification reason needs a label");
        }
        updated.setId(before.getId());
        updated.setWorkspaceId(workspaceId);
        updated.setBuiltIn(before.isBuiltIn());
        authorization.revalidate();
        reasonMapper.update(updated);
        auditService.record("disqualification.reason.update", "disqualification_reason",
            before.getId(), auditTarget(updated),
            "Updated disqualification reason " + updated.getCode(),
            safeChanges(before, updated));
        return DisqualificationReasonDto.from(require(workspaceId, before.getId()));
    }

    /** Archives a reason for new disqualifications while preserving historical resolution. */
    @Transactional
    @RequirePermission(Permission.WORKSPACE_SETTINGS)
    public void archive(int id) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        String syntheticCode = syntheticCode(id);
        WorkspaceService.LockedPermissionSnapshot authorization =
            lockSettingsPermission(workspaceId);
        materializeBuiltIns(workspaceId);
        DisqualificationReason before = syntheticCode == null
            ? requireForUpdate(workspaceId, id)
            : requireByCodeForUpdate(workspaceId, syntheticCode);
        if (before.getArchivedAt() != null) {
            throw new BadRequestException("That disqualification reason is already archived");
        }
        authorization.revalidate();
        if (reasonMapper.archive(workspaceId, before.getId()) == 0) {
            throw new BadRequestException("That disqualification reason is already archived");
        }
        auditService.record("disqualification.reason.archive", "disqualification_reason",
            before.getId(), auditTarget(before),
            "Archived disqualification reason " + before.getCode(), Map.of("code", before.getCode()));
    }

    /** Restores an archived reason to the choices offered for new disqualifications. */
    @Transactional
    @RequirePermission(Permission.WORKSPACE_SETTINGS)
    public void restore(int id) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        WorkspaceService.LockedPermissionSnapshot authorization =
            lockSettingsPermission(workspaceId);
        materializeBuiltIns(workspaceId);
        DisqualificationReason before = requireForUpdate(workspaceId, id);
        if (before.getArchivedAt() == null) {
            throw new BadRequestException("That disqualification reason is not archived");
        }
        authorization.revalidate();
        if (reasonMapper.restore(workspaceId, id) == 0) {
            throw new BadRequestException("That disqualification reason is not archived");
        }
        auditService.record("disqualification.reason.restore", "disqualification_reason",
            id, auditTarget(before),
            "Restored disqualification reason " + before.getCode(), Map.of("code", before.getCode()));
    }

    List<DisqualificationReasonDto> resolved(int workspaceId) {
        List<DisqualificationReason> stored = reasonMapper.getAll(workspaceId);
        if (!stored.isEmpty()) {
            return stored.stream().map(DisqualificationReasonDto::from).toList();
        }
        return java.util.stream.IntStream.range(0, PersonDisqualificationReason.BUILT_INS.size())
            .mapToObj(index -> {
                PersonDisqualificationReason.BuiltIn builtIn =
                    PersonDisqualificationReason.BUILT_INS.get(index);
                return new DisqualificationReasonDto(
                    -index - 1,
                    builtIn.code(),
                    null,
                    builtIn.requiresNote(),
                    index,
                    true,
                    null);
            })
            .toList();
    }

    DisqualificationReasonDto resolve(int workspaceId, String code) {
        if (!PersonDisqualificationReason.isCanonicalCode(code)) {
            return null;
        }
        return resolved(workspaceId).stream()
            .filter(reason -> reason.code().equals(code))
            .findFirst()
            .orElse(null);
    }

    /**
     * Locks the workspace vocabulary and a persisted reason, or synthesizes a built-in while the
     * workspace is still proven row-less.
     */
    DisqualificationReasonDto lockForLifecycle(int workspaceId, String code) {
        if (!PersonDisqualificationReason.isCanonicalCode(code)) {
            return null;
        }
        lockLifecyclePermissionAndMaterializationMutex(workspaceId);
        DisqualificationReason reason = reasonMapper.getByCodeForUpdate(workspaceId, code);
        if (reason != null) {
            return reason.getCode().equals(code) ? DisqualificationReasonDto.from(reason) : null;
        }
        if (!reasonMapper.getAll(workspaceId).isEmpty()) {
            return null;
        }
        return syntheticBuiltIn(code);
    }

    private static DisqualificationReasonDto syntheticBuiltIn(String code) {
        for (int index = 0; index < PersonDisqualificationReason.BUILT_INS.size(); index++) {
            PersonDisqualificationReason.BuiltIn builtIn =
                PersonDisqualificationReason.BUILT_INS.get(index);
            if (builtIn.code().equals(code)) {
                return new DisqualificationReasonDto(
                    -index - 1,
                    builtIn.code(),
                    null,
                    builtIn.requiresNote(),
                    index,
                    true,
                    null);
            }
        }
        return null;
    }

    private void materializeBuiltIns(int workspaceId) {
        for (int index = 0; index < PersonDisqualificationReason.BUILT_INS.size(); index++) {
            PersonDisqualificationReason.BuiltIn builtIn =
                PersonDisqualificationReason.BUILT_INS.get(index);
            reasonMapper.insertBuiltIn(
                workspaceId, builtIn.code(), builtIn.requiresNote(), index);
        }
    }

    private DisqualificationReason require(int workspaceId, int id) {
        DisqualificationReason reason = reasonMapper.getById(workspaceId, id);
        if (reason == null) {
            throw new ResourceNotFoundException("Disqualification reason not found with id: " + id);
        }
        return reason;
    }

    private DisqualificationReason requireForUpdate(int workspaceId, int id) {
        DisqualificationReason reason = reasonMapper.getByIdForUpdate(workspaceId, id);
        if (reason == null) {
            throw new ResourceNotFoundException("Disqualification reason not found with id: " + id);
        }
        return reason;
    }

    private DisqualificationReason requireByCodeForUpdate(int workspaceId, String code) {
        DisqualificationReason reason = reasonMapper.getByCodeForUpdate(workspaceId, code);
        if (reason == null) {
            throw new ResourceNotFoundException("Disqualification reason not found");
        }
        return reason;
    }

    private WorkspaceService.LockedPermissionSnapshot lockSettingsPermission(int workspaceId) {
        return workspaceService.lockAndRequirePermissionsWithWorkspaceMutex(
            workspaceId,
            Map.of(workspaceService.getCurrentUserId(), Set.of(Permission.WORKSPACE_SETTINGS)));
    }

    private void lockLifecyclePermissionAndMaterializationMutex(int workspaceId) {
        workspaceService.lockAndRequirePermissionsWithWorkspaceMutex(
            workspaceId,
            Map.of(workspaceService.getCurrentUserId(), Set.of(Permission.PERSON_UPDATE)));
    }

    private static Map<String, Object> safeChanges(
            DisqualificationReason before,
            DisqualificationReason after) {
        Map<String, Object> changes = new java.util.LinkedHashMap<>();
        changes.put("code", after.getCode());
        if (!java.util.Objects.equals(before.getLabel(), after.getLabel())) {
            changes.put("labelChanged", true);
        }
        if (before.isRequiresNote() != after.isRequiresNote()) {
            changes.put("requiresNote", Map.of(
                "from", before.isRequiresNote(), "to", after.isRequiresNote()));
        }
        if (before.getPosition() != after.getPosition()) {
            changes.put("position", Map.of(
                "from", before.getPosition(), "to", after.getPosition()));
        }
        return changes;
    }

    private static DuplicateResourceException duplicateCode() {
        return new DuplicateResourceException(
            "code", "A disqualification reason already uses that code");
    }

    private static String auditTarget(DisqualificationReason reason) {
        return "reason " + reason.getCode();
    }

    private static DisqualificationReason validated(
            DisqualificationReasonRequest request,
            DisqualificationReason reason,
            boolean custom) {
        if (request == null) {
            throw new BadRequestException("A disqualification reason is required");
        }
        String code = request.getCode();
        if (!PersonDisqualificationReason.isCanonicalCode(code)) {
            throw new BadRequestException(
                "A reason code must be canonical uppercase ASCII and contain 2 to 32 letters, "
                    + "numbers, or underscores");
        }
        String label = trimToNull(request.getLabel());
        if (custom && label == null) {
            throw new BadRequestException("A custom disqualification reason needs a label");
        }
        if (label != null && label.length() > 200) {
            throw new BadRequestException("A disqualification reason label must use 200 characters or fewer");
        }
        int position = request.getPosition() == null ? 0 : request.getPosition();
        if (position < 0) {
            throw new BadRequestException("A disqualification reason position cannot be negative");
        }
        reason.setCode(code);
        reason.setLabel(label);
        reason.setRequiresNote(Boolean.TRUE.equals(request.getRequiresNote()));
        reason.setPosition(position);
        return reason;
    }

    private static String syntheticCode(int id) {
        if (id >= 0) {
            return null;
        }
        int index = -id - 1;
        if (index < 0 || index >= PersonDisqualificationReason.BUILT_INS.size()) {
            throw new ResourceNotFoundException("Disqualification reason not found");
        }
        return PersonDisqualificationReason.BUILT_INS.get(index).code();
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
