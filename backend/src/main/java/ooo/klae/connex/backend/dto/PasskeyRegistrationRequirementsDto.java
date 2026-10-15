package ooo.klae.connex.backend.dto;

import java.util.List;

import ooo.klae.connex.backend.services.PrivilegedMfaStanding;

/**
 * Proof requirements for beginning passkey registration in the current account.
 *
 * @param currentPasswordRequired whether first enrollment must include the current password
 * @param emailConfirmationRequired whether first enrollment additionally needs an out-of-band
 *     confirmation emailed to the account address, because the account currently holds privilege
 * @param emailConfirmationSatisfied whether this session already redeemed such a confirmation
 * @param standing the account's privileged-MFA standing under the current attestation policy
 *     (#1534); this route stays reachable while the account is confined, so a client re-reads it
 *     after each ceremony and releases confinement only on {@code SATISFIED} or {@code UNPRIVILEGED}
 * @param privilegedMemberships the account's own active privileged memberships, regardless of enforcement
 * @param approvalGaps for each of the account's own passkeys without break-glass assurance, every
 *     organization where the account is privileged and that passkey is not yet approved
 */
public record PasskeyRegistrationRequirementsDto(
        boolean currentPasswordRequired,
        boolean emailConfirmationRequired,
        boolean emailConfirmationSatisfied,
        PrivilegedMfaStanding standing,
        List<PasskeyApprovalGapDto> approvalGaps,
        List<PrivilegedMembershipDto> privilegedMemberships) {}
