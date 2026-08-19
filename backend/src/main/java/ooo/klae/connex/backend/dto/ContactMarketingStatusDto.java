package ooo.klae.connex.backend.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Whether a contact may still be marketed to, and why not.
 *
 * <p>The two exclusions are deliberately kept apart. A <em>privacy hold</em> is the APPI processing
 * restriction recorded on the contact itself, and it is reported once for the whole contact. An
 * <em>opt-out</em> or <em>do-not-contact</em> is a workspace-owned marketing suppression, which is
 * always per delivery channel. Conflating them would either hide a legal restriction behind a
 * marketing badge or claim a legal restriction where a recipient merely unsubscribed.
 *
 * <p>This read discloses the contactability state only — never the stored suppression address,
 * note, or author, which stay behind the consent-management surface.
 *
 * @param personId the contact record id
 * @param privacyHold whether a processing restriction applies to the contact
 * @param suspendedAt when use of the contact's data was suspended, or null
 * @param provisionCeasedAt when sharing the contact outside the workspace ceased, or null
 * @param channels the per-channel marketing exclusion state
 */
public record ContactMarketingStatusDto(
        int personId,
        boolean privacyHold,
        LocalDateTime suspendedAt,
        LocalDateTime provisionCeasedAt,
        List<ContactChannelMarketingStatusDto> channels) {

    public ContactMarketingStatusDto {
        channels = List.copyOf(channels);
    }
}
