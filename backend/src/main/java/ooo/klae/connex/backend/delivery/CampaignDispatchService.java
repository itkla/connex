package ooo.klae.connex.backend.delivery;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongSupplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

import ooo.klae.connex.backend.beans.CampaignDelivery;
import ooo.klae.connex.backend.beans.CampaignDeliveryEvent;
import ooo.klae.connex.backend.beans.CampaignMessageRevision;
import ooo.klae.connex.backend.beans.CampaignSend;
import ooo.klae.connex.backend.capability.Capability;
import ooo.klae.connex.backend.capability.CapabilityRegistry;
import ooo.klae.connex.backend.mappers.CampaignDeliveryMapper;
import ooo.klae.connex.backend.mappers.CampaignMessageMapper;
import ooo.klae.connex.backend.mappers.CampaignSendMapper;
import ooo.klae.connex.backend.mappers.WorkflowRunMapper;
import ooo.klae.connex.backend.services.AudienceEligibilityService;
import ooo.klae.connex.backend.services.WorkflowTriggeredSendGate;

/**
 * Processes queued campaign deliveries for a send, claim-first and never throwing to the caller
 * (mirrors the rule engine). Each recipient is re-checked at dispatch time against restriction,
 * suppression, consent, a frequency cap, and quiet hours before the rendered message is handed to
 * the resolved provider. The dispatch policy is a fixed default for this slice: at most one message
 * per person per channel within {@link #FREQUENCY_WINDOW_HOURS} hours, and quiet hours disabled
 * ({@link #QUIET_START_HOUR} &lt; 0); a configurable per-workspace policy source arrives later.
 *
 * <p>The re-check is channel-generic: the address is normalized with {@link ChannelAddressNormalizer}
 * under the send's own channel, so it is compared to suppressions in exactly the form
 * {@code SuppressionService} stored them, and the consent verdict is delegated to
 * {@link AudienceEligibilityService} so it cannot diverge from the snapshot classification.
 */
@Service
@RequiredArgsConstructor
public class CampaignDispatchService {

    private static final Logger log = LoggerFactory.getLogger(CampaignDispatchService.class);
    private static final int FREQUENCY_WINDOW_HOURS = 24;
    private static final int QUIET_START_HOUR = -1;
    private static final int QUIET_END_HOUR = -1;
    private static final int ERROR_LIMIT = 512;
    private static final String EXPIRED_NON_IDEMPOTENT_CLAIM =
            "AMBIGUOUS: Worker claim expired on a transport without idempotent submission";
    private static final String EXPIRED_CHANGED_TARGET_CLAIM =
            "AMBIGUOUS: Worker claim expired after the delivery target changed";
    private static final String RECOVERED_CHANGED_TARGET_CLAIM =
            "AMBIGUOUS: Delivery target changed before a recovered attempt could resume";

    private final CampaignSendMapper campaignSendMapper;
    private final CampaignDeliveryMapper campaignDeliveryMapper;
    private final CampaignMessageMapper campaignMessageMapper;
    private final AudienceEligibilityService audienceEligibilityService;
    private final DeliveryProviderConfigService deliveryProviderConfigService;
    private final DeliveryProviderRouter deliveryProviderRouter;
    private final DeliveryProperties deliveryProperties;
    private final CapabilityRegistry capabilityRegistry;
    private final WorkflowTriggeredSendGate triggeredSendGate;
    private final WorkflowRunMapper workflowRunMapper;
    private final CampaignDispatchClaimBoundary claimBoundary;
    private LongSupplier nanoTimeSource = System::nanoTime;

    /** Processes every queued send in the workspace. Never throws. */
    public int processWorkspace(int workspaceId) {
        if (!capabilityRegistry.isAvailable(Capability.CAMPAIGN_DELIVERY)) {
            return 0;
        }
        recoverExpiredTriggeredClaims(workspaceId);
        int failed = 0;
        for (int sendId : campaignSendMapper.queuedSendIds(
                workspaceId, triggeredSendGate.enabled())) {
            try {
                if (!processSendReady(workspaceId, sendId)) {
                    failed++;
                }
            } catch (Exception exception) {
                failed++;
                log.warn("Campaign send {} dispatch failed in workspace {}: {}",
                        sendId, workspaceId, exception.getMessage());
            }
        }
        return failed;
    }

