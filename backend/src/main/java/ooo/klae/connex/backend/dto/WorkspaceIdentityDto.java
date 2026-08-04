package ooo.klae.connex.backend.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Canonical mutable workspace identity returned after a settings mutation. */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record WorkspaceIdentityDto(
        int id,
        int orgId,
        String name,
        String slug,
        String timezone,
        long identityVersion,
        String updatedAt) {
}
