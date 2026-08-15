package ooo.klae.connex.backend.services;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.DealDocument;
import ooo.klae.connex.backend.beans.DocumentApproval;
import ooo.klae.connex.backend.beans.DocumentDelivery;
import ooo.klae.connex.backend.beans.DocumentDeliveryArtifact;
import ooo.klae.connex.backend.beans.DocumentDeliveryEvent;
import ooo.klae.connex.backend.beans.DocumentDeliveryRecipient;
import ooo.klae.connex.backend.beans.DocumentDeliveryRequest;
import ooo.klae.connex.backend.capability.Capability;
import ooo.klae.connex.backend.capability.CapabilityRegistry;
import ooo.klae.connex.backend.dto.DocumentDeliveryDto;
import ooo.klae.connex.backend.dto.SendDeliveryRecipientRequest;
import ooo.klae.connex.backend.dto.SendDeliveryRequest;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.exceptions.ServiceUnavailableException;
import ooo.klae.connex.backend.mappers.DealDocumentMapper;
import ooo.klae.connex.backend.mappers.DealMapper;
import ooo.klae.connex.backend.mappers.DocumentApprovalMapper;
import ooo.klae.connex.backend.mappers.DocumentDeliveryMapper;
import ooo.klae.connex.backend.mappers.PersonMapper;
import ooo.klae.connex.backend.signature.DocumentSignatureEmailService;
import ooo.klae.connex.backend.signature.DocumentDeliveryIdempotencyKey;
import ooo.klae.connex.backend.signature.DocumentSignatureProvider;
import ooo.klae.connex.backend.signature.DocumentSignatureProviderRouter;
import ooo.klae.connex.backend.signature.InAppAcceptanceProvider;
import ooo.klae.connex.backend.signature.RecipientDeliveryLink;
import ooo.klae.connex.backend.signature.SendCommand;
import ooo.klae.connex.backend.signature.SendOutcome;
import ooo.klae.connex.backend.signature.SendRecipient;
import ooo.klae.connex.backend.signature.SendRecipientOutcome;
import ooo.klae.connex.backend.signature.SignatureProperties;
import ooo.klae.connex.backend.signature.VoidCommand;
import ooo.klae.connex.backend.storage.ManagedObjectService;
import ooo.klae.connex.backend.storage.ManagedObjectService.ManagedContent;
import ooo.klae.connex.backend.tenant.Permission;
import ooo.klae.connex.backend.tenant.RequirePermission;

/** Authenticated document-delivery lifecycle for immutable commercial-document versions. */
@Service
@RequiredArgsConstructor
public class DocumentDeliveryService {
    private static final String SENT = "sent";
    private static final String VIEWED = "viewed";
    private static final Set<String> LIVE = Set.of(SENT, VIEWED);

    private final DocumentDeliveryMapper deliveryMapper;
    private final DealDocumentMapper documentMapper;
    private final DealMapper dealMapper;
    private final DocumentApprovalMapper approvalMapper;
    private final PersonMapper personMapper;
    private final WorkspaceService workspaceService;
    private final CapabilityRegistry capabilityRegistry;
    private final SignatureProperties signatureProperties;
    private final DocumentSignatureProviderRouter providerRouter;
    private final DocumentSignatureEmailService emailService;
    private final ManagedObjectService managedObjectService;
    private final AuditService auditService;