    /**
     * Dispatches the pending deliveries of one send, claim-first. Never throws.
     *
     * @param workspaceId owning workspace
     * @param sendId send to dispatch
     * @return {@code false} when the send could not be dispatched because of a fault the operator
     *         should see — an unresolvable delivery provider or a missing message revision — and
     *         {@code true} for both a successful dispatch and a legitimate no-op. Scheduler
     *         diagnostics derive their run status from this, so a silently undeliverable send is
     *         never reported as a healthy sweep.
     */
    public boolean processSend(int workspaceId, int sendId) {
        if (!capabilityRegistry.isAvailable(Capability.CAMPAIGN_DELIVERY)) {
            return true;
        }
        recoverExpiredTriggeredClaims(workspaceId);
        return processSendReady(workspaceId, sendId);
    }

    private boolean processSendReady(int workspaceId, int sendId) {
        CampaignSend send = campaignSendMapper.getSend(workspaceId, sendId);
        if (send == null || !dispatchable(send)) {
            return true;
        }
        if (triggered(send) && !triggeredSendGate.enabled()) {
            return true;
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
            return false;
        }
        CampaignMessageRevision revision =
                campaignMessageMapper.getRevision(workspaceId, send.getMessageId(), send.getMessageVersion());
        if (revision == null) {
            log.warn("Campaign send {} references a missing message revision", sendId);
            return false;
        }
        campaignSendMapper.assignProvider(workspaceId, sendId, target.providerId());
        if (!triggered(send)) {
            campaignSendMapper.markRunning(workspaceId, sendId);
        }
        CampaignSend running = campaignSendMapper.getSend(workspaceId, sendId);
        if (running == null || !dispatchable(running)) {
            return true;
        }
        for (int deliveryId : campaignDeliveryMapper.pendingDeliveryIdsPage(
                workspaceId, sendId, triggeredSendGate.dispatchPageSize())) {
            CampaignSend current = campaignSendMapper.getSend(workspaceId, sendId);
            if (current == null || !dispatchable(current)
                    || (triggered(current) && !triggeredSendGate.enabled())) {
                break;
            }
            dispatchOne(workspaceId, send, channel, target, dispatcher, revision, deliveryId);
        }
        campaignSendMapper.refreshCounters(workspaceId, sendId);
        CampaignSend settled = campaignSendMapper.getSend(workspaceId, sendId);
        if (settled != null && "running".equals(settled.getStatus())
                && "audience".equals(settled.getOrigin())
                && campaignDeliveryMapper.countPending(workspaceId, sendId) == 0) {
            campaignSendMapper.markCompleted(workspaceId, sendId);
        }
        return true;
    }

    private void dispatchOne(int workspaceId, CampaignSend send, DeliveryChannel channel,
            ResolvedDeliveryProvider target, MessageDispatcher dispatcher,
            CampaignMessageRevision revision, int deliveryId) {
        boolean triggered = triggered(send);
        String leaseOwner = triggered ? UUID.randomUUID().toString() : null;
        int claimed = triggered
            ? campaignDeliveryMapper.claimTriggered(
                workspaceId,
                deliveryId,
                leaseOwner,
                dispatchLeaseMicros(),
                target.providerId(),
                target.attemptTargetFingerprint())
            : campaignDeliveryMapper.claim(workspaceId, deliveryId);
        if (claimed != 1) {
            if (triggered && campaignDeliveryMapper.markPendingTriggeredTargetMismatchAmbiguous(
                    workspaceId,
                    deliveryId,
                    target.attemptTargetFingerprint(),
                    RECOVERED_CHANGED_TARGET_CLAIM,
                    CampaignDeliveryFailureReason.DELIVERY_TARGET_CHANGED.token()) == 1) {
                try {
                    appendEvent(
                            workspaceId,
                            deliveryId,
                            "failed",
                            RECOVERED_CHANGED_TARGET_CLAIM);
                } catch (RuntimeException exception) {
                    log.warn("Campaign delivery {} target-change event could not be appended", deliveryId);
                }
            }
            return;
        }
        try {
            claimBoundary.afterClaim(workspaceId, deliveryId);
            dispatchClaimed(
                workspaceId, send, channel, target, dispatcher, revision, deliveryId,
                leaseOwner);
        } catch (AmbiguousDispatchException exception) {
            recordAmbiguous(workspaceId, deliveryId, leaseOwner,
                    "AMBIGUOUS: Provider outcome could not be persisted definitively",
                    CampaignDeliveryFailureReason.RELAY_ERROR.token());
            log.warn("Campaign delivery {} requires provider reconciliation", deliveryId);
        } catch (Exception exception) {
            String lastError = bounded(exception.getMessage());
            markFailed(
                    workspaceId,
                    deliveryId,
                    leaseOwner,
                    lastError,
                    CampaignDeliveryFailureReason.classify(lastError, false).token());
            log.warn("Campaign delivery {} dispatch failed: {}", deliveryId, exception.getMessage());
        }
    }

