package ooo.klae.connex.backend.services;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.ai.AiStandingApprovalControlAccess;
import ooo.klae.connex.backend.ai.AiStandingApprovalControlOperations;
import ooo.klae.connex.backend.ai.AiStandingApprovalPolicyRealtime;
import ooo.klae.connex.backend.dto.AiStandingApprovalPolicyDto;
import ooo.klae.connex.backend.dto.AiStandingApprovalPolicyRequest;
import ooo.klae.connex.backend.exceptions.ForbiddenException;

/** Resolves organization authority only through the authenticated active workspace. */
@Service
@RequiredArgsConstructor
public class AiStandingApprovalPolicyService {
    private final WorkspaceService workspaces;
    private final AiStandingApprovalControlAccess access;
    private final AiStandingApprovalControlOperations operations;
    private final AiStandingApprovalPolicyRealtime realtime;

    /** Returns configuration without entering a write callback or provisioning missing rows. */
    public AiStandingApprovalPolicyDto read(int workspaceId, int actorId) {
        int orgId = organization(workspaceId);
        return access.executeRead(() -> operations.read(workspaceId, orgId, actorId));
    }

    /** Commits administrator changes before publishing content-free invalidations. */
    public AiStandingApprovalPolicyDto replace(
            int workspaceId, int actorId, AiStandingApprovalPolicyRequest request) {
        int orgId = organization(workspaceId);
        var saved = access.execute(() -> operations.replace(workspaceId, orgId, actorId, request));
        realtime.publishAfterCommit(orgId);
        return saved;
    }

    private int organization(int workspaceId) {
        if (workspaces.getCurrentWorkspaceId() != workspaceId) {
            throw new ForbiddenException("Standing approval policy requires the active workspace");
        }
        return workspaces.getCurrentOrgId();
    }
}
