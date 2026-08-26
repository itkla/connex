package ooo.klae.connex.backend.services;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.ObjectMapper;

import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.DealDocument;
import ooo.klae.connex.backend.beans.DocumentDelivery;
import ooo.klae.connex.backend.beans.DocumentDeliveryEvent;
import ooo.klae.connex.backend.beans.DocumentDeliveryRecipient;
import ooo.klae.connex.backend.beans.Workspace;
import ooo.klae.connex.backend.capability.Capability;
import ooo.klae.connex.backend.capability.CapabilityRegistry;
import ooo.klae.connex.backend.dto.AcceptDocumentRequest;
import ooo.klae.connex.backend.dto.DeclineDocumentRequest;
import ooo.klae.connex.backend.dto.DocumentAcceptanceDecisionDto;
import ooo.klae.connex.backend.dto.DocumentAcceptancePreviewDto;
import ooo.klae.connex.backend.dto.DocumentContent;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.exceptions.ServiceUnavailableException;
import ooo.klae.connex.backend.mappers.DealDocumentMapper;
import ooo.klae.connex.backend.mappers.DealMapper;
import ooo.klae.connex.backend.mappers.DocumentDeliveryMapper;
import ooo.klae.connex.backend.mappers.WorkspaceMapper;
import ooo.klae.connex.backend.signature.DocumentAcceptanceToken;
import ooo.klae.connex.backend.signature.SignatureProperties;
import ooo.klae.connex.backend.util.ContactMask;

/**
 * Resolves public document links into their routed tenant before opening a write transaction.
 * Entry points deliberately remain non-transactional so catalog placement is pinned first.
 */
@Service
@RequiredArgsConstructor
public class DocumentAcceptanceService {
    private static final String UNAVAILABLE = "Document link is no longer available";
    private static final List<String> LIVE = List.of("sent", "viewed");

    private final DocumentDeliveryMapper deliveryMapper;
    private final DealDocumentMapper documentMapper;
    private final DealMapper dealMapper;
    private final WorkspaceMapper workspaceMapper;
    private final DocumentDeliveryLifecycleService lifecycleService;
    private final AuditService auditService;
    private final SignatureProperties signatureProperties;
    private final CapabilityRegistry capabilityRegistry;
    private final SystemActor systemActor;
    private final AutomationExecutor automationExecutor;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;

    /**
     * Returns the frozen document. Records nothing.
     *
     * <p>A browser opening an emailed link issues a {@code GET}, and so do email security scanners,
     * link prefetchers and URL-rewriting proxies. Stamping the first view here would let any of them
     * forge "the recipient viewed this at ..." into the completion certificate, which is the one
     * artifact whose value is trustworthy attribution. The view is recorded by
     * {@link #markViewed(String, String)} instead, which the rendered recipient page calls.
     */
    public DocumentAcceptancePreviewDto preview(String token, String sourceAddress) {
        Link link = admit(token);
        DocumentAcceptancePreviewDto result = automationExecutor.runAs(
            link.workspace().getId(),
            systemActor.user(),
            "system",
            () -> transactionTemplate.execute(status -> previewInTransaction(link)));
        return Objects.requireNonNull(result, "document preview result");
    }

    /**
     * Idempotently records that the recipient opened the document. Safe to call repeatedly: only the
     * first call stamps {@code first_viewed_at} and appends the {@code viewed} event.
     */
    public DocumentAcceptancePreviewDto markViewed(String token, String sourceAddress) {
        Link link = admit(token);
        DocumentAcceptancePreviewDto result = automationExecutor.runAs(
            link.workspace().getId(),
            systemActor.user(),
            "system",
            () -> transactionTemplate.execute(status -> markViewedInTransaction(link)));
        return Objects.requireNonNull(result, "document view result");
    }