    private void dispatchClaimed(int workspaceId, CampaignSend send, DeliveryChannel channel,
            ResolvedDeliveryProvider target, MessageDispatcher dispatcher,
            CampaignMessageRevision revision, int deliveryId, String leaseOwner) {
        if (leaseOwner != null && !triggeredSendGate.enabled()) {
            campaignDeliveryMapper.releaseTriggeredClaim(workspaceId, deliveryId, leaseOwner);
            return;
        }
        CampaignDelivery identity = campaignDeliveryMapper.getDeliveryIdentity(workspaceId, deliveryId);
        if (identity == null) {
            return;
        }
        Integer personId = identity.getPersonId();
        if (personId != null
                && !audienceEligibilityService.restrictedIds(
                        workspaceId, List.of(personId)).isEmpty()) {
            markSkipped(workspaceId, deliveryId, leaseOwner, "restricted");
            return;
        }
        CampaignDelivery delivery = campaignDeliveryMapper.getDelivery(workspaceId, deliveryId);
        if (delivery == null) {
            return;
        }
        String skipReason = evaluate(workspaceId, send, channel, delivery);
        if (skipReason != null) {
            int updated = markSkipped(workspaceId, deliveryId, leaseOwner, skipReason);
            if (updated == 1 && "frequency_capped".equals(skipReason)) {
                workflowRunMapper.markActionDeliveryCapped(workspaceId, deliveryId);
            }
            return;
        }
        RenderedMessage content = render(channel, revision, delivery);
        long providerDeadlineNanos = providerDeadlineNanos();
        if (leaseOwner != null) {
            claimBoundary.beforeProviderLeaseRenewal(workspaceId, deliveryId);
            if (campaignDeliveryMapper.renewTriggeredClaim(
                    workspaceId, deliveryId, leaseOwner, dispatchLeaseMicros()) != 1) {
                return;
            }
            if (!triggeredSendGate.enabled()) {
                campaignDeliveryMapper.releaseTriggeredClaim(workspaceId, deliveryId, leaseOwner);
                return;
            }
        }
        if (providerDeadlineNanos - nanoTimeSource.getAsLong() <= 0) {
            markFailed(workspaceId, deliveryId, leaseOwner,
                    "Provider deadline expired before egress",
                    CampaignDeliveryFailureReason.PROVIDER_TIMEOUT.token());
            return;
        }
        DeliveryRequest request = new DeliveryRequest(
                channel, delivery.getAddress(), content, delivery.getPersonId(),
                "send:" + send.getId() + ":" + deliveryId, providerDeadlineNanos);
        DispatchReceipt receipt;
        try {
            receipt = dispatcher.dispatch(target, request);
        } catch (RuntimeException exception) {
            receipt = DispatchReceipt.ambiguous(
                    "Provider dispatch outcome is ambiguous and requires reconciliation");
        }
        if (receipt.status() == DispatchStatus.SENT) {
            int updated;
            try {
                updated = leaseOwner == null
                    ? campaignDeliveryMapper.markDispatched(
                        workspaceId, deliveryId, target.providerId(), receipt.providerMessageId())
                    : campaignDeliveryMapper.markTriggeredDispatched(
                        workspaceId, deliveryId, leaseOwner, target.providerId(),
                        receipt.providerMessageId());
            } catch (RuntimeException exception) {
                throw new AmbiguousDispatchException(exception);
            }
            if (updated == 1) {
                appendEvent(workspaceId, deliveryId, "dispatched", receipt.detail());
            }
        } else {
            String failure = receipt.status() == DispatchStatus.AMBIGUOUS
                    ? "AMBIGUOUS: " + receipt.detail()
                    : receipt.detail();
            String failureCode = CampaignDeliveryFailureReason.classify(
                    failure, receipt.status() == DispatchStatus.AMBIGUOUS).token();
            int updated = receipt.status() == DispatchStatus.AMBIGUOUS
                    ? markAmbiguousOrThrow(
                            workspaceId, deliveryId, leaseOwner, bounded(failure), failureCode)
                    : markFailed(
                            workspaceId, deliveryId, leaseOwner, bounded(failure), failureCode);
            if (updated == 1) {
                appendEvent(workspaceId, deliveryId, "failed", failure);
            }
        }
    }

