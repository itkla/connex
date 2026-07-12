package ooo.klae.connex.backend.dto;

/**
 * Proof requirements for beginning passkey registration in the current account.
 * @param currentPasswordRequired whether first enrollment must include the current password
 */
public record PasskeyRegistrationRequirementsDto(boolean currentPasswordRequired) {}
