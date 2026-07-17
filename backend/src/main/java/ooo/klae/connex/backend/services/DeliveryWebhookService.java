package ooo.klae.connex.backend.services;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import ooo.klae.connex.backend.beans.CampaignDelivery;
import ooo.klae.connex.backend.beans.CampaignDeliveryEvent;
import ooo.klae.connex.backend.beans.CampaignSend;
import ooo.klae.connex.backend.delivery.DeliveryEvent;
import ooo.klae.connex.backend.delivery.DeliveryEventType;
import ooo.klae.connex.backend.delivery.DeliveryProviderConfigService;
import ooo.klae.connex.backend.delivery.DeliveryProviderException;
import ooo.klae.connex.backend.delivery.DeliveryProviderRouter;
import ooo.klae.connex.backend.delivery.ProviderEventSource;
import ooo.klae.connex.backend.delivery.ResolvedDeliveryProvider;
import ooo.klae.connex.backend.dto.ContactChannelConsentRequest;
import ooo.klae.connex.backend.dto.SuppressionEntryRequest;
import ooo.klae.connex.backend.mappers.CampaignDeliveryMapper;
import ooo.klae.connex.backend.mappers.CampaignSendMapper;

/**
 * Ingests authenticated provider webhooks and reconciles delivery state. The opaque token in the URL
 * alone resolves the workspace and provider (never the request body); the raw body is then
 * signature-verified by the provider's own adapter, translated into normalized events, and each event
 * is recorded idempotently against the {@code campaign_delivery} it concerns. A hard bounce or a
 * complaint additionally records a suppression entry and revokes consent, run as the system actor in
 * the delivery's own workspace, mirroring the public unsubscribe path.
 */
@Service
@RequiredArgsConstructor
public class DeliveryWebhookService {

    private static final Logger log = LoggerFactory.getLogger(DeliveryWebhookService.class);
    private static final int DETAIL_LIMIT = 512;

    private final DeliveryProviderConfigService deliveryProviderConfigService;
    private final DeliveryProviderRouter deliveryProviderRouter;
    private final CampaignDeliveryMapper campaignDeliveryMapper;
    private final CampaignSendMapper campaignSendMapper;
    private final SuppressionService suppressionService;
    private final ConsentService consentService;
    private final SystemActor systemActor;
    private final AutomationExecutor automationExecutor;

    /**
     * Authenticates and applies a provider webhook. Returns the number of events newly applied;
     * replayed events are counted as skipped and never double-applied.
     * @param provider the provider id from the request path
     * @param rawToken the opaque webhook token from the request path
     * @param rawBody the exact received body bytes
     * @param headers the received headers, keyed by lower-case name
     * @return the number of events newly applied
     */
    @Transactional
    public int ingest(String provider, String rawToken, byte[] rawBody, Map<String, String> headers) {
        ResolvedDeliveryProvider target = deliveryProviderConfigService.resolveByWebhookToken(rawToken);
        if (!target.providerId().equals(normalize(provider))) {
            throw new DeliveryProviderException("Webhook provider does not match the token");
        }
        ProviderEventSource eventSource = deliveryProviderRouter.eventSourceFor(target.providerId());
        eventSource.verifySignature(target, rawBody, headers);
        List<DeliveryEvent> events = eventSource.translate(rawBody);
        int applied = 0;
        for (DeliveryEvent event : events) {
            if (applyEvent(target, event)) {
                applied++;
            }
        }
        return applied;
    }

    private boolean applyEvent(ResolvedDeliveryProvider target, DeliveryEvent event) {
        int workspaceId = target.workspaceId();
        if (event.providerMessageId() == null || event.providerMessageId().isBlank()) {
            return false;
        }
        CampaignDelivery delivery = campaignDeliveryMapper.findByProviderMessage(
                workspaceId, target.providerId(), event.providerMessageId());
        if (delivery == null) {
            return false;
        }
        if (!recordEvent(workspaceId, delivery.getId(), target.providerId(), event)) {
            return false;
        }
        applyStatus(workspaceId, delivery, event.type());
        if (event.type() == DeliveryEventType.BOUNCED || event.type() == DeliveryEventType.COMPLAINED) {
            suppress(workspaceId, delivery, event.type());
        }
        return true;
    }

    private boolean recordEvent(int workspaceId, int deliveryId, String providerId, DeliveryEvent event) {
        CampaignDeliveryEvent row = new CampaignDeliveryEvent();
        row.setWorkspaceId(workspaceId);
        row.setDeliveryId(deliveryId);
        row.setEventType(event.type().token());
        row.setDetail(bounded(event.detail()));
        row.setProviderId(providerId);
        row.setProviderEventId(event.providerEventId());
        try {
            campaignDeliveryMapper.insertEvent(row);
            return true;
        } catch (DuplicateKeyException exception) {
            return false;
        }
    }

    private void applyStatus(int workspaceId, CampaignDelivery delivery, DeliveryEventType type) {
        List<String> fromStatuses = switch (type) {
            case DELIVERED, FAILED -> List.of("dispatched");
            case BOUNCED, COMPLAINED -> List.of("dispatched", "delivered");
        };
        campaignDeliveryMapper.applyProviderStatus(workspaceId, delivery.getId(), type.token(), fromStatuses);
    }

    private void suppress(int workspaceId, CampaignDelivery delivery, DeliveryEventType type) {
        CampaignSend send = campaignSendMapper.getSend(workspaceId, delivery.getSendId());
        if (send == null) {
            log.warn("Delivery {} webhook event references a missing send", delivery.getId());
            return;
        }
        String reason = type == DeliveryEventType.BOUNCED ? "hard_bounce" : "complaint";
        automationExecutor.runAs(workspaceId, systemActor.user(), "system", () -> {
            suppressionService.add(new SuppressionEntryRequest(
                    "workspace", send.getChannel(), delivery.getAddress(), delivery.getPersonId(),
                    reason, "Recorded from a delivery provider " + reason + " event"));
            if (delivery.getPersonId() != null) {
                consentService.setForPerson(delivery.getPersonId(), new ContactChannelConsentRequest(
                        send.getChannel(), send.getPurpose(), "revoked", reason, null, LocalDateTime.now()));
            }
            return null;
        });
    }

    private static String normalize(String value) {
        return value == null ? null : value.trim().toLowerCase(Locale.ROOT);
    }

    private static String bounded(String message) {
        if (message == null || message.isBlank()) {
            return null;
        }
        String trimmed = message.trim();
        return trimmed.length() > DETAIL_LIMIT ? trimmed.substring(0, DETAIL_LIMIT) : trimmed;
    }
}