    private DocumentAcceptancePreviewDto markViewedInTransaction(Link link) {
        Aggregate aggregate = lockAggregate(link, true);
        requireActionable(aggregate.delivery(), aggregate.recipient(), now());
        if (aggregate.recipient().getFirstViewedAt() == null) {
            LocalDateTime viewedAt = now();
            if (deliveryMapper.markRecipientViewed(
                    link.workspace().getId(),
                    aggregate.delivery().getId(),
                    aggregate.recipient().getId(),
                    viewedAt) != 1) {
                throw unavailable();
            }
            deliveryMapper.markDeliveryViewed(
                link.workspace().getId(), aggregate.delivery().getId());
            appendRecipientEvent(
                link.workspace().getId(),
                aggregate.delivery().getId(),
                aggregate.recipient().getId(),
                "viewed",
                viewedAt);
            aggregate.delivery().setStatus("viewed");
            aggregate.recipient().setStatus("viewed");
        }
        DocumentAcceptancePreviewDto viewed = new DocumentAcceptancePreviewDto(
            parseContent(aggregate.document()),
            aggregate.deal().getName(),
            link.workspace().getName(),
            ContactMask.maskEmail(aggregate.recipient().getEmail()),
            aggregate.delivery().getStatus(),
            aggregate.recipient().getStatus(),
            true);
        auditRecipientOperation(
            aggregate, "document_delivery.preview", "Viewed a delivered document");
        return viewed;
    }

    /** Records one signer acceptance and completes the envelope after the last signer. */
    public DocumentAcceptanceDecisionDto accept(
            String token,
            AcceptDocumentRequest request,
            String sourceAddress,
            String userAgent) {
        Link link = admit(token);
        DocumentAcceptanceDecisionDto result = automationExecutor.runAs(
            link.workspace().getId(),
            systemActor.user(),
            "system",
            () -> transactionTemplate.execute(status -> acceptInTransaction(
                link, request.typedName().trim(), sourceAddress, userAgent)));
        return Objects.requireNonNull(result, "document acceptance result");
    }

    /** Records one signer decline and terminally closes the envelope. */
    public DocumentAcceptanceDecisionDto decline(
            String token,
            DeclineDocumentRequest request,
            String sourceAddress,
            String userAgent) {
        Link link = admit(token);
        DocumentAcceptanceDecisionDto result = automationExecutor.runAs(
            link.workspace().getId(),
            systemActor.user(),
            "system",
            () -> transactionTemplate.execute(status -> declineInTransaction(
                link, request.reason().trim(), sourceAddress, userAgent)));
        return Objects.requireNonNull(result, "document decline result");
    }

    private DocumentAcceptancePreviewDto previewInTransaction(Link link) {
        Aggregate aggregate = lockAggregate(link, false);
        requireActionable(aggregate.delivery(), aggregate.recipient(), now());
        DocumentAcceptancePreviewDto preview = new DocumentAcceptancePreviewDto(
            parseContent(aggregate.document()),
            aggregate.deal().getName(),
            link.workspace().getName(),
            ContactMask.maskEmail(aggregate.recipient().getEmail()),
            aggregate.delivery().getStatus(),
            aggregate.recipient().getStatus(),
            true);
        return preview;
    }

    private DocumentAcceptanceDecisionDto acceptInTransaction(
            Link link, String typedName, String sourceAddress, String userAgent) {
        Aggregate aggregate = lockAggregate(link, true);
        if ("completed".equals(aggregate.recipient().getStatus())) {
            auditRecipientOperation(
                aggregate,
                "document_delivery.recipient_accept",
                "Repeated a completed document acceptance");
            return new DocumentAcceptanceDecisionDto(
                aggregate.delivery().getStatus(), "completed",
                "completed".equals(aggregate.delivery().getStatus()));
        }
        requireActionable(aggregate.delivery(), aggregate.recipient(), now());
        if (!"signer".equals(aggregate.recipient().getRole())) {
            throw unavailable();
        }
        LocalDateTime decidedAt = now();
        Evidence evidence = evidence(link, aggregate, sourceAddress, userAgent);
        if (deliveryMapper.completeRecipient(
                link.workspace().getId(),
                aggregate.delivery().getId(),
                aggregate.recipient().getId(),
                typedName,
                evidence.ipHash(),
                evidence.agentHash(),
                decidedAt) != 1) {
            throw unavailable();
        }
        appendRecipientEvent(
            link.workspace().getId(),
            aggregate.delivery().getId(),
            aggregate.recipient().getId(),
            "completed",
            decidedAt);
        boolean completed = lifecycleService.complete(
            link.workspace().getId(),
            aggregate.deal(),
            aggregate.document(),
            aggregate.delivery(),
            decidedAt);
        auditRecipientOperation(
            aggregate,
            "document_delivery.recipient_accept",
            "Accepted a delivered document");
        return new DocumentAcceptanceDecisionDto(
            completed ? "completed" : aggregate.delivery().getStatus(),
            "completed",
            completed);
    }

