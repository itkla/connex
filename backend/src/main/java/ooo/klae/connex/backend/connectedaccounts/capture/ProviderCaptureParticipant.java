package ooo.klae.connex.backend.connectedaccounts.capture;

/**
 * Provider-neutral participant identity.
 *
 * @param role organizer, attendee, from, to, or cc
 * @param displayName provider display name
 * @param email provider email address
 */
public record ProviderCaptureParticipant(String role, String displayName, String email) {
}
