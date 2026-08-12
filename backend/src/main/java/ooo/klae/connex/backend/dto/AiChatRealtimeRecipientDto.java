package ooo.klae.connex.backend.dto;

/** Control-plane identity needed to address one authenticated realtime user destination. */
public record AiChatRealtimeRecipientDto(int id, String username) {
}