    /** Sends one final immutable document version to one through twenty external recipients. */
    @Transactional
    @RequirePermission(Permission.DOCUMENT_SEND)
    public DocumentDeliveryDto send(
            int dealId,
            int documentId,
            SendDeliveryRequest request,
            String idempotencyKey) {
        requireAvailable();
        if (request == null) {
            throw new BadRequestException("Document-delivery request is required");
        }
        String requestId = DocumentDeliveryIdempotencyKey.canonicalize(idempotencyKey);
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        int actorId = workspaceService.getCurrentUserId();
        requireLockedPermission(workspaceId, actorId);
        Deal deal = lockDeal(workspaceId, dealId);
        DealDocument document = lockDocument(workspaceId, dealId, documentId);
        List<NormalizedRecipient> recipients = normalizeRecipients(request.getRecipients());
        byte[] fingerprint = sendFingerprint(dealId, documentId, request, recipients);
        DocumentDeliveryRequest replay = claimOrReplay(
            workspaceId,
            requestId,
            "send",
            fingerprint,
            documentId,
            null,
            null,
            actorId);
        if (replay != null) {
            return replay(workspaceId, dealId, documentId, replay);
        }
        requireSendableDocument(workspaceId, documentId, document, requestId);
        if (deliveryMapper.findActiveByDocument(workspaceId, documentId) != null) {
            throw new BadRequestException("This document already has a live delivery");
        }
        requireRecipientPeople(workspaceId, recipients);
        DocumentSignatureProvider provider = providerRouter.adapterFor(request.getProvider());
        requireOutboundProvider(provider);

        DocumentDelivery delivery = new DocumentDelivery();
        delivery.setWorkspaceId(workspaceId);
        delivery.setDealId(dealId);
        delivery.setDocumentId(documentId);
        delivery.setProvider(provider.key());
        delivery.setStatus(SENT);
        delivery.setMessage(blankToNull(request.getMessage()));
        delivery.setExpiresAt(request.getExpiresAt());
        delivery.setSentBy(actorId);
        deliveryMapper.insertDelivery(delivery);
        if (deliveryMapper.completeRequest(workspaceId, requestId, delivery.getId()) != 1) {
            throw new IllegalStateException("Document-delivery idempotency result was not recorded");
        }

        ArrayList<DocumentDeliveryRecipient> persisted = new ArrayList<>();
        for (NormalizedRecipient normalized : recipients) {
            DocumentDeliveryRecipient recipient = new DocumentDeliveryRecipient();
            recipient.setWorkspaceId(workspaceId);
            recipient.setDeliveryId(delivery.getId());
            recipient.setPersonId(normalized.personId());
            recipient.setName(normalized.name());
            recipient.setEmail(normalized.email());
            recipient.setRole(normalized.role());
            recipient.setRecipientOrder(normalized.recipientOrder());
            recipient.setStatus("pending");
            recipient.setTokenExpiresAt(delivery.getExpiresAt());
            deliveryMapper.insertRecipient(recipient);
            persisted.add(recipient);
        }

        SendOutcome outcome = provider.send(new SendCommand(
            workspaceId,
            delivery.getId(),
            null,
            delivery.getExpiresAt(),
            persisted.stream().map(this::sendRecipient).toList()));
        Map<Integer, SendRecipientOutcome> outcomeByRecipient = validateOutcome(persisted, outcome);
        deliveryMapper.setProviderEnvelopeId(
            workspaceId, delivery.getId(), requireText(outcome.providerEnvelopeId(), "Provider envelope id"));
        for (DocumentDeliveryRecipient recipient : persisted) {
            SendRecipientOutcome recipientOutcome = outcomeByRecipient.get(recipient.getId());
            RecipientDeliveryLink deliveryLink = requireDeliveryLink(recipientOutcome);
            if (deliveryMapper.updateRecipientToken(
                    workspaceId,
                    delivery.getId(),
                    recipient.getId(),
                    requireHash(deliveryLink.tokenHash()),
                    delivery.getExpiresAt(),
                    blankToNull(recipientOutcome.providerRecipientId())) != 1) {
                throw new IllegalStateException("Document recipient token could not be stored");
            }
        }
        appendEvent(workspaceId, delivery.getId(), null, "sent", "actor", null, null, now());
        if (documentMapper.updateStatus(workspaceId, documentId, SENT) != 1) {
            throw new IllegalStateException("Document could not be marked sent");
        }
        auditService.record(
            "document_delivery.send",
            "deal",
            dealId,
            deal.getName(),
            "Sent document v" + document.getVersion() + " for external acceptance",
            Map.of(
                "deliveryId", delivery.getId(),
                "documentId", documentId,
                "provider", provider.key(),
                "recipientCount", persisted.size()));
        for (DocumentDeliveryRecipient recipient : persisted) {
            SendRecipientOutcome recipientOutcome = outcomeByRecipient.get(recipient.getId());
            RecipientDeliveryLink deliveryLink = requireDeliveryLink(recipientOutcome);
            sendAfterCommit(
                workspaceId,
                recipient.getName(),
                recipient.getEmail(),
                titleOf(document),
                delivery.getMessage(),
                requireText(deliveryLink.url(), "Acceptance URL"),
                document.getLocale());
        }
        return DocumentDeliveryDto.from(requireHydrated(workspaceId, delivery.getId()));
    }

