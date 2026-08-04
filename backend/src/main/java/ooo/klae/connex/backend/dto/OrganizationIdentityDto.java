package ooo.klae.connex.backend.dto;

/** Canonical organization display identity returned by settings and layout endpoints. */
public record OrganizationIdentityDto(
        int id,
        String name,
        String slug,
        String updatedAt) {
}
