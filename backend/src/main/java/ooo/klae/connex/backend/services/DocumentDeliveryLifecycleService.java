package ooo.klae.connex.backend.services;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.RequiredArgsConstructor;
import tools.jackson.databind.ObjectMapper;

import ooo.klae.connex.backend.beans.Activity;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.DealDocument;
import ooo.klae.connex.backend.beans.DocumentApproval;
import ooo.klae.connex.backend.beans.DocumentDelivery;
import ooo.klae.connex.backend.beans.DocumentDeliveryArtifact;
import ooo.klae.connex.backend.beans.DocumentDeliveryEvent;
import ooo.klae.connex.backend.beans.DocumentDeliveryRecipient;
import ooo.klae.connex.backend.beans.Notification;
import ooo.klae.connex.backend.mappers.DealDocumentMapper;
import ooo.klae.connex.backend.mappers.DocumentApprovalMapper;
import ooo.klae.connex.backend.mappers.DocumentDeliveryMapper;
import ooo.klae.connex.backend.notifications.NotificationDelivery;
import ooo.klae.connex.backend.signature.InAppAcceptanceProvider;
import ooo.klae.connex.backend.signature.ProviderSignedArtifact;
import ooo.klae.connex.backend.storage.ManagedObjectService;
import ooo.klae.connex.backend.storage.ManagedObjectService.StoredArtifact;

/** Applies terminal delivery transitions after callers acquire the documented aggregate locks. */
@Service
@RequiredArgsConstructor
public class DocumentDeliveryLifecycleService {
    private static final Logger log = LoggerFactory.getLogger(DocumentDeliveryLifecycleService.class);
    private static final DateTimeFormatter TIMESTAMP_FORMAT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private final DocumentDeliveryMapper deliveryMapper;
    private final DealDocumentMapper documentMapper;
    private final DocumentApprovalMapper approvalMapper;
    private final ManagedObjectService managedObjectService;
    private final ActivityService activityService;
    private final AutomationExecutor automationExecutor;
    private final SystemActor systemActor;
    private final NotificationDelivery notificationDelivery;
    private final NotificationPreferenceService notificationPreferenceService;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    /** Completes a live envelope and persists its exact document and certificate artifacts. */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean complete(
            int workspaceId,
            Deal deal,
            DealDocument document,
            DocumentDelivery delivery,
            LocalDateTime triggeringEventAt) {
        return complete(
            workspaceId, deal, document, delivery, triggeringEventAt, Optional.empty());
    }

    /** Completes an envelope with provider-returned signed bytes when an adapter supplies them. */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean complete(
            int workspaceId,
            Deal deal,
            DealDocument document,
            DocumentDelivery delivery,
            LocalDateTime triggeringEventAt,
            Optional<ProviderSignedArtifact> providerArtifact) {
        Objects.requireNonNull(triggeringEventAt, "triggeringEventAt");
        Objects.requireNonNull(providerArtifact, "providerArtifact");
        DocumentDeliveryArtifact signedArtifact = deliveryMapper.getArtifactByKind(
            workspaceId, delivery.getId(), "signed_document");
        if (providerArtifact.isPresent()) {
            signedArtifact = stageProviderArtifact(
                workspaceId,
                delivery.getId(),
                signedArtifact,
                providerArtifact.orElseThrow());
        }
        if (deliveryMapper.countIncompleteSigners(workspaceId, delivery.getId()) != 0) {
            return false;
        }
        List<DocumentDeliveryRecipient> decidedRecipients =
            deliveryMapper.getRecipients(workspaceId, delivery.getId());
        LocalDateTime completedAt = deterministicCompletionTime(decidedRecipients);
        if (!InAppAcceptanceProvider.KEY.equals(delivery.getProvider())
                && signedArtifact == null) {
            return false;
        }
        int completed = "expired".equals(delivery.getStatus())
            ? deliveryMapper.completeExpiredDelivery(workspaceId, delivery.getId(), completedAt)
            : deliveryMapper.completeDelivery(workspaceId, delivery.getId(), completedAt);
        if (completed != 1) {
            return "completed".equals(delivery.getStatus());
        }
        deliveryMapper.completeViewersAndInvalidateTokens(workspaceId, delivery.getId());
        List<DocumentDeliveryRecipient> recipients =
            deliveryMapper.getRecipients(workspaceId, delivery.getId());
        if (signedArtifact == null) {
            if (document.getContent() == null) {
                throw new IllegalStateException("Completed document has no frozen content");
            }
            signedArtifact = persistArtifact(
                workspaceId,
                delivery.getId(),
                "signed_document",
                "application/json",
                document.getContent().getBytes(StandardCharsets.UTF_8));
        }
        DocumentApproval approval = approvalMapper.getByDocumentId(
            workspaceId, document.getId()).stream().findFirst().orElse(null);
        byte[] certificate = certificateBytes(
            workspaceId,
            deal,
            document,
            approval,
            delivery,
            recipients,
            completedAt,
            signedArtifact.getSha256());
        persistArtifact(
            workspaceId,
            delivery.getId(),
            "certificate",
            "application/json",
            certificate);
        if (documentMapper.updateStatus(workspaceId, document.getId(), "signed") != 1) {
            throw new IllegalStateException("Completed document could not be marked signed");
        }
        recordActivity(workspaceId, deal, document, delivery, "completed");
        notifyParticipants(workspaceId, deal, document, delivery, "document.delivery_completed");
        auditService.record(
            "document_delivery.complete",
            "deal",
            deal.getId(),
            deal.getName(),
            "Completed document delivery",
            Map.of("deliveryId", delivery.getId(), "documentId", document.getId()));
        return true;
    }