    /** Returns all delivery history for one workspace-scoped document. */
    @RequirePermission(Permission.DOCUMENT_SEND)
    public List<DocumentDeliveryDto> getForDocument(int dealId, int documentId) {
        requireAvailable();
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        requireDeal(workspaceId, dealId);
        requireDocument(workspaceId, dealId, documentId);
        List<DocumentDelivery> deliveries = deliveryMapper.getByDocument(workspaceId, documentId);
        hydrate(workspaceId, deliveries);
        return deliveries.stream().map(DocumentDeliveryDto::from).toList();
    }

    /** Voids one live envelope, invalidates every bearer token, and restores the document to final. */
    @Transactional
    @RequirePermission(Permission.DOCUMENT_SEND)
    public DocumentDeliveryDto voidDelivery(
            int dealId, int documentId, int deliveryId, String reason) {
        requireAvailable();
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        int actorId = workspaceService.getCurrentUserId();
        requireLockedPermission(workspaceId, actorId);
        Deal deal = lockDeal(workspaceId, dealId);
        DealDocument document = lockDocument(workspaceId, dealId, documentId);
        DocumentDelivery delivery = lockDelivery(workspaceId, dealId, documentId, deliveryId);
        requireLive(delivery);
        lockRecipientsAscending(workspaceId, deliveryId);
        DocumentSignatureProvider provider = providerRouter.adapterFor(delivery.getProvider());
        requireOutboundProvider(provider);
        provider.voidEnvelope(new VoidCommand(
            workspaceId, deliveryId, delivery.getProviderEnvelopeId(), requireReason(reason)));
        terminate(workspaceId, delivery, "voided", requireReason(reason), null);
        if (documentMapper.updateStatus(workspaceId, documentId, "final") != 1) {
            throw new IllegalStateException("Voided document could not return to final");
        }
        auditService.record(
            "document_delivery.void",
            "deal",
            dealId,
            deal.getName(),
            "Voided delivery for document v" + document.getVersion(),
            Map.of("deliveryId", deliveryId, "documentId", documentId));
        return DocumentDeliveryDto.from(requireHydrated(workspaceId, deliveryId));
    }

