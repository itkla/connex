package ooo.klae.connex.backend.services;

import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.beans.AiWorkspaceGovernance;
import ooo.klae.connex.backend.dto.AiWorkspaceGovernanceDto;
import ooo.klae.connex.backend.dto.AiWorkspaceGovernanceRequest;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.mappers.AiWorkspaceGovernanceMapper;

/** Workspace-level AI kill switch and bounded assistant turn configuration. */
@Service
@RequiredArgsConstructor
public class AiWorkspaceGovernanceService {
    public static final int DEFAULT_ASSISTANT_MAX_STEPS = 6;

    private final AiWorkspaceGovernanceMapper governanceMapper;
    private final WorkspaceService workspaceService;
    private final OrgMemberService orgMemberService;
    private final AuditService auditService;

    /** Returns administrator-visible governance for the active workspace. */
    @Transactional(readOnly = true)
    public AiWorkspaceGovernanceDto getForWorkspace(int workspaceId, int actorId) {
        requireAdministrator(workspaceId, actorId);
        return AiWorkspaceGovernanceDto.from(current(workspaceId));
    }

    /** Replaces governance settings for the active workspace. */
    @Transactional
    public AiWorkspaceGovernanceDto save(
            int workspaceId,
            int actorId,
            AiWorkspaceGovernanceRequest request) {
        requireAdministrator(workspaceId, actorId);
        if (request == null || request.enabled() == null || request.assistantMaxSteps() == null
                || request.assistantMaxSteps() < 1 || request.assistantMaxSteps() > 12) {
            throw new BadRequestException("Workspace AI governance is invalid");
        }
        governanceMapper.upsert(
                workspaceId,
                request.enabled(),
                request.assistantMaxSteps());
        auditService.recordStrict(
                "ai.workspace_governance.save",
                "workspace",
                workspaceId,
                "Workspace " + workspaceId,
                "Updated workspace AI governance",
                Map.of(
                        "enabled", request.enabled(),
                        "assistantMaxSteps", request.assistantMaxSteps()));
        return AiWorkspaceGovernanceDto.from(current(workspaceId));
    }

    /** Returns whether AI remains enabled for the current tenant workspace. */
    @Transactional(readOnly = true)
    public boolean isEnabled(int workspaceId) {
        return current(workspaceId).isAiEnabled();
    }

    /** Returns the configured assistant step cap for the current tenant workspace. */
    @Transactional(readOnly = true)
    public int assistantMaxSteps(int workspaceId) {
        return current(workspaceId).getAssistantMaxSteps();
    }

    private AiWorkspaceGovernance current(int workspaceId) {
        AiWorkspaceGovernance stored = governanceMapper.get(workspaceId);
        if (stored != null) {
            return stored;
        }
        AiWorkspaceGovernance defaults = new AiWorkspaceGovernance();
        defaults.setWorkspaceId(workspaceId);
        defaults.setAiEnabled(true);
        defaults.setAssistantMaxSteps(DEFAULT_ASSISTANT_MAX_STEPS);
        return defaults;
    }

    private void requireAdministrator(int workspaceId, int actorId) {
        if (workspaceService.getCurrentWorkspaceId() != workspaceId) {
            throw new ForbiddenException("AI governance is restricted to the active workspace");
        }
        orgMemberService.requireOrgAdmin(workspaceService.getCurrentOrgId(), actorId);
    }
}