    private DocumentAcceptanceDecisionDto declineInTransaction(
            Link link, String reason, String sourceAddress, String userAgent) {
        Aggregate aggregate = lockAggregate(link, true);
        if ("declined".equals(aggregate.recipient().getStatus())) {
            auditRecipientOperation(
                aggregate,
                "document_delivery.recipient_decline",
                "Repeated a completed document decline");
            return new DocumentAcceptanceDecisionDto("declined", "declined", false);
        }
        requireActionable(aggregate.delivery(), aggregate.recipient(), now());
        if (!"signer".equals(aggregate.recipient().getRole())) {
            throw unavailable();
        }
        LocalDateTime decidedAt = now();
        Evidence evidence = evidence(link, aggregate, sourceAddress, userAgent);
        if (deliveryMapper.declineRecipient(
                link.workspace().getId(),
                aggregate.delivery().getId(),
                aggregate.recipient().getId(),
                reason,
                evidence.ipHash(),
                evidence.agentHash(),
                decidedAt) != 1) {
            throw unavailable();
        }
        if (!lifecycleService.terminate(
                link.workspace().getId(),
                aggregate.deal(),
                aggregate.document(),
                aggregate.delivery(),
                "declined",
                reason,
                aggregate.recipient().getId(),
                "recipient",
                null,
                decidedAt)) {
            throw unavailable();
        }
        auditRecipientOperation(
            aggregate,
            "document_delivery.recipient_decline",
            "Declined a delivered document");
        return new DocumentAcceptanceDecisionDto("declined", "declined", false);
    }

    private void auditRecipientOperation(
            Aggregate aggregate, String action, String summary) {
        auditService.recordWithoutRequestMetadata(
            action,
            "deal",
            aggregate.deal().getId(),
            aggregate.deal().getName(),
            summary,
            Map.of(
                "documentId", aggregate.document().getId(),
                "deliveryId", aggregate.delivery().getId(),
                "recipientId", aggregate.recipient().getId()));
    }

    private Aggregate lockAggregate(Link link, boolean lockAllRecipients) {
        int workspaceId = link.workspace().getId();
        DocumentDeliveryRecipient discovered =
            deliveryMapper.getRecipientByTokenHash(workspaceId, link.tokenHash());
        if (discovered == null) {
            throw unavailable();
        }
        DocumentDelivery discoveredDelivery =
            deliveryMapper.getById(workspaceId, discovered.getDeliveryId());
        if (discoveredDelivery == null) {
            throw unavailable();
        }
        Deal deal = dealMapper.getDealByIdForUpdate(
            workspaceId, discoveredDelivery.getDealId());
        if (deal == null) {
            throw unavailable();
        }
        DealDocument document =
            documentMapper.lockById(workspaceId, discoveredDelivery.getDocumentId());
        if (document == null || document.getDealId() != discoveredDelivery.getDealId()) {
            throw unavailable();
        }
        DocumentDelivery delivery =
            deliveryMapper.lockById(workspaceId, discoveredDelivery.getId());
        if (delivery == null || delivery.getDocumentId() != document.getId()) {
            throw unavailable();
        }
        List<DocumentDeliveryRecipient> locked = lockAllRecipients
            ? lockRecipientsAscending(workspaceId, delivery.getId())
            : List.of(requireLockedRecipient(
                workspaceId, delivery.getId(), discovered.getId()));
        DocumentDeliveryRecipient recipient = locked.stream()
            .filter(candidate -> candidate.getId() == discovered.getId())
            .findFirst()
            .orElseThrow(DocumentAcceptanceService::unavailable);
        requireSameHash(link.tokenHash(), recipient.getTokenHash());
        if (delivery.getDealId() != deal.getId()) {
            throw unavailable();
        }
        return new Aggregate(deal, document, delivery, recipient);
    }

    private List<DocumentDeliveryRecipient> lockRecipientsAscending(
            int workspaceId, int deliveryId) {
        ArrayList<Integer> ids = new ArrayList<>(
            deliveryMapper.getRecipientIds(workspaceId, deliveryId));
        ids.sort(Integer::compareTo);
        ArrayList<DocumentDeliveryRecipient> recipients = new ArrayList<>();
        for (int id : ids) {
            recipients.add(requireLockedRecipient(workspaceId, deliveryId, id));
        }
        return recipients;
    }

