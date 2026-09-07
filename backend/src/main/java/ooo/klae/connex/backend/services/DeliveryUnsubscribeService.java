package ooo.klae.connex.backend.services;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

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
import ooo.klae.connex.backend.services.OneTimeLinkFlowService.ResolvedFlow;
import ooo.klae.connex.backend.util.ContactMask;
import ooo.klae.connex.backend.util.OneTimeTokenDigest;

/**
 * Handles the public unsubscribe endpoints. The raw emailed token is seen once, by
 * {@link #exchange}, which turns it into the SHA-256 digest the generated
 * {@code campaign_delivery.unsubscribe_token_hash} column is keyed on; that digest alone identifies
 * a single {@code campaign_delivery} row, the workspace is resolved from that row and never trusted
 * from the request, so no caller-supplied id is honored. The whole operation is idempotent.
 *
 * <p>The only caller these endpoints have is an email recipient with no session, so
 * {@code TenantResolutionInterceptor} leaves the request thread unresolved and every
 * workspace-scoped statement would otherwise be refused by the {@code TenantScopeInterceptor}
 * backstop (#994). Everything after the exempt token lookup therefore runs inside
 * {@link AutomationExecutor#runAs}, which resolves the delivery's own workspace placement
 * fail-closed and installs the narrowly-permissioned system actor that {@link SuppressionService}
 * and {@link ConsentService} require.
 *
 * <p>That scope is installed <em>before</em> the transaction opens, not inside it:
 * {@code TenantWorkScope} refuses to change the pinned catalog while a transaction is already
 * active, because the transaction-bound connection keeps its original catalog. The write path
 * therefore opens its transaction through {@link TransactionTemplate} inside the scope. Neither
 * entry point may become {@code @Transactional} again; {@code DeliveryUnsubscribeServiceTest}
 * asserts that, because under {@code single-database} the wrong order is silently harmless and
 * would otherwise surface only on the first dedicated-placement tenant.
 *
 * <p>The token lookup itself still runs unrouted on the default catalog, so under
 * {@code catalog-per-placement} a dedicated-placement tenant's link resolves to nothing and the
 * recipient gets a 404. Resolving a token before its catalog is known is a separate problem.
 */
@Service
@RequiredArgsConstructor
public class DeliveryUnsubscribeService {

    private static final String EVENT_UNSUBSCRIBED = "unsubscribed";
    private static final Pattern TOKEN_SHAPE = Pattern.compile("[a-f0-9]{64}");
    private static final String INVALID_LINK = "Unsubscribe link is not valid";

    private final CampaignDeliveryMapper campaignDeliveryMapper;
    private final CampaignSendMapper campaignSendMapper;
    private final SuppressionService suppressionService;
    private final ConsentService consentService;
    private final SystemActor systemActor;
    private final AutomationExecutor automationExecutor;
    private final TransactionTemplate transactionTemplate;

    /**
     * Validates the raw emailed token once and returns the digest a browser grant is issued for.
     * Malformed and unknown tokens share one not-found response so nothing is enumerable.
     */
    public String exchange(String rawToken) {
        if (rawToken == null || !TOKEN_SHAPE.matcher(rawToken).matches()) {
            throw new ResourceNotFoundException(INVALID_LINK);
        }
        String tokenHash = OneTimeTokenDigest.sha256(rawToken);
        requireDelivery(tokenHash);
        return tokenHash;
    }

    /** Returns the confirmation payload for an exchanged unsubscribe flow. */
    public DeliveryUnsubscribeDto preview(ResolvedFlow flow) {
        CampaignDelivery delivery = requireDelivery(flow.sourceTokenHash());
        return inDeliveryWorkspace(delivery, () -> {
            CampaignSend send = requireSend(delivery);
            boolean unsubscribed = campaignDeliveryMapper.hasEvent(
                    delivery.getWorkspaceId(), delivery.getId(), EVENT_UNSUBSCRIBED);
            return new DeliveryUnsubscribeDto(
                    flow.flowId(),
                    send.getChannel(),
                    ContactMask.maskEmail(delivery.getAddress()),
                    unsubscribed);
        });
    }

    /**
     * Performs the unsubscribe: idempotent suppression, consent revocation, and an event. The flow
     * must already be bound to the preview identity the recipient confirmed.
     */
    public DeliveryUnsubscribeDto unsubscribe(ResolvedFlow flow) {
        CampaignDelivery delivery = requireDelivery(flow.sourceTokenHash());
        DeliveryUnsubscribeDto result = inDeliveryWorkspace(delivery,
                () -> transactionTemplate.execute(status -> apply(delivery, flow.flowId())));
        return Objects.requireNonNull(result, "unsubscribe result");
    }

    private DeliveryUnsubscribeDto apply(CampaignDelivery delivery, String flowId) {
        CampaignSend send = requireSend(delivery);
        int workspaceId = delivery.getWorkspaceId();
        if (campaignDeliveryMapper.hasEvent(workspaceId, delivery.getId(), EVENT_UNSUBSCRIBED)) {
            return new DeliveryUnsubscribeDto(
                    flowId, send.getChannel(), ContactMask.maskEmail(delivery.getAddress()), true);
        }
        suppressionService.add(new SuppressionEntryRequest(
                "workspace", send.getChannel(), delivery.getAddress(), delivery.getPersonId(),
                "unsubscribe", "Recipient unsubscribed via campaign link"));
        if (delivery.getPersonId() != null) {
            consentService.setForPerson(delivery.getPersonId(), new ContactChannelConsentRequest(
                    send.getChannel(), send.getPurpose(), "revoked", "unsubscribe", null,
                    LocalDateTime.now()));
        }
        CampaignDeliveryEvent event = new CampaignDeliveryEvent();
        event.setWorkspaceId(workspaceId);
        event.setDeliveryId(delivery.getId());
        event.setEventType(EVENT_UNSUBSCRIBED);
        event.setDetail("Recipient unsubscribed");
        campaignDeliveryMapper.insertEvent(event);
        return new DeliveryUnsubscribeDto(
                flowId, send.getChannel(), ContactMask.maskEmail(delivery.getAddress()), true);
    }

    private <T> T inDeliveryWorkspace(CampaignDelivery delivery, Supplier<T> work) {
        return automationExecutor.runAs(
                delivery.getWorkspaceId(), systemActor.user(), "system", work);
    }

    private CampaignDelivery requireDelivery(String tokenHash) {
        CampaignDelivery delivery = tokenHash == null
                ? null
                : campaignDeliveryMapper.getByTokenHash(tokenHash);
        if (delivery == null) {
            throw new ResourceNotFoundException(INVALID_LINK);
        }
        return delivery;
    }

    private CampaignSend requireSend(CampaignDelivery delivery) {
        CampaignSend send = campaignSendMapper.getSend(delivery.getWorkspaceId(), delivery.getSendId());
        if (send == null) {
            throw new ResourceNotFoundException(INVALID_LINK);
        }
        return send;
    }
}