    /** Replaces one live recipient's bearer token and sends the new link. */
    @Transactional
    @RequirePermission(Permission.DOCUMENT_SEND)
    public DocumentDeliveryDto resend(
            int dealId,
            int documentId,
            int deliveryId,
            int recipientId,
            String idempotencyKey) {
        requireAvailable();
        String requestId = DocumentDeliveryIdempotencyKey.canonicalize(idempotencyKey);
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        int actorId = workspaceService.getCurrentUserId();
        requireLockedPermission(workspaceId, actorId);
        Deal deal = lockDeal(workspaceId, dealId);
        DealDocument document = lockDocument(workspaceId, dealId, documentId);
        DocumentDelivery delivery = lockDelivery(workspaceId, dealId, documentId, deliveryId);
        byte[] fingerprint = resendFingerprint(dealId, documentId, deliveryId, recipientId);
        DocumentDeliveryRequest replay = claimOrReplay(
            workspaceId,
            requestId,
            "resend",
            fingerprint,
            documentId,
            deliveryId,
            recipientId,
            actorId);
        if (replay != null) {
            return replay(workspaceId, dealId, documentId, replay);
        }
        requireLive(delivery);
        DocumentDeliveryRecipient recipient = deliveryMapper.lockRecipient(
            workspaceId, deliveryId, recipientId);
        if (recipient == null || !("pending".equals(recipient.getStatus())
                || VIEWED.equals(recipient.getStatus()))) {
            throw new ResourceNotFoundException("Document recipient was not found");
        }
        DocumentSignatureProvider provider = providerRouter.adapterFor(delivery.getProvider());
        requireOutboundProvider(provider);
        SendOutcome outcome = provider.send(new SendCommand(
            workspaceId,
            deliveryId,
            delivery.getProviderEnvelopeId(),
            delivery.getExpiresAt(),
            List.of(sendRecipient(recipient))));
        Map<Integer, SendRecipientOutcome> outcomes = validateOutcome(List.of(recipient), outcome);
        SendRecipientOutcome recipientOutcome = outcomes.get(recipientId);
        RecipientDeliveryLink deliveryLink = requireDeliveryLink(recipientOutcome);
        if (deliveryMapper.updateRecipientToken(
                workspaceId,
                deliveryId,
                recipientId,
                requireHash(deliveryLink.tokenHash()),
                delivery.getExpiresAt(),
                blankToNull(recipientOutcome.providerRecipientId())) != 1) {
            throw new IllegalStateException("Document recipient token could not be replaced");
        }
        appendEvent(workspaceId, deliveryId, recipientId, "resent", "actor", null, null, now());
        auditService.record(
            "document_delivery.resend",
            "deal",
            dealId,
            deal.getName(),
            "Resent one document-delivery link",
            Map.of("deliveryId", deliveryId, "documentId", documentId, "recipientId", recipientId));
        sendAfterCommit(
            workspaceId,
            recipient.getName(),
            recipient.getEmail(),
            titleOf(document),
            delivery.getMessage(),
            requireText(deliveryLink.url(), "Acceptance URL"),
            document.getLocale());
        return DocumentDeliveryDto.from(requireHydrated(workspaceId, deliveryId));
    }

    /** Opens one authenticated, workspace-scoped immutable artifact. */
    @RequirePermission(Permission.DOCUMENT_SEND)
    public ManagedContent downloadArtifact(
            int dealId, int documentId, int deliveryId, int artifactId) {
        requireAvailable();
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        requireDeal(workspaceId, dealId);
        requireDocument(workspaceId, dealId, documentId);
        DocumentDelivery delivery = requireDelivery(workspaceId, dealId, documentId, deliveryId);
        DocumentDeliveryArtifact artifact = deliveryMapper.getArtifact(
            workspaceId, delivery.getId(), artifactId);
        if (artifact == null) {
            throw new ResourceNotFoundException("Document-delivery artifact was not found");
        }
        auditService.record(
            "document_delivery.artifact_download",
            "deal",
            dealId,
            null,
            "Downloaded a document-delivery artifact",
            Map.of("deliveryId", deliveryId, "artifactId", artifactId, "kind", artifact.getKind()));
        return managedObjectService.openDocumentArtifact(
            workspaceId,
            deliveryId,
            artifact.getKind(),
            artifact.getObjectKey(),
            artifact.getContentType());
    }

    /** Voids a live envelope while its document row is already held for superseding. */
    void voidOnSupersede(int workspaceId, DealDocument document) {
        DocumentDelivery active = deliveryMapper.findActiveByDocument(workspaceId, document.getId());
        if (active == null) {
            return;
        }
        DocumentDelivery delivery = deliveryMapper.lockById(workspaceId, active.getId());
        if (delivery == null || !LIVE.contains(delivery.getStatus())) {
            return;
        }
        lockRecipientsAscending(workspaceId, delivery.getId());
        DocumentSignatureProvider provider = providerRouter.adapterFor(delivery.getProvider());
        requireOutboundProvider(provider);
        provider.voidEnvelope(new VoidCommand(
            workspaceId,
            delivery.getId(),
            delivery.getProviderEnvelopeId(),
            "Document superseded"));
        terminate(workspaceId, delivery, "voided", "Document superseded", null);
    }

