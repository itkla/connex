package ooo.klae.connex.backend.services;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

import ooo.klae.connex.backend.beans.ContactChannelConsent;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.delivery.ChannelAddressNormalizer;
import ooo.klae.connex.backend.delivery.DeliveryChannel;
import ooo.klae.connex.backend.dto.ChannelAddressRef;
import ooo.klae.connex.backend.dto.ContactChannelMarketingStatusDto;
import ooo.klae.connex.backend.dto.ContactMarketingStatusDto;
import ooo.klae.connex.backend.dto.PageResponse;
import ooo.klae.connex.backend.dto.PersonCampaignTouchDto;
import ooo.klae.connex.backend.dto.SuppressionChannelStateRow;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.CampaignDeliveryMapper;
import ooo.klae.connex.backend.mappers.ConsentMapper;
import ooo.klae.connex.backend.mappers.PersonMapper;
import ooo.klae.connex.backend.mappers.SuppressionMapper;
import ooo.klae.connex.backend.tenant.Permission;
import ooo.klae.connex.backend.tenant.RequirePermission;

/**
 * What a contact record needs to know about marketing before someone contacts them: whether they are
 * excluded, and which campaigns have already reached them.
 *
 * <p>The two reads are gated differently on purpose. The contactability state is visible to anyone
 * who can already open the contact, because its whole job is to stop a member emailing someone who
 * opted out; it discloses only booleans, a state token, and a timestamp — never the stored
 * suppression address, note, or author, which stay behind {@link Permission#CONSENT_MANAGE} on the
 * suppression surface. The campaign touches are campaign content and therefore need
 * {@link Permission#CAMPAIGN_VIEW}. Bulk per-person marketing rosters remain
 * {@code CONSENT_MANAGE}-gated in {@link CampaignRecipientService}; nothing here returns more than
 * one already-visible contact.
 */
@Service
@RequiredArgsConstructor
public class ContactMarketingService {

    /** The consent purpose the campaign machinery enforces for marketing sends. */
    private static final String MARKETING_PURPOSE = "marketing";

    /** The suppression reason that means the workspace recorded an explicit do-not-contact. */
    private static final String DO_NOT_CONTACT = "do_not_contact";

    private final PersonMapper personMapper;
    private final SuppressionMapper suppressionMapper;
    private final ConsentMapper consentMapper;
    private final CampaignDeliveryMapper campaignDeliveryMapper;
    private final WorkspaceService workspaceService;

    /**
     * Returns one contact's marketing exclusion state, per delivery channel, plus the contact-level
     * privacy hold.
     *
     * @param personId the contact record id
     * @return the contact's marketing status
     */
    public ContactMarketingStatusDto getStatus(int personId) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        Person person = personMapper.getPersonById(workspaceId, personId);
        if (person == null) {
            throw new ResourceNotFoundException("Contact not found with id: " + personId);
        }
        List<ChannelAddressRef> addresses = addressesFor(person);
        List<SuppressionChannelStateRow> suppressions =
                suppressionMapper.findPersonChannelStates(workspaceId, personId, addresses);
        Set<String> revokedChannels = revokedMarketingChannels(workspaceId, personId);
        Set<String> addressableChannels = new HashSet<>();
        for (ChannelAddressRef address : addresses) {
            addressableChannels.add(address.channel());
        }

        List<ContactChannelMarketingStatusDto> channels = new ArrayList<>();
        for (DeliveryChannel channel : DeliveryChannel.values()) {
            channels.add(channelStatus(
                    channel.token(), suppressions, revokedChannels, addressableChannels));
        }
        boolean privacyHold =
                person.getSuspendedAt() != null || person.getProvisionCeasedAt() != null;
        return new ContactMarketingStatusDto(
                personId, privacyHold, person.getSuspendedAt(), person.getProvisionCeasedAt(),
                channels);
    }

    /**
     * Returns one bounded page of the campaign touches on a contact's timeline, newest first.
     *
     * @param personId the contact record id
     * @param limit the page size
     * @param offset the page offset
     * @return the page of touches and the total it was drawn from
     */
    @RequirePermission(Permission.CAMPAIGN_VIEW)
    public PageResponse<PersonCampaignTouchDto> getCampaignTouches(
            int personId, int limit, int offset) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        if (personMapper.getPersonById(workspaceId, personId) == null) {
            throw new ResourceNotFoundException("Contact not found with id: " + personId);
        }
        List<PersonCampaignTouchDto> items =
                campaignDeliveryMapper.listPersonTouches(workspaceId, personId, limit, offset);
        return new PageResponse<>(items, campaignDeliveryMapper.countPersonTouches(workspaceId, personId));
    }

    private static ContactChannelMarketingStatusDto channelStatus(
            String channel,
            List<SuppressionChannelStateRow> suppressions,
            Set<String> revokedChannels,
            Set<String> addressableChannels) {
        boolean doNotContact = false;
        boolean suppressed = false;
        for (SuppressionChannelStateRow row : suppressions) {
            if (!channel.equals(row.channel())) {
                continue;
            }
            if (DO_NOT_CONTACT.equals(row.reason())) {
                doNotContact = true;
            } else {
                suppressed = true;
            }
        }
        boolean consentRevoked = revokedChannels.contains(channel);
        boolean optedOut = suppressed || consentRevoked || doNotContact;
        String state = doNotContact ? DO_NOT_CONTACT : optedOut ? "opted_out" : null;
        return new ContactChannelMarketingStatusDto(
                channel, state, optedOut, doNotContact, consentRevoked,
                addressableChannels.contains(channel));
    }

    private Set<String> revokedMarketingChannels(int workspaceId, int personId) {
        Set<String> revoked = new HashSet<>();
        for (ContactChannelConsent consent : consentMapper.getForPerson(workspaceId, personId)) {
            if (MARKETING_PURPOSE.equals(consent.getPurpose()) && "revoked".equals(consent.getStatus())) {
                revoked.add(consent.getChannel());
            }
        }
        return revoked;
    }

    private static List<ChannelAddressRef> addressesFor(Person person) {
        List<ChannelAddressRef> addresses = new ArrayList<>();
        for (DeliveryChannel channel : DeliveryChannel.values()) {
            String address = ChannelAddressNormalizer.addressFor(channel, person);
            if (address != null && !address.isBlank()) {
                addresses.add(new ChannelAddressRef(channel.token(), address));
            }
        }
        return addresses;
    }
}
