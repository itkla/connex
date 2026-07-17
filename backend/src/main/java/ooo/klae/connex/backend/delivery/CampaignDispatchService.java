package ooo.klae.connex.backend.delivery;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

import ooo.klae.connex.backend.beans.CampaignDelivery;
import ooo.klae.connex.backend.beans.CampaignDeliveryEvent;
import ooo.klae.connex.backend.beans.CampaignMessageRevision;
import ooo.klae.connex.backend.beans.CampaignSend;
import ooo.klae.connex.backend.mappers.CampaignDeliveryMapper;
import ooo.klae.connex.backend.mappers.CampaignMessageMapper;
import ooo.klae.connex.backend.mappers.CampaignSendMapper;
import ooo.klae.connex.backend.services.AudienceEligibilityService;

/**
 * Processes queued campaign deliveries for a send, claim-first and never throwing to the caller
 * (mirrors the rule engine). Each recipient is re-checked at dispatch time against restriction,
 * suppression, consent, a frequency cap, and quiet hours before the rendered message is handed to
 * the resolved provider. The dispatch policy is a fixed default for this slice: at most one message
 * per person per channel within {@link #FREQUENCY_WINDOW_HOURS} hours, and quiet hours disabled
 * ({@link #QUIET_START_HOUR} &lt; 0); a configurable per-workspace policy source arrives later.
 */
@Service
@RequiredArgsConstructor
public class CampaignDispatchService {

    private static final Logger log = LoggerFactory.getLogger(CampaignDispatchService.class);
    private static final int FREQUENCY_WINDOW_HOURS = 24;
    private static final int QUIET_START_HOUR = -1;
    private static final int QUIET_END_HOUR = -1;
    private static final int ERROR_LIMIT = 512;

    private final CampaignSendMapper campaignSendMapper;
    private final CampaignDeliveryMapper campaignDeliveryMapper;
    private final CampaignMessageMapper campaignMessageMapper;
    private final AudienceEligibilityService audienceEligibilityService;
    private final DeliveryProviderConfigService deliveryProviderConfigService;
    private final DeliveryProviderRouter deliveryProviderRouter;
    private final DeliveryProperties deliveryProperties;

    /** Processes every queued send in the workspace. Never throws. */
    public void processWorkspace(int workspaceId) {
        for (int sendId : campaignSendMapper.queuedSendIds(workspaceId)) {
            try {
                processSend(workspaceId, sendId);
            } catch (Exception exception) {
                log.warn("Campaign send {} dispatch failed in workspace {}: {}",
                        sendId, workspaceId, exception.getMessage());
            }
        }
    }

    /** Dispatches the pending deliveries of one send, claim-first. Never throws. */
    public void processSend(int workspaceId, int sendId) {
        CampaignSend send = campaignSendMapper.getSend(workspaceId, sendId);
        if (send == null || !("queued".equals(send.getStatus()) || "running".equals(send.getStatus()))) {
            return;
        }
        DeliveryChannel channel;
        ResolvedDeliveryProvider target;
        MessageDispatcher dispatcher;
        try {
            channel = DeliveryChannel.fromToken(send.getChannel());
            target = deliveryProviderConfigService.resolveForWorkspace(workspaceId, channel);
            dispatcher = deliveryProviderRouter.dispatcherFor(target.providerId());
        } catch (RuntimeException exception) {
            log.warn("Campaign send {} has no usable provider: {}", sendId, exception.getMessage());
            return;
        }
        CampaignMessageRevision revision =
                campaignMessageMapper.getRevision(workspaceId, send.getMessageId(), send.getMessageVersion());
        if (revision == null) {
            log.warn("Campaign send {} references a missing message revision", sendId);
            return;
        }
        campaignSendMapper.assignProvider(workspaceId, sendId, target.providerId());
        campaignSendMapper.markRunning(workspaceId, sendId);
        CampaignSend running = campaignSendMapper.getSend(workspaceId, sendId);
        if (running == null || !"running".equals(running.getStatus())) {
            return;
        }
        for (int deliveryId : campaignDeliveryMapper.pendingDeliveryIds(workspaceId, sendId)) {
            try {
                dispatchOne(workspaceId, send, channel, target, dispatcher, revision, deliveryId);
            } catch (Exception exception) {
                log.warn("Campaign delivery {} dispatch failed: {}", deliveryId, exception.getMessage());
            }
        }
        campaignSendMapper.refreshCounters(workspaceId, sendId);
        if (campaignDeliveryMapper.countPending(workspaceId, sendId) == 0) {
            campaignSendMapper.markCompleted(workspaceId, sendId);
        }
    }