    private void terminate(
            int workspaceId,
            DocumentDelivery delivery,
            String status,
            String reason,
            Integer exceptRecipientId) {
        LocalDateTime at = now();
        if (deliveryMapper.terminateDelivery(
                workspaceId, delivery.getId(), status, at, reason) != 1) {
            throw new IllegalStateException("Document delivery could not be terminated");
        }
        deliveryMapper.closeOutstandingRecipients(
            workspaceId, delivery.getId(), status, exceptRecipientId);
        deliveryMapper.invalidateAllTokens(workspaceId, delivery.getId());
        appendEvent(workspaceId, delivery.getId(), exceptRecipientId, status, "actor", null, reason, at);
    }

    private List<NormalizedRecipient> normalizeRecipients(
            List<SendDeliveryRecipientRequest> requested) {
        if (requested == null || requested.isEmpty() || requested.size() > 20) {
            throw new BadRequestException("A delivery requires between 1 and 20 recipients");
        }
        ArrayList<NormalizedRecipient> normalized = new ArrayList<>();
        HashSet<String> emails = new HashSet<>();
        boolean signer = false;
        int defaultOrder = 1;
        for (SendDeliveryRecipientRequest candidate : requested) {
            if (candidate == null) {
                throw new BadRequestException("Document recipients are required");
            }
            String name = requireText(candidate.name(), "Recipient name");
            String email = requireText(candidate.email(), "Recipient email").toLowerCase(Locale.ROOT);
            String role = candidate.role();
            if (!("signer".equals(role) || "viewer".equals(role))) {
                throw new BadRequestException("Recipient role must be signer or viewer");
            }
            if (!emails.add(email)) {
                throw new BadRequestException("Recipient emails must be unique");
            }
            signer |= "signer".equals(role);
            normalized.add(new NormalizedRecipient(
                candidate.personId(),
                name,
                email,
                role,
                candidate.recipientOrder() == null ? defaultOrder : candidate.recipientOrder()));
            defaultOrder++;
        }
        if (!signer) {
            throw new BadRequestException("A delivery requires at least one signer");
        }
        normalized.sort(Comparator.comparingInt(NormalizedRecipient::recipientOrder));
        return List.copyOf(normalized);
    }

    private void requireRecipientPeople(
            int workspaceId, List<NormalizedRecipient> recipients) {
        for (NormalizedRecipient recipient : recipients) {
            if (recipient.personId() != null
                    && !personMapper.exists(workspaceId, recipient.personId())) {
                throw new ResourceNotFoundException("Recipient person was not found");
            }
        }
    }

    Map<Integer, SendRecipientOutcome> validateOutcome(
            List<DocumentDeliveryRecipient> recipients, SendOutcome outcome) {
        if (outcome == null || outcome.recipients() == null) {
            throw new IllegalStateException("Document-signature provider returned no outcome");
        }
        Map<Integer, SendRecipientOutcome> indexed = outcome.recipients().stream()
            .collect(Collectors.toMap(
                SendRecipientOutcome::recipientId,
                Function.identity(),
                (left, right) -> {
                    throw new IllegalStateException("Document-signature provider duplicated a recipient");
                }));
        Set<Integer> expected = recipients.stream()
            .map(DocumentDeliveryRecipient::getId)
            .collect(Collectors.toSet());
        if (!indexed.keySet().equals(expected)) {
            throw new IllegalStateException("Document-signature provider recipient outcome is incomplete");
        }
        HashSet<String> hashes = new HashSet<>();
        HashSet<String> providerRecipientIds = new HashSet<>();
        for (SendRecipientOutcome recipient : indexed.values()) {
            RecipientDeliveryLink deliveryLink = requireDeliveryLink(recipient);
            if (!hashes.add(requireHash(deliveryLink.tokenHash()))) {
                throw new IllegalStateException("Document-signature provider reused a recipient token");
            }
            String providerRecipientId = blankToNull(recipient.providerRecipientId());
            if (providerRecipientId != null) {
                if (providerRecipientId.length() > 255) {
                    throw new IllegalStateException(
                        "Document-signature provider recipient id is too long");
                }
                if (!providerRecipientIds.add(providerRecipientId)) {
                    throw new IllegalStateException(
                        "Document-signature provider reused a provider recipient id");
                }
            }
            requireText(deliveryLink.url(), "Acceptance URL");
        }
        return indexed;
    }