    private int markSkipped(
            int workspaceId, int deliveryId, String leaseOwner, String skipReason) {
        return leaseOwner == null
                ? campaignDeliveryMapper.markSkipped(workspaceId, deliveryId, skipReason)
                : campaignDeliveryMapper.markTriggeredSkipped(
                    workspaceId, deliveryId, leaseOwner, skipReason);
    }

    private int markFailed(
            int workspaceId,
            int deliveryId,
            String leaseOwner,
            String lastError,
            String lastErrorCode) {
        return leaseOwner == null
            ? campaignDeliveryMapper.markFailed(
                    workspaceId, deliveryId, lastError, lastErrorCode)
            : campaignDeliveryMapper.markTriggeredFailed(
                    workspaceId, deliveryId, leaseOwner, lastError, lastErrorCode);
    }

    private int markAmbiguous(
            int workspaceId,
            int deliveryId,
            String leaseOwner,
            String lastError,
            String lastErrorCode) {
        return leaseOwner == null
            ? campaignDeliveryMapper.markAmbiguous(
                    workspaceId, deliveryId, lastError, lastErrorCode)
            : campaignDeliveryMapper.markTriggeredAmbiguous(
                    workspaceId, deliveryId, leaseOwner, lastError, lastErrorCode);
    }

    private int markAmbiguousOrThrow(
            int workspaceId,
            int deliveryId,
            String leaseOwner,
            String lastError,
            String lastErrorCode) {
        try {
            return markAmbiguous(
                    workspaceId, deliveryId, leaseOwner, lastError, lastErrorCode);
        } catch (RuntimeException exception) {
            throw new AmbiguousDispatchException(exception);
        }
    }

    private void recordAmbiguous(
            int workspaceId,
            int deliveryId,
            String leaseOwner,
            String lastError,
            String lastErrorCode) {
        int updated;
        try {
            updated = markAmbiguous(
                    workspaceId, deliveryId, leaseOwner, lastError, lastErrorCode);
        } catch (RuntimeException firstFailure) {
            try {
                updated = markAmbiguous(
                        workspaceId, deliveryId, leaseOwner, lastError, lastErrorCode);
            } catch (RuntimeException secondFailure) {
                log.warn("Campaign delivery {} ambiguous outcome remains claim-owned for recovery",
                        deliveryId);
                return;
            }
        }
        if (updated == 1) {
            try {
                appendEvent(workspaceId, deliveryId, "failed", lastError);
            } catch (RuntimeException exception) {
                log.warn("Campaign delivery {} ambiguous event could not be appended", deliveryId);
            }
        }
    }

