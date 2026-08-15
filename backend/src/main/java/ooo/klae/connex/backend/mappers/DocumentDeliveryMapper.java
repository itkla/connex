package ooo.klae.connex.backend.mappers;

import java.time.LocalDateTime;
import java.util.List;

import org.apache.ibatis.annotations.Param;

import ooo.klae.connex.backend.beans.DocumentDelivery;
import ooo.klae.connex.backend.beans.DocumentDeliveryArtifact;
import ooo.klae.connex.backend.beans.DocumentDeliveryEvent;
import ooo.klae.connex.backend.beans.DocumentDeliveryRecipient;

/** Workspace-scoped persistence for document-delivery envelopes and their immutable children. */
public interface DocumentDeliveryMapper {
    List<DocumentDelivery> getByDocument(
            @Param("workspaceId") int workspaceId,
            @Param("documentId") int documentId);

    DocumentDelivery getById(
            @Param("workspaceId") int workspaceId,
            @Param("id") int id);

    DocumentDelivery lockById(
            @Param("workspaceId") int workspaceId,
            @Param("id") int id);

    DocumentDelivery findActiveByDocument(
            @Param("workspaceId") int workspaceId,
            @Param("documentId") int documentId);

    DocumentDelivery findByProviderEnvelope(
            @Param("workspaceId") int workspaceId,
            @Param("provider") String provider,
            @Param("providerEnvelopeId") String providerEnvelopeId);

    List<Integer> workspaceIdsWithExpired();

    List<Integer> findDueDeliveryIds(
            @Param("workspaceId") int workspaceId,
            @Param("now") LocalDateTime now,
            @Param("limit") int limit);

    int insertDelivery(DocumentDelivery delivery);

    int setProviderEnvelopeId(
            @Param("workspaceId") int workspaceId,
            @Param("id") int id,
            @Param("providerEnvelopeId") String providerEnvelopeId);

    int markDeliveryViewed(
            @Param("workspaceId") int workspaceId,
            @Param("id") int id);

    int completeDelivery(
            @Param("workspaceId") int workspaceId,
            @Param("id") int id,
            @Param("completedAt") LocalDateTime completedAt);

    int terminateDelivery(
            @Param("workspaceId") int workspaceId,
            @Param("id") int id,
            @Param("status") String status,
            @Param("terminatedAt") LocalDateTime terminatedAt,
            @Param("reason") String reason);

    int insertRecipient(DocumentDeliveryRecipient recipient);

    List<DocumentDeliveryRecipient> getRecipients(
            @Param("workspaceId") int workspaceId,
            @Param("deliveryId") int deliveryId);

    List<DocumentDeliveryRecipient> getRecipientsByDeliveryIds(
            @Param("workspaceId") int workspaceId,
            @Param("deliveryIds") List<Integer> deliveryIds);

    DocumentDeliveryRecipient getRecipient(
            @Param("workspaceId") int workspaceId,
            @Param("deliveryId") int deliveryId,
            @Param("id") int id);

    DocumentDeliveryRecipient lockRecipient(
            @Param("workspaceId") int workspaceId,
            @Param("deliveryId") int deliveryId,
            @Param("id") int id);

    DocumentDeliveryRecipient getRecipientByTokenHash(
            @Param("workspaceId") int workspaceId,
            @Param("tokenHash") String tokenHash);

    DocumentDeliveryRecipient getRecipientByProviderId(
            @Param("workspaceId") int workspaceId,
            @Param("deliveryId") int deliveryId,
            @Param("providerRecipientId") String providerRecipientId);

    List<Integer> getRecipientIds(
            @Param("workspaceId") int workspaceId,
            @Param("deliveryId") int deliveryId);

    int updateRecipientToken(
            @Param("workspaceId") int workspaceId,
            @Param("deliveryId") int deliveryId,
            @Param("id") int id,
            @Param("tokenHash") String tokenHash,
            @Param("tokenExpiresAt") LocalDateTime tokenExpiresAt,
            @Param("providerRecipientId") String providerRecipientId);

    int markRecipientViewed(
            @Param("workspaceId") int workspaceId,
            @Param("deliveryId") int deliveryId,
            @Param("id") int id,
            @Param("viewedAt") LocalDateTime viewedAt);

    int completeRecipient(
            @Param("workspaceId") int workspaceId,
            @Param("deliveryId") int deliveryId,
            @Param("id") int id,
            @Param("typedName") String typedName,
            @Param("evidenceIpHash") String evidenceIpHash,
            @Param("evidenceAgentHash") String evidenceAgentHash,
            @Param("decidedAt") LocalDateTime decidedAt);

    int completeProviderRecipient(
            @Param("workspaceId") int workspaceId,
            @Param("deliveryId") int deliveryId,
            @Param("id") int id,
            @Param("decidedAt") LocalDateTime decidedAt);

    int declineRecipient(
            @Param("workspaceId") int workspaceId,
            @Param("deliveryId") int deliveryId,
            @Param("id") int id,
            @Param("reason") String reason,
            @Param("evidenceIpHash") String evidenceIpHash,
            @Param("evidenceAgentHash") String evidenceAgentHash,
            @Param("decidedAt") LocalDateTime decidedAt);

    int completeViewersAndInvalidateTokens(
            @Param("workspaceId") int workspaceId,
            @Param("deliveryId") int deliveryId);

    int closeOutstandingRecipients(
            @Param("workspaceId") int workspaceId,
            @Param("deliveryId") int deliveryId,
            @Param("status") String status,
            @Param("exceptRecipientId") Integer exceptRecipientId);

    int invalidateAllTokens(
            @Param("workspaceId") int workspaceId,
            @Param("deliveryId") int deliveryId);

    int invalidateTokensExcept(
            @Param("workspaceId") int workspaceId,
            @Param("deliveryId") int deliveryId,
            @Param("exceptRecipientId") Integer exceptRecipientId);

    int countIncompleteSigners(
            @Param("workspaceId") int workspaceId,
            @Param("deliveryId") int deliveryId);

    int insertEvent(DocumentDeliveryEvent event);

    boolean hasEvent(
            @Param("workspaceId") int workspaceId,
            @Param("deliveryId") int deliveryId,
            @Param("recipientId") Integer recipientId,
            @Param("eventType") String eventType);

    boolean hasExternalEvent(
            @Param("workspaceId") int workspaceId,
            @Param("deliveryId") int deliveryId,
            @Param("externalEventId") String externalEventId);

    List<DocumentDeliveryEvent> getEventsByDeliveryIds(
            @Param("workspaceId") int workspaceId,
            @Param("deliveryIds") List<Integer> deliveryIds);

    int insertArtifact(DocumentDeliveryArtifact artifact);

    DocumentDeliveryArtifact getArtifact(
            @Param("workspaceId") int workspaceId,
            @Param("deliveryId") int deliveryId,
            @Param("id") int id);

    DocumentDeliveryArtifact getArtifactByKind(
            @Param("workspaceId") int workspaceId,
            @Param("deliveryId") int deliveryId,
            @Param("kind") String kind);

    List<DocumentDeliveryArtifact> getArtifactsByDeliveryIds(
            @Param("workspaceId") int workspaceId,
            @Param("deliveryIds") List<Integer> deliveryIds);

    List<DocumentDeliveryArtifact> getArtifactsByDeal(
            @Param("workspaceId") int workspaceId,
            @Param("dealId") int dealId);
}