    private static RecipientDeliveryLink requireDeliveryLink(
            SendRecipientOutcome recipientOutcome) {
        return recipientOutcome.deliveryLink().orElseThrow(() ->
            new IllegalStateException("Built-in provider returned no recipient delivery link"));
    }

    private void hydrate(int workspaceId, List<DocumentDelivery> deliveries) {
        if (deliveries.isEmpty()) {
            return;
        }
        List<Integer> ids = deliveries.stream().map(DocumentDelivery::getId).toList();
        Map<Integer, List<DocumentDeliveryRecipient>> recipients = deliveryMapper
            .getRecipientsByDeliveryIds(workspaceId, ids).stream()
            .collect(Collectors.groupingBy(DocumentDeliveryRecipient::getDeliveryId));
        Map<Integer, List<DocumentDeliveryEvent>> events = deliveryMapper
            .getEventsByDeliveryIds(workspaceId, ids).stream()
            .collect(Collectors.groupingBy(DocumentDeliveryEvent::getDeliveryId));
        Map<Integer, List<DocumentDeliveryArtifact>> artifacts = deliveryMapper
            .getArtifactsByDeliveryIds(workspaceId, ids).stream()
            .collect(Collectors.groupingBy(DocumentDeliveryArtifact::getDeliveryId));
        for (DocumentDelivery delivery : deliveries) {
            delivery.setRecipients(recipients.getOrDefault(delivery.getId(), List.of()));
            delivery.setEvents(events.getOrDefault(delivery.getId(), List.of()));
            delivery.setArtifacts(artifacts.getOrDefault(delivery.getId(), List.of()));
        }
    }

    private DocumentDelivery requireHydrated(int workspaceId, int deliveryId) {
        DocumentDelivery delivery = deliveryMapper.getById(workspaceId, deliveryId);
        if (delivery == null) {
            throw new ResourceNotFoundException("Document delivery was not found");
        }
        hydrate(workspaceId, List.of(delivery));
        return delivery;
    }

    private List<DocumentDeliveryRecipient> lockRecipientsAscending(
            int workspaceId, int deliveryId) {
        ArrayList<Integer> ids = new ArrayList<>(deliveryMapper.getRecipientIds(workspaceId, deliveryId));
        ids.sort(Integer::compareTo);
        ArrayList<DocumentDeliveryRecipient> recipients = new ArrayList<>();
        for (int id : ids) {
            DocumentDeliveryRecipient recipient = deliveryMapper.lockRecipient(
                workspaceId, deliveryId, id);
            if (recipient != null) {
                recipients.add(recipient);
            }
        }
        return recipients;
    }

    private void requireLockedPermission(int workspaceId, int actorId) {
        if (!workspaceService.lockedPermissionsFor(workspaceId, actorId)
                .contains(Permission.DOCUMENT_SEND)) {
            throw new ForbiddenException("Requires the DOCUMENT_SEND permission in this workspace");
        }
    }

    private void requireAvailable() {
        if (!signatureProperties.isEnabled()
                || !capabilityRegistry.isAvailable(Capability.DOCUMENT_SIGNATURE)) {
            throw new ServiceUnavailableException("Document signature delivery is unavailable");
        }
    }

    /**
     * Validates that the locked document may still be sent, releasing the idempotency claim first
     * when it may not.
     *
     * <p>The claim has to be taken before these checks so a retry of a successful send replays
     * instead of tripping over the state that very send produced. A refusal must therefore hand the
     * key back, matching the repository's rule that a failed preview cancels its reserved proof;
     * otherwise a caller who fixes the underlying problem could never reuse the key they retained.
     */
    private void requireSendableDocument(
            int workspaceId, int documentId, DealDocument document, String requestId) {
        try {
            if (!"final".equals(document.getStatus())) {
                throw new BadRequestException("Only final documents can be sent");
            }
            requireCertifiableApproval(workspaceId, documentId);
        } catch (RuntimeException refusal) {
            deliveryMapper.cancelUncompletedRequest(workspaceId, requestId);
            throw refusal;
        }
    }