    private void recoverExpiredTriggeredClaims(int workspaceId) {
        for (CampaignDelivery claim : campaignDeliveryMapper.expiredTriggeredClaimsPage(
                workspaceId, triggeredSendGate.dispatchPageSize())) {
            boolean replaySafe = false;
            boolean targetChanged = false;
            try {
                DeliveryChannel channel = DeliveryChannel.fromToken(claim.getChannel());
                ResolvedDeliveryProvider current =
                        deliveryProviderConfigService.resolveForWorkspace(workspaceId, channel);
                targetChanged = !current.providerId().equals(claim.getProviderId())
                        || !current.attemptTargetFingerprint().equals(
                                claim.getAttemptTargetFingerprint());
                replaySafe = !targetChanged
                        && current.idempotentSubmission();
            } catch (RuntimeException exception) {
                log.warn("Expired campaign delivery {} has no replay-safe transport capability",
                        claim.getId());
            }
            if (replaySafe) {
                campaignDeliveryMapper.recoverExpiredTriggeredClaim(
                        workspaceId, claim.getId(), claim.getAttemptTargetFingerprint());
            } else if (campaignDeliveryMapper.markExpiredTriggeredClaimAmbiguous(
                    workspaceId,
                    claim.getId(),
                    targetChanged ? EXPIRED_CHANGED_TARGET_CLAIM : EXPIRED_NON_IDEMPOTENT_CLAIM,
                    targetChanged
                            ? CampaignDeliveryFailureReason.DELIVERY_TARGET_CHANGED.token()
                            : CampaignDeliveryFailureReason.DEADLINE_AMBIGUOUS.token()) == 1) {
                try {
                    appendEvent(
                            workspaceId,
                            claim.getId(),
                            "failed",
                            targetChanged
                                    ? EXPIRED_CHANGED_TARGET_CLAIM
                                    : EXPIRED_NON_IDEMPOTENT_CLAIM);
                } catch (RuntimeException exception) {
                    log.warn("Campaign delivery {} recovery event could not be appended", claim.getId());
                }
            }
        }
    }

    private long dispatchLeaseMicros() {
        return deliveryProperties.providerCallLeaseDuration().toNanos() / 1_000L;
    }

    private long providerDeadlineNanos() {
        return Math.addExact(
                nanoTimeSource.getAsLong(), deliveryProperties.providerCallDeadline().toNanos());
    }

    private String evaluate(int workspaceId, CampaignSend send, DeliveryChannel channel, CampaignDelivery delivery) {
        Integer personId = delivery.getPersonId();
        String channelToken = channel.token();
        String address = delivery.getAddress();
        if (address == null || address.isBlank()) {
            return "no_address";
        }
        String normalizedAddress = ChannelAddressNormalizer.normalize(channel, address);
        if (normalizedAddress == null) {
            return "no_address";
        }
        if (!audienceEligibilityService.suppressedAddresses(
                workspaceId, channelToken, List.of(normalizedAddress)).isEmpty()) {
            return "suppressed";
        }
        if (personId != null && !audienceEligibilityService
                .suppressedPersonRefIds(workspaceId, List.of(personId), channelToken).isEmpty()) {
            return "suppressed";
        }
        if (audienceEligibilityService.consentBlocks(workspaceId, personId, channelToken, send.getPurpose())) {
            return AudienceEligibilityService.CONSENT_POLICY.exclusionReason();
        }
        if (personId != null && FREQUENCY_WINDOW_HOURS > 0) {
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

    private static boolean dispatchable(CampaignSend send) {
        return "queued".equals(send.getStatus())
                || "running".equals(send.getStatus())
                || triggered(send);
    }

    private static boolean triggered(CampaignSend send) {
        return "triggered".equals(send.getOrigin()) && "triggered".equals(send.getStatus());
    }

    /**
     * Renders the revision for one recipient under the send's channel. SMS is text-only — its revisions
     * carry no subject or HTML body — so only the text body is rendered and handed to the adapter.
     */
    private RenderedMessage render(
            DeliveryChannel channel, CampaignMessageRevision revision, CampaignDelivery delivery) {
        Map<String, String> tokens = Map.of("unsubscribe_url", unsubscribeUrl(delivery.getUnsubscribeToken()));
        String text = revision.getBodyText() == null ? null : substitutePlain(revision.getBodyText(), tokens);
        if (channel == DeliveryChannel.SMS) {
            return new RenderedMessage(null, null, text);
        }
        String subject = revision.getSubject() == null ? null : substitutePlain(revision.getSubject(), tokens);
        String html = revision.getBodyHtml() == null ? null : substituteHtml(revision.getBodyHtml(), tokens);
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

    private static final class AmbiguousDispatchException extends RuntimeException {

        private AmbiguousDispatchException(Throwable cause) {
            super(cause);
        }
    }
}
