package ooo.klae.connex.backend.dto;

import jakarta.validation.constraints.Min;

/** Request body for inviting one active workspace member to a shared assistant session. */
public record AiChatParticipantInviteRequest(@Min(1) int userId) {
}
