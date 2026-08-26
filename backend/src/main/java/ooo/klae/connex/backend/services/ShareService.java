package ooo.klae.connex.backend.services;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.dto.ShareDto;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.exceptions.ShareBlockedPrivacyHoldException;
import ooo.klae.connex.backend.mappers.PersonMapper;
import ooo.klae.connex.backend.mappers.ShareMapper;
import ooo.klae.connex.backend.notifications.NotificationChangePublisher;
import ooo.klae.connex.backend.tenant.Permission;

/**
 * Cross-workspace record sharing for companies, contacts, and pipelines. The
 * owning workspace shares a record it owns with another workspace the actor also
 * belongs to; the grantee gains read visibility. Requires the SHARE_MANAGE
 * permission in the owning workspace.
 */
@Service
@RequiredArgsConstructor
public class ShareService {

    private enum Type { COMPANY, PERSON, PIPELINE }

    private final ShareMapper shareMapper;
    private final PersonMapper personMapper;
    private final WorkspaceService workspaceService;
    private final AuthService authService;
    private final AuditService auditService;
    private final DuplicateDecisionLockService duplicateDecisionLockService;
    private final NotificationChangePublisher notificationChanges;

    public List<ShareDto> listShares(String typeRaw, int entityId) {
        Type type = parseType(typeRaw);
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        int actorId = authService.getCurrentUser().getId();
        workspaceService.requirePermission(workspaceId, actorId, Permission.SHARE_MANAGE);
        requireOwned(type, workspaceId, entityId);
        return switch (type) {
            case COMPANY -> shareMapper.listCompanyShares(workspaceId, entityId);
            case PERSON -> shareMapper.listPersonShares(workspaceId, entityId);
            case PIPELINE -> shareMapper.listPipelineShares(workspaceId, entityId);
        };
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void share(String typeRaw, int entityId, int targetWorkspaceId, boolean canEdit) {
        Type type = parseType(typeRaw);
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        int actorId = authService.getCurrentUser().getId();
        workspaceService.requirePermission(workspaceId, actorId, Permission.SHARE_MANAGE);
        if (type != Type.PIPELINE) {
            duplicateDecisionLockService.lockCurrentOrganizationWithMemberWorkspace(
                targetWorkspaceId);
        }
        requireOwned(type, workspaceId, entityId);
        if (type == Type.PERSON) {
            requirePersonProvisionAllowed(workspaceId, entityId);
        }
        if (targetWorkspaceId == workspaceId) {
            throw new BadRequestException("A record cannot be shared with its own workspace");
        }
        if (type == Type.PIPELINE) {
            workspaceService.requireMember(targetWorkspaceId, actorId);
            if (workspaceService.getOrgId(targetWorkspaceId)
                    != workspaceService.getOrgId(workspaceId)) {
                throw new ForbiddenException(
                    "A record cannot be shared across organizations");
            }
        }
        int granted = switch (type) {
            case COMPANY -> shareMapper.shareCompany(entityId, workspaceId, targetWorkspaceId, actorId, canEdit);
            case PERSON -> shareMapper.sharePerson(entityId, workspaceId, targetWorkspaceId, actorId, canEdit);
            case PIPELINE -> shareMapper.sharePipeline(entityId, workspaceId, targetWorkspaceId, actorId, canEdit);
        };
        if (granted == 0 && type == Type.PERSON) {
            requirePersonProvisionAllowed(workspaceId, entityId);
        }
        if (granted == 0 && !shareExists(type, entityId, workspaceId, targetWorkspaceId)) {
            throw new ForbiddenException("A record can only be shared by its owning workspace within its organization");
        }
        auditService.record("workspace.share", type.name().toLowerCase(), entityId, null,
                "Shared with workspace " + targetWorkspaceId, null);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void unshare(String typeRaw, int entityId, int targetWorkspaceId) {
        Type type = parseType(typeRaw);
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        int actorId = authService.getCurrentUser().getId();
        workspaceService.requirePermission(workspaceId, actorId, Permission.SHARE_MANAGE);
        if (type != Type.PIPELINE) {
            duplicateDecisionLockService.lockCurrentOrganizationWithWorkspace(
                targetWorkspaceId);
        }
        requireOwned(type, workspaceId, entityId);
        switch (type) {
            case COMPANY -> shareMapper.unshareCompany(entityId, workspaceId, targetWorkspaceId);
            case PERSON -> shareMapper.unsharePerson(entityId, workspaceId, targetWorkspaceId);
            case PIPELINE -> shareMapper.unsharePipeline(entityId, workspaceId, targetWorkspaceId);
        }
        auditService.record("workspace.unshare", type.name().toLowerCase(), entityId, null,
                "Stopped sharing with workspace " + targetWorkspaceId, null);
        if (type != Type.PIPELINE) {
            notificationChanges.publish(
                targetWorkspaceId, type.name().toLowerCase(), entityId);
        }
    }

    /**
     * Whether the grant row exists, distinguishing an idempotent re-grant (some
     * drivers report 0 affected rows for an unchanged upsert) from a grant the
     * SQL ceiling refused.
     */
    private boolean shareExists(Type type, int entityId, int workspaceId, int targetWorkspaceId) {
        return switch (type) {
            case COMPANY -> shareMapper.companyShareExists(entityId, workspaceId, targetWorkspaceId);
            case PERSON -> shareMapper.personShareExists(entityId, workspaceId, targetWorkspaceId);
            case PIPELINE -> shareMapper.pipelineShareExists(entityId, workspaceId, targetWorkspaceId);
        };
    }

    private void requireOwned(Type type, int workspaceId, int entityId) {
        boolean owned = switch (type) {
            case COMPANY -> shareMapper.ownsCompany(workspaceId, entityId);
            case PERSON -> shareMapper.ownsPerson(workspaceId, entityId);
            case PIPELINE -> shareMapper.ownsPipeline(workspaceId, entityId);
        };
        if (!owned) {
            throw new ResourceNotFoundException("Record not found in this workspace");
        }
    }

    private void requirePersonProvisionAllowed(int workspaceId, int entityId) {
        Person person = personMapper.getPersonById(workspaceId, entityId);
        if (person == null) {
            throw new ResourceNotFoundException("Record not found in this workspace");
        }
        if (person.getProvisionCeasedAt() != null) {
            throw new ShareBlockedPrivacyHoldException();
        }
    }

    private static Type parseType(String raw) {
        if (raw == null) {
            throw new BadRequestException("Share type is required");
        }
        try {
            return Type.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("Unknown share type: " + raw);
        }
    }
}