    private void dispatchOne(int workspaceId, CampaignSend send, DeliveryChannel channel,
            ResolvedDeliveryProvider target, MessageDispatcher dispatcher,
            CampaignMessageRevision revision, int deliveryId) {
        if (campaignDeliveryMapper.claim(workspaceId, deliveryId) != 1) {
            return;
        }
        CampaignDelivery delivery = campaignDeliveryMapper.getDelivery(workspaceId, deliveryId);
        if (delivery == null) {
            return;
        }
        String skipReason = evaluate(workspaceId, send, channel, delivery);
        if (skipReason != null) {
            campaignDeliveryMapper.markSkipped(workspaceId, deliveryId, skipReason);
            return;
        }
        RenderedMessage content = render(revision, delivery);
        DeliveryRequest request = new DeliveryRequest(
                channel, delivery.getAddress(), content, delivery.getPersonId(),
                "send:" + send.getId() + ":" + deliveryId);
        DispatchReceipt receipt = dispatcher.dispatch(target, request);
        if (receipt.status() == DispatchStatus.SENT) {
            campaignDeliveryMapper.markDispatched(workspaceId, deliveryId, receipt.providerMessageId());
            appendEvent(workspaceId, deliveryId, "dispatched", receipt.detail());
        } else {
            campaignDeliveryMapper.markFailed(workspaceId, deliveryId, bounded(receipt.detail()));
            appendEvent(workspaceId, deliveryId, "failed", receipt.detail());
        }
    }

    private String evaluate(int workspaceId, CampaignSend send, DeliveryChannel channel, CampaignDelivery delivery) {
        String address = delivery.getAddress();
        if (address == null || address.isBlank()) {
            return "no_address";
        }
        Integer personId = delivery.getPersonId();
        String channelToken = channel.token();
        if (personId != null
                && !audienceEligibilityService.restrictedIds(workspaceId, List.of(personId)).isEmpty()) {
            return "restricted";
        }
        String normalizedAddress = address.trim().toLowerCase(Locale.ROOT);
        if (!audienceEligibilityService.suppressedAddresses(workspaceId, channelToken, List.of(normalizedAddress)).isEmpty()) {
            return "suppressed";
        }
        if (personId == null || !audienceEligibilityService
                .grantedConsentIds(workspaceId, List.of(personId), channelToken, send.getPurpose())
                .contains(personId)) {
            return "consent_missing";
        }
        if (FREQUENCY_WINDOW_HOURS > 0) {
            LocalDateTime since = LocalDateTime.now().minusHours(FREQUENCY_WINDOW_HOURS);
            if (campaignDeliveryMapper.recentDispatchCount(
                    workspaceId, personId, channelToken, send.getId(), since) > 0) {
                return "frequency_capped";
            }
        }
        if (inQuietHours(LocalDateTime.now())) {
            return "quiet_hours";
        }
        return null;
    }

    private RenderedMessage render(CampaignMessageRevision revision, CampaignDelivery delivery) {
        Map<String, String> tokens = Map.of("unsubscribe_url", unsubscribeUrl(delivery.getUnsubscribeToken()));
        String subject = substitutePlain(revision.getSubject(), tokens);
        String html = substituteHtml(revision.getBodyHtml(), tokens);
        String text = revision.getBodyText() == null ? null : substitutePlain(revision.getBodyText(), tokens);
        return new RenderedMessage(subject, html, text);
    }

    private String unsubscribeUrl(String token) {
        String base = deliveryProperties.getPublicBaseUrl();
        String path = "/api/delivery/unsubscribe/" + token;
        if (base == null || base.isBlank()) {
            return path;
        }
        String trimmed = base.trim();
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) + path : trimmed + path;
    }

    private static boolean inQuietHours(LocalDateTime now) {
        if (QUIET_START_HOUR < 0 || QUIET_END_HOUR < 0) {
            return false;
        }
        int hour = now.getHour();
        if (QUIET_START_HOUR <= QUIET_END_HOUR) {
            return hour >= QUIET_START_HOUR && hour < QUIET_END_HOUR;
        }
        return hour >= QUIET_START_HOUR || hour < QUIET_END_HOUR;
    }

    private void appendEvent(int workspaceId, int deliveryId, String eventType, String detail) {
        CampaignDeliveryEvent event = new CampaignDeliveryEvent();
        event.setWorkspaceId(workspaceId);
        event.setDeliveryId(deliveryId);
        event.setEventType(eventType);
        event.setDetail(bounded(detail));
        campaignDeliveryMapper.insertEvent(event);
    }

    private static String substitutePlain(String template, Map<String, String> tokens) {
        String result = template;
        for (Map.Entry<String, String> token : tokens.entrySet()) {
            result = result.replace("{{" + token.getKey() + "}}", token.getValue());
        }
        return result;
    }

    private static String substituteHtml(String template, Map<String, String> tokens) {
        String result = template;
        for (Map.Entry<String, String> token : tokens.entrySet()) {
            result = result.replace("{{" + token.getKey() + "}}", escapeHtml(token.getValue()));
        }
        return result;
    }

    private static String escapeHtml(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    private static String bounded(String message) {
        if (message == null || message.isBlank()) {
            return null;
        }
        String trimmed = message.trim();
        return trimmed.length() > ERROR_LIMIT ? trimmed.substring(0, ERROR_LIMIT) : trimmed;
    }
}
