package ooo.klae.connex.backend.publicapi;

/** Immutable workspace and organization roots referenced by an API credential. */
public record ApiCredentialReferenceRoot(int workspaceId, int organizationId) {
}
