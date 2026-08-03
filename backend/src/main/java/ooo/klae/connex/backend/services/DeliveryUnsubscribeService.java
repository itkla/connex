package ooo.klae.connex.backend.services;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.function.Supplier;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
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

/**
 * Handles the public unsubscribe endpoints. The signed token alone identifies a single
 * {@code campaign_delivery} row; the workspace is resolved from that row and never trusted from the
 * request, so no caller-supplied id is honored. The whole operation is idempotent.
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
 * therefore opens its transaction through {@link TransactionTemplate} inside the scope, and both
 * entry points fail fast when a caller has already opened one — under {@code single-database} the
 * wrong order is silently harmless, so it has to be rejected rather than discovered on the first
 * dedicated-placement tenant.
 *
 * <p>The token lookup itself still runs unrouted on the default catalog, so under
 * {@code catalog-per-placement} a dedicated-placement tenant's link resolves to nothing and the
 * recipient gets a 404. Resolving a token before its catalog is known is a separate problem.
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
    private final TransactionTemplate transactionTemplate;

    /** Returns the confirmation payload for an unsubscribe link. */
    public DeliveryUnsubscribeDto preview(String token) {
        CampaignDelivery delivery = requireDelivery(token);
        return inDeliveryWorkspace(delivery, () -> {
            CampaignSend send = requireSend(delivery);
            boolean unsubscribed = campaignDeliveryMapper.hasEvent(
                    delivery.getWorkspaceId(), delivery.getId(), EVENT_UNSUBSCRIBED);
            return new DeliveryUnsubscribeDto(
                    send.getChannel(), maskAddress(delivery.getAddress()), unsubscribed);
        });
    }

    /** Performs the unsubscribe: idempotent suppression, consent revocation, and an event. */
    public DeliveryUnsubscribeDto unsubscribe(String token) {
        CampaignDelivery delivery = requireDelivery(token);
        DeliveryUnsubscribeDto result = inDeliveryWorkspace(delivery,
                () -> transactionTemplate.execute(status -> apply(delivery)));
        return Objects.requireNonNull(result, "unsubscribe result");
    }

    private DeliveryUnsubscribeDto apply(CampaignDelivery delivery) {
        CampaignSend send = requireSend(delivery);
        int workspaceId = delivery.getWorkspaceId();
        if (campaignDeliveryMapper.hasEvent(workspaceId, delivery.getId(), EVENT_UNSUBSCRIBED)) {
            return new DeliveryUnsubscribeDto(send.getChannel(), maskAddress(delivery.getAddress()), true);
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
        return new DeliveryUnsubscribeDto(send.getChannel(), maskAddress(delivery.getAddress()), true);
    }

    private <T> T inDeliveryWorkspace(CampaignDelivery delivery, Supplier<T> work) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "The delivery's workspace scope must be established before a transaction opens; "
                        + "a transaction-bound connection keeps the catalog it was checked out with");
        }
        return automationExecutor.runAs(
                delivery.getWorkspaceId(), systemActor.user(), "system", work);
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
