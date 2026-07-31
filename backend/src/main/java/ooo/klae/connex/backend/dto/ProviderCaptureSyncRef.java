package ooo.klae.connex.backend.dto;

/**
 * Catalog-local due capture stream reference.
 *
 * @param workspaceId workspace owning the stream
 * @param syncStateId durable stream state id
 */
public record ProviderCaptureSyncRef(int workspaceId, long syncStateId) {
}