    private void requireCertifiableApproval(int workspaceId, int documentId) {
        DocumentApproval approval = approvalMapper.getByDocumentId(
            workspaceId, documentId).stream().findFirst().orElse(null);
        if (approval != null && "unknown_legacy".equals(approval.getPolicyBinding())) {
            throw new BadRequestException(
                "The document approval policy cannot be certified; create a new document version");
        }
    }

    private static void requireOutboundProvider(DocumentSignatureProvider provider) {
        if (!InAppAcceptanceProvider.KEY.equals(provider.key())) {
            throw new ServiceUnavailableException(
                "External document-signature provider execution is unavailable");
        }
    }

    private Deal requireDeal(int workspaceId, int dealId) {
        Deal deal = dealMapper.getDealById(workspaceId, dealId);
        if (deal == null) {
            throw new ResourceNotFoundException("Deal not found with id: " + dealId);
        }
        return deal;
    }

    private Deal lockDeal(int workspaceId, int dealId) {
        Deal deal = dealMapper.getDealByIdForUpdate(workspaceId, dealId);
        if (deal == null) {
            throw new ResourceNotFoundException("Deal not found with id: " + dealId);
        }
        return deal;
    }

    private DealDocument requireDocument(int workspaceId, int dealId, int documentId) {
        DealDocument document = documentMapper.getById(workspaceId, documentId);
        if (document == null || document.getDealId() != dealId) {
            throw new ResourceNotFoundException("Document not found with id: " + documentId);
        }
        return document;
    }

    private DealDocument lockDocument(int workspaceId, int dealId, int documentId) {
        DealDocument document = documentMapper.lockById(workspaceId, documentId);
        if (document == null || document.getDealId() != dealId) {
            throw new ResourceNotFoundException("Document not found with id: " + documentId);
        }
        return document;
    }

    private DocumentDelivery requireDelivery(
            int workspaceId, int dealId, int documentId, int deliveryId) {
        DocumentDelivery delivery = deliveryMapper.getById(workspaceId, deliveryId);
        if (delivery == null || delivery.getDealId() != dealId
                || delivery.getDocumentId() != documentId) {
            throw new ResourceNotFoundException("Document delivery was not found");
        }
        return delivery;
    }

    private DocumentDelivery lockDelivery(
            int workspaceId, int dealId, int documentId, int deliveryId) {
        DocumentDelivery delivery = deliveryMapper.lockById(workspaceId, deliveryId);
        if (delivery == null || delivery.getDealId() != dealId
                || delivery.getDocumentId() != documentId) {
            throw new ResourceNotFoundException("Document delivery was not found");
        }
        return delivery;
    }

    private static void requireLive(DocumentDelivery delivery) {
        if (!LIVE.contains(delivery.getStatus())) {
            throw new BadRequestException("Document delivery is no longer active");
        }
    }

    private SendRecipient sendRecipient(DocumentDeliveryRecipient recipient) {
        return new SendRecipient(
            recipient.getId(),
            recipient.getName(),
            recipient.getEmail(),
            recipient.getRole(),
            recipient.getRecipientOrder());
    }

    private void appendEvent(
            int workspaceId,
            int deliveryId,
            Integer recipientId,
            String type,
            String source,
            String externalEventId,
            String detail,
            LocalDateTime occurredAt) {
        DocumentDeliveryEvent event = new DocumentDeliveryEvent();
        event.setWorkspaceId(workspaceId);
        event.setDeliveryId(deliveryId);
        event.setRecipientId(recipientId);
        event.setEventType(type);
        event.setSource(source);
        event.setExternalEventId(externalEventId);
        event.setDetail(blankToNull(detail));
        event.setOccurredAt(occurredAt);
        deliveryMapper.insertEvent(event);
    }

    private static String titleOf(DealDocument document) {
        return document.getTitle() == null || document.getTitle().isBlank()
            ? document.getType() + " v" + document.getVersion()
            : document.getTitle();
    }

    private static String requireReason(String value) {
        String reason = requireText(value, "Void reason");
        if (reason.length() > 500) {
            throw new BadRequestException("Void reason must not exceed 500 characters");
        }
        return reason;
    }

