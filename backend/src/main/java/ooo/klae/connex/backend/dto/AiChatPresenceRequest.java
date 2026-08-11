package ooo.klae.connex.backend.dto;

/** Heartbeat payload for one participant's ephemeral assistant-session presence. */
public record AiChatPresenceRequest(boolean typing) {
}
