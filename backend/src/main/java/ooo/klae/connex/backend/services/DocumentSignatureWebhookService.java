package ooo.klae.connex.backend.services;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import lombok.RequiredArgsConstructor;

import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.DealDocument;
import ooo.klae.connex.backend.beans.DocumentDelivery;
import ooo.klae.connex.backend.beans.DocumentDeliveryRecipient;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.DealDocumentMapper;
import ooo.klae.connex.backend.mappers.DealMapper;
import ooo.klae.connex.backend.mappers.DocumentDeliveryMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.signature.DocumentSignatureProviderRouter;
import ooo.klae.connex.backend.signature.ProviderEvent;

/** Authenticates, tenant-routes, and idempotently applies provider signature callbacks. */
@Service
@RequiredArgsConstructor
public class DocumentSignatureWebhookService {
    private static final Set<String> TERMINAL =
        Set.of("completed", "declined", "expired", "voided");

    private final DocumentSignatureProviderRouter providerRouter;
    private final DocumentDeliveryMapper deliveryMapper;
    private final DealDocumentMapper documentMapper;
    private final DealMapper dealMapper;
    private final WorkspaceMapper workspaceMapper;
    private final DocumentDeliveryLifecycleService lifecycleService;
    private final AuditService auditService;
    private final SystemActor systemActor;
    private final AutomationExecutor automationExecutor;
    private final TransactionTemplate transactionTemplate;

    /** Applies one provider-authenticated callback, returning false for unsupported webhook keys. */
    public boolean ingest(String provider, Map<String, String> headers, byte[] body) {
        ProviderEvent event = providerRouter.parseWebhook(provider, headers, body)
            .orElseThrow(() -> new ResourceNotFoundException("Signature webhook was not found"));
        validate(event);
        if (workspaceMapper.getActiveById(event.workspaceId()) == null) {
            throw new ResourceNotFoundException("Signature envelope was not found");
        }
        Boolean applied = automationExecutor.runAs(
            event.workspaceId(),
            systemActor.user(),
            "system",
            () -> transactionTemplate.execute(status -> apply(provider, event)));
        return Boolean.TRUE.equals(applied);
    }

    private boolean apply(String provider, ProviderEvent event) {
        int workspaceId = event.workspaceId();
        DocumentDelivery discovered = deliveryMapper.findByProviderEnvelope(
            workspaceId, provider, event.providerEnvelopeId());
        if (discovered == null) {
            throw new ResourceNotFoundException("Signature envelope was not found");
        }
        DealDocument document = documentMapper.lockById(workspaceId, discovered.getDocumentId());
        DocumentDelivery delivery = deliveryMapper.lockById(workspaceId, discovered.getId());
        if (document == null || delivery == null || document.getId() != delivery.getDocumentId()) {
            throw new ResourceNotFoundException("Signature envelope was not found");
        }
        List<DocumentDeliveryRecipient> recipients =
            lockRecipientsAscending(workspaceId, delivery.getId());
        if (deliveryMapper.hasExternalEvent(
                workspaceId, delivery.getId(), event.externalEventId())) {
            auditWebhook(provider, delivery, null, event, false);
            return false;
        }
        DocumentDeliveryRecipient recipient = recipientFor(recipients, event.providerRecipientId());
        LocalDateTime occurredAt = event.occurredAt() == null
            ? LocalDateTime.now(ZoneOffset.UTC)
            : event.occurredAt();
        if (TERMINAL.contains(delivery.getStatus())) {
            lifecycleService.appendProviderEvent(
                workspaceId,
                delivery.getId(),
                recipient == null ? null : recipient.getId(),
                event.eventType(),
                event.externalEventId(),
                bounded(event.detail(), 500),
                occurredAt);
            auditWebhook(provider, delivery, recipient, event, true);
            return true;
        }
        Deal deal = dealMapper.getDealById(workspaceId, delivery.getDealId());
        if (deal == null) {
            throw new IllegalStateException("Signature envelope has no deal");
        }
        boolean applied = applyLive(
            workspaceId, deal, document, delivery, recipient, event, occurredAt);
        auditWebhook(provider, delivery, recipient, event, applied);
        return applied;
    }

