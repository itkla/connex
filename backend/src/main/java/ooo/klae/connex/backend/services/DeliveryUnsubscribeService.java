package ooo.klae.connex.backend.services;

import java.time.LocalDateTime;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

import ooo.klae.connex.backend.beans.CampaignDelivery;
import ooo.klae.connex.backend.beans.CampaignDeliveryEvent;
import ooo.klae.connex.backend.beans.CampaignSend;
import ooo.klae.connex.backend.dto.ContactChannelConsentRequest;
import ooo.klae.connex.backend.dto.DeliveryUnsubscribeDto;
import ooo.klae.connex.backend.dto.SuppressionEntryRequest;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.CampaignDeliveryMapper;
import ooo.klae.connex.backend.mappers.CampaignSendMapper;

/**
 * Handles the public unsubscribe endpoints. The signed token alone identifies a single
 * {@code campaign_delivery} row; the workspace is resolved from that row and never trusted from the
 * request, so no caller-supplied id is honored. The suppression and consent-revocation writes run as
 * the system actor in the delivery's own workspace, and the whole operation is idempotent.
 */
@Service
@RequiredArgsConstructor
public class DeliveryUnsubscribeService {

    private static final String EVENT_UNSUBSCRIBED = "unsubscribed";

    private final CampaignDeliveryMapper campaignDeliveryMapper;
    private final CampaignSendMapper campaignSendMapper;
    private final SuppressionService suppressionService;
    private final ConsentService consentService;
    private final SystemActor systemActor;
    private final AutomationExecutor automationExecutor;

    /** Returns the confirmation payload for an unsubscribe link. */
    public DeliveryUnsubscribeDto preview(String token) {
        CampaignDelivery delivery = requireDelivery(token);
        CampaignSend send = requireSend(delivery);
        boolean unsubscribed = campaignDeliveryMapper.hasEvent(
                delivery.getWorkspaceId(), delivery.getId(), EVENT_UNSUBSCRIBED);
        return new DeliveryUnsubscribeDto(send.getChannel(), maskAddress(delivery.getAddress()), unsubscribed);
    }

    /** Performs the unsubscribe: idempotent suppression, consent revocation, and an event. */
    public DeliveryUnsubscribeDto unsubscribe(String token) {
        CampaignDelivery delivery = requireDelivery(token);
        CampaignSend send = requireSend(delivery);
        int workspaceId = delivery.getWorkspaceId();
        if (campaignDeliveryMapper.hasEvent(workspaceId, delivery.getId(), EVENT_UNSUBSCRIBED)) {
            return new DeliveryUnsubscribeDto(send.getChannel(), maskAddress(delivery.getAddress()), true);
        }
        automationExecutor.runAs(workspaceId, systemActor.user(), "system", () -> {
            suppressionService.add(new SuppressionEntryRequest(
                    "workspace", send.getChannel(), delivery.getAddress(), delivery.getPersonId(),
                    "unsubscribe", "Recipient unsubscribed via campaign link"));
            if (delivery.getPersonId() != null) {
                consentService.setForPerson(delivery.getPersonId(), new ContactChannelConsentRequest(
                        send.getChannel(), send.getPurpose(), "revoked", "unsubscribe", null,
                        LocalDateTime.now()));
            }
            return null;
        });
        CampaignDeliveryEvent event = new CampaignDeliveryEvent();
        event.setWorkspaceId(workspaceId);
        event.setDeliveryId(delivery.getId());
        event.setEventType(EVENT_UNSUBSCRIBED);
        event.setDetail("Recipient unsubscribed");
        campaignDeliveryMapper.insertEvent(event);
        return new DeliveryUnsubscribeDto(send.getChannel(), maskAddress(delivery.getAddress()), true);
    }

    private CampaignDelivery requireDelivery(String token) {
        CampaignDelivery delivery = token == null ? null : campaignDeliveryMapper.getByToken(token);
        if (delivery == null) {
            throw new ResourceNotFoundException("Unsubscribe link is not valid");
        }
        return delivery;
    }

    private CampaignSend requireSend(CampaignDelivery delivery) {
        CampaignSend send = campaignSendMapper.getSend(delivery.getWorkspaceId(), delivery.getSendId());
        if (send == null) {
            throw new ResourceNotFoundException("Unsubscribe link is not valid");
        }
        return send;
    }

    private static String maskAddress(String address) {
        if (address == null || address.isBlank()) {
            return "";
        }
        int at = address.indexOf('@');
        if (at <= 0) {
            return "***";
        }
        char first = address.charAt(0);
        return first + "***" + address.substring(at);
    }
}
