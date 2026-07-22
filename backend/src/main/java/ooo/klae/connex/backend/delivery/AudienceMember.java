package ooo.klae.connex.backend.delivery;

import java.util.Objects;

/**
 * One recipient in an audience push to a third-party marketing connector. Carries only the fields a
 * generic list connector needs; a normalized email address is required, the names are optional.
 * @param email the normalized recipient email address
 * @param firstName the recipient given name, or null when unknown
 * @param lastName the recipient family name, or null when unknown
 */
public record AudienceMember(String email, String firstName, String lastName) {

    public AudienceMember {
        Objects.requireNonNull(email, "email");
    }
}