    private void auditWebhook(
            String provider,
            DocumentDelivery delivery,
            DocumentDeliveryRecipient recipient,
            ProviderEvent event,
            boolean applied) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("deliveryId", delivery.getId());
        metadata.put("provider", provider);
        metadata.put("eventType", event.eventType());
        metadata.put("applied", applied);
        if (recipient != null) {
            metadata.put("recipientId", recipient.getId());
        }
        auditService.record(
            "document_delivery.webhook",
            "deal",
            delivery.getDealId(),
            null,
            applied
                ? "Applied an authenticated document-signature webhook"
                : "Ignored a replayed document-signature webhook",
            metadata);
    }

    private boolean applyLive(
            int workspaceId,
            Deal deal,
            DealDocument document,
            DocumentDelivery delivery,
            DocumentDeliveryRecipient recipient,
            ProviderEvent event,
            LocalDateTime occurredAt) {
        return switch (event.eventType()) {
            case "viewed" -> applyViewed(
                workspaceId, delivery, requireRecipient(recipient), event, occurredAt);
            case "completed" -> applyCompleted(
                workspaceId, deal, document, delivery, requireRecipient(recipient), event, occurredAt);
            case "declined" -> applyDeclined(
                workspaceId, deal, document, delivery, requireRecipient(recipient), event, occurredAt);
            case "expired", "voided" -> lifecycleService.terminate(
                    workspaceId,
                    deal,
                    document,
                    delivery,
                    event.eventType(),
                    bounded(event.detail(), 500),
                    recipient == null ? null : recipient.getId(),
                    "provider",
                    event.externalEventId(),
                    occurredAt);
            default -> {
                lifecycleService.appendProviderEvent(
                    workspaceId,
                    delivery.getId(),
                    recipient == null ? null : recipient.getId(),
                    event.eventType(),
                    event.externalEventId(),
                    bounded(event.detail(), 500),
                    occurredAt);
                yield true;
            }
        };
    }

    private boolean applyViewed(
            int workspaceId,
            DocumentDelivery delivery,
            DocumentDeliveryRecipient recipient,
            ProviderEvent event,
            LocalDateTime occurredAt) {
        deliveryMapper.markRecipientViewed(
            workspaceId, delivery.getId(), recipient.getId(), occurredAt);
        deliveryMapper.markDeliveryViewed(workspaceId, delivery.getId());
        lifecycleService.appendProviderEvent(
            workspaceId,
            delivery.getId(),
            recipient.getId(),
            "viewed",
            event.externalEventId(),
            bounded(event.detail(), 500),
            occurredAt);
        return true;
    }

    private boolean applyCompleted(
            int workspaceId,
            Deal deal,
            DealDocument document,
            DocumentDelivery delivery,
            DocumentDeliveryRecipient recipient,
            ProviderEvent event,
            LocalDateTime occurredAt) {
        if (!"completed".equals(recipient.getStatus())) {
            if (!("pending".equals(recipient.getStatus())
                    || "viewed".equals(recipient.getStatus()))) {
                lifecycleService.appendProviderEvent(
                    workspaceId,
                    delivery.getId(),
                    recipient.getId(),
                    "completed",
                    event.externalEventId(),
                    bounded(event.detail(), 500),
                    occurredAt);
                return true;
            }
            if (deliveryMapper.completeProviderRecipient(
                    workspaceId,
                    delivery.getId(),
                    recipient.getId(),
                    occurredAt) != 1) {
                throw new IllegalStateException("Provider recipient completion could not be applied");
            }
        }
        lifecycleService.appendProviderEvent(
            workspaceId,
            delivery.getId(),
            recipient.getId(),
            "completed",
            event.externalEventId(),
            bounded(event.detail(), 500),
            occurredAt);
        lifecycleService.complete(
            workspaceId,
            deal,
            document,
            delivery,
            occurredAt,
            event.signedArtifact());
        return true;
    }

    private boolean applyDeclined(
            int workspaceId,
            Deal deal,
            DealDocument document,
            DocumentDelivery delivery,
            DocumentDeliveryRecipient recipient,
            ProviderEvent event,
            LocalDateTime occurredAt) {
        if (!("pending".equals(recipient.getStatus())
                || "viewed".equals(recipient.getStatus()))) {
            lifecycleService.appendProviderEvent(
                workspaceId,
                delivery.getId(),
                recipient.getId(),
                "declined",
                event.externalEventId(),
                bounded(event.detail(), 500),
                occurredAt);
            return true;
        }
        String reason = bounded(event.detail(), 500);
        if (deliveryMapper.declineRecipient(
                workspaceId,
                delivery.getId(),
                recipient.getId(),
                reason,
                null,
                null,
                occurredAt) != 1) {
            throw new IllegalStateException("Provider recipient decline could not be applied");
        }
        return lifecycleService.terminate(
            workspaceId,
            deal,
            document,
            delivery,
            "declined",
            reason,
            recipient.getId(),
            "provider",
            event.externalEventId(),
            occurredAt);
    }

    private List<DocumentDeliveryRecipient> lockRecipientsAscending(
            int workspaceId, int deliveryId) {
        ArrayList<Integer> ids = new ArrayList<>(
            deliveryMapper.getRecipientIds(workspaceId, deliveryId));
        ids.sort(Integer::compareTo);
        ArrayList<DocumentDeliveryRecipient> recipients = new ArrayList<>();
        for (int id : ids) {
            DocumentDeliveryRecipient recipient =
                deliveryMapper.lockRecipient(workspaceId, deliveryId, id);
            if (recipient == null) {
                throw new IllegalStateException("Signature recipient disappeared during callback");
            }
            recipients.add(recipient);
        }
        return recipients;
    }

    private static DocumentDeliveryRecipient recipientFor(
            List<DocumentDeliveryRecipient> recipients, String providerRecipientId) {
        if (providerRecipientId == null || providerRecipientId.isBlank()) {
            return null;
        }
        return recipients.stream()
            .filter(recipient -> Objects.equals(
                providerRecipientId, recipient.getProviderRecipientId()))
            .findFirst()
            .orElseThrow(() -> new ResourceNotFoundException("Signature recipient was not found"));
    }

    private static DocumentDeliveryRecipient requireRecipient(
            DocumentDeliveryRecipient recipient) {
        if (recipient == null) {
            throw new ResourceNotFoundException("Signature recipient was not found");
        }
        return recipient;
    }

    private static void validate(ProviderEvent event) {
        if (event.workspaceId() <= 0
                || blank(event.providerEnvelopeId())
                || blank(event.externalEventId())
                || blank(event.eventType())
                || event.providerEnvelopeId().length() > 255
                || event.providerRecipientId() != null
                    && event.providerRecipientId().length() > 255
                || event.externalEventId().length() > 255
                || event.eventType().length() > 32
                || event.signedArtifact().isPresent()
                    && !"completed".equals(event.eventType())) {
            throw new IllegalStateException("Signature provider returned an invalid webhook event");
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String bounded(String value, int maximum) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() > maximum ? trimmed.substring(0, maximum) : trimmed;
    }
}