    /** Terminates a live envelope, invalidates outstanding tokens, and restores the document. */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean terminate(
            int workspaceId,
            Deal deal,
            DealDocument document,
            DocumentDelivery delivery,
            String status,
            String reason,
            Integer decidingRecipientId,
            String source,
            String externalEventId,
            LocalDateTime occurredAt) {
        boolean replacingExpiry = "expired".equals(delivery.getStatus())
            && !"expired".equals(status);
        int terminated = replacingExpiry
            ? deliveryMapper.replaceExpiredTermination(
                workspaceId, delivery.getId(), status, occurredAt, reason)
            : deliveryMapper.terminateDelivery(
                workspaceId, delivery.getId(), status, occurredAt, reason);
        if (terminated != 1) {
            return false;
        }
        Integer replayRecipientId = "declined".equals(status) ? decidingRecipientId : null;
        if (replacingExpiry) {
            deliveryMapper.replaceExpiredRecipients(
                workspaceId, delivery.getId(), status, replayRecipientId);
        } else {
            deliveryMapper.closeOutstandingRecipients(
                workspaceId, delivery.getId(), status, replayRecipientId);
        }
        deliveryMapper.invalidateTokensExcept(
            workspaceId, delivery.getId(), replayRecipientId);
        appendEvent(
            workspaceId,
            delivery.getId(),
            decidingRecipientId,
            status,
            source,
            externalEventId,
            reason,
            occurredAt);
        if (documentMapper.updateStatus(workspaceId, document.getId(), "final") != 1) {
            throw new IllegalStateException("Terminated document could not return to final");
        }
        recordActivity(workspaceId, deal, document, delivery, status);
        String notificationType = switch (status) {
            case "declined" -> "document.delivery_declined";
            case "expired" -> "document.delivery_expired";
            default -> null;
        };
        if (notificationType != null) {
            notifyParticipants(workspaceId, deal, document, delivery, notificationType);
        }
        auditService.record(
            "document_delivery." + status,
            "deal",
            deal.getId(),
            deal.getName(),
            "Document delivery became " + status,
            Map.of("deliveryId", delivery.getId(), "documentId", document.getId()));
        return true;
    }

