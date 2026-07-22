package ooo.klae.connex.backend.services;

import java.util.List;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.dto.ShareDto;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.ShareMapper;
import ooo.klae.connex.backend.services.ShareService.Type;

/** Executes record ownership and share-table work inside the routed tenant catalog. */
@Component
@RequiredArgsConstructor
public class ShareTenantOperations {
    private final ShareMapper shareMapper;

    /** Requires an owned, currently shareable record. */
    @Transactional(readOnly = true)
    public void requireShareableOwned(Type type, int workspaceId, int entityId) {
        requireOwned(type, workspaceId, entityId);
        if (type == Type.PERSON) {
            requirePersonProvisionAllowed(workspaceId, entityId);
        }
    }

    /** Loads owner-anchored tenant share rows. */
    @Transactional(readOnly = true)
    public List<ShareDto> list(Type type, int workspaceId, int entityId) {
        requireOwned(type, workspaceId, entityId);
        return switch (type) {
            case COMPANY -> shareMapper.listCompanyShares(workspaceId, entityId);
            case PERSON -> shareMapper.listPersonShares(workspaceId, entityId);
            case PIPELINE -> shareMapper.listPipelineShares(workspaceId, entityId);
        };
    }

    /** Grants a share through an owner-organization workspace allowlist. */
    @Transactional
    public void share(Type type, int entityId, int workspaceId, int targetWorkspaceId,
            List<Integer> workspaceIds, int actorId, boolean canEdit) {
        if (!workspaceIds.contains(workspaceId) || !workspaceIds.contains(targetWorkspaceId)) {
            throw new ForbiddenException(
                "A record can only be shared by its owning workspace within its organization");
        }
        int granted = switch (type) {
            case COMPANY -> shareMapper.shareCompany(
                entityId, workspaceId, targetWorkspaceId, actorId, canEdit, workspaceIds);
            case PERSON -> shareMapper.sharePerson(
                entityId, workspaceId, targetWorkspaceId, actorId, canEdit, workspaceIds);
            case PIPELINE -> shareMapper.sharePipeline(
                entityId, workspaceId, targetWorkspaceId, actorId, canEdit, workspaceIds);
        };
        if (granted == 0 && type == Type.PERSON) {
            requirePersonProvisionAllowed(workspaceId, entityId);
        }
        if (granted == 0 && !shareExists(type, entityId, workspaceId, targetWorkspaceId)) {
            throw new ForbiddenException(
                "A record can only be shared by its owning workspace within its organization");
        }
    }

    /** Revokes an owner-anchored share. */
    @Transactional
    public void unshare(Type type, int entityId, int workspaceId, int targetWorkspaceId) {
        requireOwned(type, workspaceId, entityId);
        switch (type) {
            case COMPANY -> shareMapper.unshareCompany(entityId, workspaceId, targetWorkspaceId);
            case PERSON -> shareMapper.unsharePerson(entityId, workspaceId, targetWorkspaceId);
            case PIPELINE -> shareMapper.unsharePipeline(entityId, workspaceId, targetWorkspaceId);
        }
    }

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
        Person person = shareMapper.getOwnedPersonProvisionState(workspaceId, entityId);
        if (person == null) {
            throw new ResourceNotFoundException("Record not found in this workspace");
        }
        if (person.getProvisionCeasedAt() != null) {
            throw new BadRequestException("Third-party provision has been ceased for this contact");
        }
    }
}
