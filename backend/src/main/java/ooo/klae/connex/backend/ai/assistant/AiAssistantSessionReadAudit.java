package ooo.klae.connex.backend.ai.assistant;

import java.util.Map;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.services.AuditService;

/** Records metadata-only administrative reads of assistant sessions. */
@Service
@RequiredArgsConstructor
public class AiAssistantSessionReadAudit {
    private final AuditService auditService;

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
}