    /** Appends one immutable provider event after replay and ordering checks. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void appendProviderEvent(
            int workspaceId,
            int deliveryId,
            Integer recipientId,
            String eventType,
            String externalEventId,
            String detail,
            LocalDateTime occurredAt) {
        appendEvent(
            workspaceId,
            deliveryId,
            recipientId,
            eventType,
            "provider",
            externalEventId,
            detail,
            occurredAt);
    }

    /** Writes one canonical timeline activity while callers hold the transition transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordActivity(
            int workspaceId,
            Deal deal,
            DealDocument document,
            DocumentDelivery delivery,
            String transition) {
        automationExecutor.runAs(
            workspaceId,
            systemActor.user(),
            "system",
            () -> {
                createActivity(deal, document, delivery, transition);
                return null;
            });
    }

    private DocumentDeliveryArtifact stageProviderArtifact(
            int workspaceId,
            int deliveryId,
            DocumentDeliveryArtifact existing,
            ProviderSignedArtifact providerArtifact) {
        byte[] bytes = providerArtifact.bytes();
        String expectedSha = sha256(bytes);
        if (existing != null) {
            if (!providerArtifact.contentType().equals(existing.getContentType())
                    || existing.getByteLength() != bytes.length
                    || !expectedSha.equals(existing.getSha256())) {
                throw new IllegalStateException(
                    "Provider returned conflicting signed-document artifact bytes");
            }
            return existing;
        }
        return persistArtifact(
            workspaceId,
            deliveryId,
            "signed_document",
            providerArtifact.contentType(),
            bytes);
    }

    private DocumentDeliveryArtifact persistArtifact(
            int workspaceId,
            int deliveryId,
            String kind,
            String contentType,
            byte[] bytes) {
        StoredArtifact stored = managedObjectService.storeDocumentArtifact(
            workspaceId, deliveryId, kind, contentType, bytes);
        DocumentDeliveryArtifact artifact = new DocumentDeliveryArtifact();
        artifact.setWorkspaceId(workspaceId);
        artifact.setDeliveryId(deliveryId);
        artifact.setKind(kind);
        artifact.setObjectKey(stored.objectKey());
        artifact.setContentType(stored.contentType());
        artifact.setByteLength(stored.byteLength());
        artifact.setSha256(stored.sha256());
        if (deliveryMapper.insertArtifact(artifact) != 1) {
            throw new IllegalStateException("Document-delivery artifact metadata was not persisted");
        }
        return artifact;
    }

    byte[] certificateBytes(
            int workspaceId,
            Deal deal,
            DealDocument document,
            DocumentApproval approval,
            DocumentDelivery delivery,
            List<DocumentDeliveryRecipient> recipients,
            LocalDateTime completedAt,
            String documentSha) {
        CompletionCertificate certificate = new CompletionCertificate(
            workspaceId,
            deal.getId(),
            document.getId(),
            document.getVersion(),
            document.getType(),
            approval == null ? null : approval.getId(),
            approvalOutcome(approval),
            approvalPolicyId(approval),
            delivery.getProvider(),
            delivery.getProviderEnvelopeId(),
            delivery.getId(),
            delivery.getSentAt(),
            completedAt,
            documentSha,
            recipients.stream()
                .sorted(Comparator.comparingInt(DocumentDeliveryRecipient::getRecipientOrder)
                    .thenComparingInt(DocumentDeliveryRecipient::getId))
                .map(recipient -> new CertificateRecipient(
                    recipient.getId(),
                    recipient.getName(),
                    recipient.getEmail(),
                    recipient.getRole(),
                    recipient.getStatus(),
                    recipient.getFirstViewedAt(),
                    recipient.getDecidedAt(),
                    recipient.getTypedName(),
                    recipient.getDeclineReason(),
                    recipient.getEvidenceIpHash(),
                    recipient.getEvidenceAgentHash()))
                .toList());
        return objectMapper.writeValueAsBytes(certificate);
    }

    static LocalDateTime deterministicCompletionTime(
            List<DocumentDeliveryRecipient> recipients) {
        return recipients.stream()
            .filter(recipient -> "signer".equals(recipient.getRole()))
            .map(recipient -> {
                if (!"completed".equals(recipient.getStatus())
                        || recipient.getDecidedAt() == null) {
                    throw new IllegalStateException(
                        "Completed signer decision time is unavailable");
                }
                return recipient.getDecidedAt();
            })
            .max(LocalDateTime::compareTo)
            .orElseThrow(() -> new IllegalStateException(
                "Completed delivery has no signer decisions"));
    }

    private static String approvalOutcome(DocumentApproval approval) {
        if (approval == null) {
            return "no_approval_required";
        }
        return switch (approval.getStatus()) {
            case "approved", "rejected", "cancelled" -> approval.getStatus();
            default -> throw new IllegalStateException(
                "Document approval is not terminal at delivery completion");
        };
    }

    private static Integer approvalPolicyId(DocumentApproval approval) {
        if (approval == null) {
            return null;
        }
        String policyBinding = approval.getPolicyBinding();
        if (policyBinding == null) {
            throw new IllegalStateException("Document approval policy binding is unavailable");
        }
        return switch (policyBinding) {
            case "none" -> null;
            case "applied" -> {
                if (approval.getPolicyIdSnapshot() == null) {
                    throw new IllegalStateException(
                        "Applied approval policy snapshot is unavailable");
                }
                yield approval.getPolicyIdSnapshot();
            }
            case "unknown_legacy" -> throw new IllegalStateException(
                "Legacy approval policy binding is unavailable for certification");
            default -> throw new IllegalStateException(
                "Document approval policy binding is invalid");
        };
    }

    private void createActivity(
            Deal deal, DealDocument document, DocumentDelivery delivery, String transition) {
        Activity activity = new Activity();
        activity.setType("document");
        activity.setSubject("Document delivery " + transition);
        activity.setNotes("Document v" + document.getVersion()
            + " delivery " + delivery.getId() + " became " + transition);
        activity.setDeal(deal);
        activity.setTimestamp(LocalDateTime.now(ZoneOffset.UTC).format(TIMESTAMP_FORMAT));
        activityService.create(activity);
    }

    private void notifyParticipants(
            int workspaceId,
            Deal deal,
            DealDocument document,
            DocumentDelivery delivery,
            String type) {
        LinkedHashSet<Integer> recipients = new LinkedHashSet<>();
        if (delivery.getSentBy() != null) {
            recipients.add(delivery.getSentBy());
        }
        if (deal.getOwnerId() != null) {
            recipients.add(deal.getOwnerId());
        }
        for (int recipientId : recipients) {
            try {
                if (!notificationPreferenceService.isEnabled(recipientId, type, "in_app")) {
                    continue;
                }
                Notification notification = new Notification();
                notification.setWorkspaceId(workspaceId);
                notification.setRecipientId(recipientId);
                notification.setType(type);
                notification.setCategory("deal");
                notification.setSeverity(type.endsWith("declined") ? "warning" : "info");
                notification.setTemplateVersion(1);
                notification.setTitle(notificationTitle(type));
                notification.setBody("A commercial-document delivery changed status");
                notification.setSourceType("deal_document");
                notification.setSourceId(document.getId());
                notification.setSourceLabel(documentTitle(document));
                notification.setContextType("deal");
                notification.setContextId(deal.getId());
                notification.setContextLabel(deal.getName());
                notification.setActionUrl("/records/deals/" + deal.getId());
                notification.setDedupeKey(type + ":" + delivery.getId() + ":" + recipientId);
                notification.setTriggeredAt(
                    LocalDateTime.now(ZoneOffset.UTC).format(TIMESTAMP_FORMAT));
                notification.setData(objectMapper.writeValueAsString(Map.of(
                    "dealId", deal.getId(),
                    "documentId", document.getId(),
                    "deliveryId", delivery.getId())));
                notificationDelivery.deliver(notification);
            } catch (RuntimeException exception) {
                log.warn(
                    "Document-delivery notification failed deliveryId={} recipientId={} exceptionClass={}",
                    delivery.getId(),
                    recipientId,
                    exception.getClass().getSimpleName());
            }
        }
    }

    private void appendEvent(
            int workspaceId,
            int deliveryId,
            Integer recipientId,
            String eventType,
            String source,
            String externalEventId,
            String detail,
            LocalDateTime occurredAt) {
        DocumentDeliveryEvent event = new DocumentDeliveryEvent();
        event.setWorkspaceId(workspaceId);
        event.setDeliveryId(deliveryId);
        event.setRecipientId(recipientId);
        event.setEventType(eventType);
        event.setSource(source);
        event.setExternalEventId(externalEventId);
        event.setDetail(detail);
        event.setOccurredAt(occurredAt);
        if (deliveryMapper.insertEvent(event) != 1) {
            throw new IllegalStateException("Document-delivery event was not persisted");
        }
    }

    private static String notificationTitle(String type) {
        return switch (type) {
            case "document.delivery_completed" -> "Document delivery completed";
            case "document.delivery_declined" -> "Document delivery declined";
            default -> "Document delivery expired";
        };
    }

    private static String documentTitle(DealDocument document) {
        return document.getTitle() == null || document.getTitle().isBlank()
            ? document.getType() + " v" + document.getVersion()
            : document.getTitle();
    }

    private static String sha256(byte[] bytes) {
        try {
            return java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    private record CompletionCertificate(
            int workspaceId,
            int dealId,
            int documentId,
            int documentVersion,
            String documentType,
            Integer approvalRequestId,
            String approvalOutcome,
            Integer approvalPolicyId,
            String provider,
            String providerEnvelopeId,
            int deliveryId,
            LocalDateTime sentAt,
            LocalDateTime completedAt,
            String signedDocumentSha256,
            List<CertificateRecipient> recipients) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    private record CertificateRecipient(
            int recipientId,
            String name,
            String email,
            String role,
            String decision,
            LocalDateTime firstViewedAt,
            LocalDateTime decidedAt,
            String typedName,
            String declineReason,
            String evidenceIpHash,
            String evidenceAgentHash) {
    }
}