    private static String requireHash(String value) {
        if (value == null || !value.matches("[a-f0-9]{64}")) {
            throw new IllegalStateException("Document-signature provider token hash is invalid");
        }
        return value;
    }

    private static String requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new BadRequestException(label + " is required");
        }
        return value.trim();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }

    private DocumentDeliveryRequest claimOrReplay(
            int workspaceId,
            String idempotencyKey,
            String operation,
            byte[] fingerprint,
            int documentId,
            Integer deliveryId,
            Integer recipientId,
            int actorId) {
        if (deliveryMapper.claimRequest(
                workspaceId,
                idempotencyKey,
                operation,
                fingerprint,
                documentId,
                deliveryId,
                recipientId,
                actorId) == 1) {
            return null;
        }
        DocumentDeliveryRequest existing = deliveryMapper.getRequestForUpdate(
            workspaceId, idempotencyKey);
        if (existing == null
                || existing.createdByUserId() != actorId
                || !operation.equals(existing.operation())
                || existing.documentId() != documentId
                || !java.util.Objects.equals(existing.recipientId(), recipientId)
                || !MessageDigest.isEqual(existing.requestFingerprint(), fingerprint)) {
            throw new ConflictException(
                "Idempotency-Key was already used for a different document-delivery request");
        }
        if (existing.deliveryId() == null) {
            throw new ConflictException("Document-delivery request is still in progress");
        }
        return existing;
    }

    private DocumentDeliveryDto replay(
            int workspaceId,
            int dealId,
            int documentId,
            DocumentDeliveryRequest request) {
        DocumentDelivery delivery = requireHydrated(workspaceId, request.deliveryId());
        if (delivery.getDealId() != dealId || delivery.getDocumentId() != documentId) {
            throw new ConflictException(
                "Idempotency-Key result does not belong to this document");
        }
        return DocumentDeliveryDto.from(delivery);
    }

    private static byte[] sendFingerprint(
            int dealId,
            int documentId,
            SendDeliveryRequest request,
            List<NormalizedRecipient> recipients) {
        MessageDigest digest = sha256Digest();
        updateDigest(digest, "connex-document-delivery-send-v1");
        updateDigest(digest, dealId);
        updateDigest(digest, documentId);
        updateDigest(digest, blankToNull(request.getProvider()));
        updateDigest(digest, blankToNull(request.getMessage()));
        updateDigest(digest, request.getExpiresAt() == null ? null : request.getExpiresAt().toString());
        for (NormalizedRecipient recipient : recipients) {
            updateDigest(digest, recipient.personId());
            updateDigest(digest, recipient.name());
            updateDigest(digest, recipient.email());
            updateDigest(digest, recipient.role());
            updateDigest(digest, recipient.recipientOrder());
        }
        return digest.digest();
    }

    private static byte[] resendFingerprint(
            int dealId, int documentId, int deliveryId, int recipientId) {
        MessageDigest digest = sha256Digest();
        updateDigest(digest, "connex-document-delivery-resend-v1");
        updateDigest(digest, dealId);
        updateDigest(digest, documentId);
        updateDigest(digest, deliveryId);
        updateDigest(digest, recipientId);
        return digest.digest();
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static void updateDigest(MessageDigest digest, Integer value) {
        updateDigest(digest, value == null ? null : Integer.toString(value));
    }

    private static void updateDigest(MessageDigest digest, String value) {
        byte[] bytes = value == null ? null : value.getBytes(StandardCharsets.UTF_8);
        int length = bytes == null ? -1 : bytes.length;
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(length).array());
        if (bytes != null) {
            digest.update(bytes);
        }
    }

    private void sendAfterCommit(
            int workspaceId,
            String recipientName,
            String recipientEmail,
            String documentTitle,
            String message,
            String acceptanceUrl,
            String locale) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("Document-link email requires a transaction synchronization");
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                emailService.send(
                    workspaceId,
                    recipientName,
                    recipientEmail,
                    documentTitle,
                    message,
                    acceptanceUrl,
                    locale);
            }
        });
    }

    private record NormalizedRecipient(
            Integer personId,
            String name,
            String email,
            String role,
            int recipientOrder) {
    }
}
