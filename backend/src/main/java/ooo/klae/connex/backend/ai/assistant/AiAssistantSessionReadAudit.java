package ooo.klae.connex.backend.ai.assistant;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.beans.AiChatSession;
import ooo.klae.connex.backend.services.AuditService;
import ooo.klae.connex.backend.services.WorkspaceService;
import ooo.klae.connex.backend.tenant.Permission;

/** Records metadata-only administrative reads of assistant sessions. */
@Service
@RequiredArgsConstructor
public class AiAssistantSessionReadAudit {
    private final AuditService auditService;
    private final WorkspaceService workspaceService;

    /** Records one accessible administrative read when the caller holds the administrative role. */
    public void recordAccessible(int workspaceId, int userId, AiChatSession session) {
        recordAccessible(
                userId,
                session,
                workspaceService.permissionsFor(workspaceId, userId)
                        .contains(Permission.AI_SESSION_ADMIN));
    }

    /** Records accessible administrative reads with one permission lookup for the whole page. */
    public void recordAccessible(int workspaceId, int userId, List<AiChatSession> sessions) {
        boolean administrative = workspaceService.permissionsFor(workspaceId, userId)
                .contains(Permission.AI_SESSION_ADMIN);
        sessions.forEach(session -> recordAccessible(userId, session, administrative));
    }

    /** Records one strict assistant-session read without transcript or action-card content. */
    public void record(int sessionId, String scope) {
        auditService.recordStrict(
                "ai.assistant.session.read",
                "ai_chat_session",
                sessionId,
                "Assistant session " + sessionId,
                "Administrative assistant session read",
                Map.of("scope", scope));
    }

    private void recordAccessible(int userId, AiChatSession session, boolean administrative) {
        if (administrative && !Objects.equals(session.getCreatedByUserId(), userId)) {
            record(session.getId(), "accessible");
        }
    }
}
