package ooo.klae.connex.backend.dto;

/**
 * Proof requirements for beginning passkey registration in the current account.
 *
 * @param currentPasswordRequired whether first enrollment must include the current password
 * @param operatorAuthorizationRequired whether the account administers other principals and so
 *        cannot enroll its first passkey on the strength of its password alone
 */
public record PasskeyRegistrationRequirementsDto(
        boolean currentPasswordRequired,
        boolean operatorAuthorizationRequired) {}
