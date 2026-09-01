package ooo.klae.connex.backend.dto;

/**
 * Proof requirements for beginning passkey registration in the current account.
 *
 * @param currentPasswordRequired whether first enrollment must include the current password
 * @param emailConfirmationRequired whether first enrollment additionally needs an out-of-band
 *     confirmation emailed to the account address, because the account currently holds privilege
 * @param emailConfirmationSatisfied whether this session already redeemed such a confirmation
 */
public record PasskeyRegistrationRequirementsDto(
        boolean currentPasswordRequired,
        boolean emailConfirmationRequired,
        boolean emailConfirmationSatisfied) {}
