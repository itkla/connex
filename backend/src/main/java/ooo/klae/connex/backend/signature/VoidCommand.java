package ooo.klae.connex.backend.signature;

/** Provider-neutral command for voiding one envelope. */
public record VoidCommand(
        int workspaceId,
        int deliveryId,
        String providerEnvelopeId,
        String reason) {
}