    private DocumentDeliveryRecipient requireLockedRecipient(
            int workspaceId, int deliveryId, int recipientId) {
        DocumentDeliveryRecipient recipient =
            deliveryMapper.lockRecipient(workspaceId, deliveryId, recipientId);
        if (recipient == null) {
            throw unavailable();
        }
        return recipient;
    }

    private Link admit(String token) {
        requireAvailable();
        if (!DocumentAcceptanceToken.hasValidShape(token)) {
            throw unavailable();
        }
        String tokenHash = DocumentAcceptanceToken.hash(token);
        int workspaceId;
        try {
            workspaceId = DocumentAcceptanceToken.workspaceId(token);
        } catch (IllegalArgumentException exception) {
            throw unavailable();
        }
        Workspace workspace = workspaceMapper.getActiveById(workspaceId);
        if (workspace == null) {
            throw unavailable();
        }
        return new Link(workspace, tokenHash);
    }

    private void requireAvailable() {
        if (!signatureProperties.isEnabled()
                || !capabilityRegistry.isAvailable(Capability.DOCUMENT_SIGNATURE)) {
            throw new ServiceUnavailableException("Document signature delivery is unavailable");
        }
    }

    private static void requireActionable(
            DocumentDelivery delivery,
            DocumentDeliveryRecipient recipient,
            LocalDateTime at) {
        if (!LIVE.contains(delivery.getStatus())
                || !("pending".equals(recipient.getStatus())
                    || "viewed".equals(recipient.getStatus()))
                || delivery.getExpiresAt() != null && !delivery.getExpiresAt().isAfter(at)
                || recipient.getTokenExpiresAt() != null
                    && !recipient.getTokenExpiresAt().isAfter(at)) {
            throw unavailable();
        }
    }

    private Evidence evidence(
            Link link, Aggregate aggregate, String sourceAddress, String userAgent) {
        String scope = link.workspace().getId() + ":" + aggregate.delivery().getId()
            + ":" + aggregate.recipient().getId();
        return new Evidence(
            hmac(link.tokenHash(), "ip:" + scope, normalizedEvidence(sourceAddress)),
            hmac(link.tokenHash(), "agent:" + scope, normalizedEvidence(userAgent)));
    }

    private void appendRecipientEvent(
            int workspaceId,
            int deliveryId,
            int recipientId,
            String type,
            LocalDateTime occurredAt) {
        DocumentDeliveryEvent event = new DocumentDeliveryEvent();
        event.setWorkspaceId(workspaceId);
        event.setDeliveryId(deliveryId);
        event.setRecipientId(recipientId);
        event.setEventType(type);
        event.setSource("recipient");
        event.setOccurredAt(occurredAt);
        if (deliveryMapper.insertEvent(event) != 1) {
            throw new IllegalStateException("Document-recipient event was not persisted");
        }
    }

    private DocumentContent parseContent(DealDocument document) {
        if (document.getContent() == null) {
            throw unavailable();
        }
        try {
            return objectMapper.readValue(document.getContent(), DocumentContent.class);
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Frozen document content is invalid", exception);
        }
    }

    private static void requireSameHash(String expected, String actual) {
        if (actual == null || !MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.US_ASCII),
                actual.getBytes(StandardCharsets.US_ASCII))) {
            throw unavailable();
        }
    }

    private static String normalizedEvidence(String value) {
        return value == null || value.isBlank() ? "unresolved" : value.trim();
    }

    private static String hmac(String key, String purpose, String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.US_ASCII), "HmacSHA256"));
            mac.update(purpose.getBytes(StandardCharsets.UTF_8));
            mac.update((byte) 0);
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable", exception);
        }
    }

    private static LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }

    private static ResourceNotFoundException unavailable() {
        return new ResourceNotFoundException(UNAVAILABLE);
    }

    private record Link(Workspace workspace, String tokenHash) {
    }

    private record Aggregate(
            Deal deal,
            DealDocument document,
            DocumentDelivery delivery,
            DocumentDeliveryRecipient recipient) {
    }

    private record Evidence(String ipHash, String agentHash) {
    }
}
