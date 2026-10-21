package ooo.klae.connex.backend.ai;

import java.util.Map;
import java.util.Objects;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.beans.AiStandingApprovalPolicy;
import ooo.klae.connex.backend.dto.AiStandingApprovalPolicyDto;
import ooo.klae.connex.backend.dto.AiStandingApprovalPolicyRequest;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.mappers.AiStandingApprovalPolicyMapper;
import ooo.klae.connex.backend.mappers.OrganizationMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.services.AuditService;
import ooo.klae.connex.backend.services.OrgMemberService;

/** Control transactions retain administrator parents before the policy and strict audit append. */
@Service
@RequiredArgsConstructor
public class AiStandingApprovalControlOperations {
    private final AiStandingApprovalPolicyMapper policies;
    private final UserMapper users;
    private final WorkspaceMapper workspaces;
    private final OrganizationMapper organizations;
    private final OrgMemberService members;
    private final AuditService audit;

    @Value("${connex.ai.standing-approvals.enabled:false}")
    private boolean authoringEnabled;

    /** Pure administrative read; missing rows remain unavailable until provisioning completes. */
    @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
    public AiStandingApprovalPolicyDto read(int workspaceId, int orgId, int actorId) {
        if (!Objects.equals(workspaces.getOrgId(workspaceId), orgId)
                || !workspaces.isMember(workspaceId, actorId)
                || users.isAccountDeletionReserved(actorId)) {
            throw administratorRequired();
        }
        members.requireOrgAdmin(orgId, actorId);
        return dto(policies.get(orgId));
    }

    /** Replaces one exact version, with every disabled replacement advancing the kill fence. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public AiStandingApprovalPolicyDto replace(
            int workspaceId, int orgId, int actorId, AiStandingApprovalPolicyRequest request) {
        int mask = AiStandingApprovalCeiling.validate(request);
        if (!Boolean.FALSE.equals(users.isAccountDeletionReservedForShare(actorId))
                || !Objects.equals(workspaces.lockActiveWorkspaceForShare(workspaceId), orgId)
                || workspaces.lockActiveMembership(workspaceId, actorId) == null
                || organizations.lockActiveByIdForShare(orgId) == null) {
            throw administratorRequired();
        }
        members.requireOrgAdminWithOrganizationRetained(orgId, actorId);
        AiStandingApprovalPolicy policy = policies.lockForUpdate(orgId);
        if (policy == null) {
            throw new ConflictException("Standing approval policy is not provisioned");
        }
        if (policy.getStateVersion() != AiStandingApprovalCeiling.version(request.expectedStateVersion())) {
            throw new ConflictException("Standing approval settings changed; refresh and retry");
        }
        policy.setStateVersion(AiStandingApprovalCeiling.nextEpoch(policy.getStateVersion()));
        if (!request.enabled()) {
            policy.setKillEpoch(AiStandingApprovalCeiling.nextEpoch(policy.getKillEpoch()));
        }
        policy.setEnabled(request.enabled());
        policy.setAllowedToolMask(mask);
        policy.setMaximumDailyCap(request.maximumDailyCap());
        policy.setMaximumPerRunCap(request.maximumPerRunCap());
        if (policies.replace(policy) != 1) {
            throw new ConflictException("Standing approval settings changed; refresh and retry");
        }
        audit.recordStrictScoped("org.ai_standing_approval_policy.save", "organization", orgId,
                null, orgId, "Organization " + orgId, "Updated standing approval policy",
                Map.of("enabled", policy.isEnabled(), "allowedTools", AiStandingApprovalCeiling.tools(mask),
                        "maximumDailyCap", policy.getMaximumDailyCap(),
                        "maximumPerRunCap", policy.getMaximumPerRunCap(),
                        "stateVersion", Long.toString(policy.getStateVersion()),
                        "killEpoch", Long.toString(policy.getKillEpoch())));
        return dto(policy);
    }

    /** Provisions one active organization outside domain locks; existing choices are preserved. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void provision(int orgId) {
        if (organizations.lockActiveByIdForShare(orgId) != null) {
            policies.provision(orgId);
        }
    }

    private AiStandingApprovalPolicyDto dto(AiStandingApprovalPolicy policy) {
        if (policy == null) {
            return new AiStandingApprovalPolicyDto(false, authoringEnabled, false,
                    java.util.List.of(), 100, 10, null, null);
        }
        if (policy.getStateVersion() < 1 || policy.getKillEpoch() < 1
                || policy.getMaximumDailyCap() < 1 || policy.getMaximumDailyCap() > 10000
                || policy.getMaximumPerRunCap() < 1
                || policy.getMaximumPerRunCap() > policy.getMaximumDailyCap()) {
            throw new IllegalStateException("Invalid standing approval policy metadata");
        }
        return new AiStandingApprovalPolicyDto(true, authoringEnabled, policy.isEnabled(),
                AiStandingApprovalCeiling.tools(policy.getAllowedToolMask()), policy.getMaximumDailyCap(),
                policy.getMaximumPerRunCap(), Long.toString(policy.getStateVersion()),
                Long.toString(policy.getKillEpoch()));
    }

    private static ForbiddenException administratorRequired() {
        return new ForbiddenException("Requires an organization administrator role");
    }
}
