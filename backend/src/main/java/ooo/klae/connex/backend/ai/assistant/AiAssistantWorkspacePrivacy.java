package ooo.klae.connex.backend.ai.assistant;

/** Control-plane workspace identity and current assistant generation for notification projection. */
public record AiAssistantWorkspacePrivacy(int workspaceId, int organizationId, long generation) {}
