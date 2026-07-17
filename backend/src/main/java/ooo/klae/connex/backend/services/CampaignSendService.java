package ooo.klae.connex.backend.services;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import ooo.klae.connex.backend.beans.Campaign;
import ooo.klae.connex.backend.beans.CampaignAudienceMember;
import ooo.klae.connex.backend.beans.CampaignAudienceSnapshot;
import ooo.klae.connex.backend.beans.CampaignDelivery;
import ooo.klae.connex.backend.beans.CampaignMessage;
import ooo.klae.connex.backend.beans.CampaignMessageRevision;
import ooo.klae.connex.backend.beans.CampaignSend;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.capability.Capability;
import ooo.klae.connex.backend.capability.CapabilityRegistry;
import ooo.klae.connex.backend.delivery.DeliveryChannel;
import ooo.klae.connex.backend.delivery.DeliveryProviderConfigService;
import ooo.klae.connex.backend.dto.CampaignMessageDto;
import ooo.klae.connex.backend.dto.CampaignMessageRequest;
import ooo.klae.connex.backend.dto.CampaignMessageRevisionDto;
import ooo.klae.connex.backend.dto.CampaignMessageRevisionRequest;
import ooo.klae.connex.backend.dto.CampaignSendDto;
import ooo.klae.connex.backend.dto.CampaignSendRequest;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.CampaignDeliveryMapper;
import ooo.klae.connex.backend.mappers.CampaignMapper;
import ooo.klae.connex.backend.mappers.CampaignMessageMapper;
import ooo.klae.connex.backend.mappers.CampaignSendMapper;
import ooo.klae.connex.backend.mappers.PersonMapper;
import ooo.klae.connex.backend.tenant.Permission;
import ooo.klae.connex.backend.tenant.RequirePermission;

/**
 * The campaign send choke point: message and revision authoring, and creation/lifecycle of a send
 * bound to a frozen audience snapshot and an immutable message revision. Message and send CRUD are
 * gated by {@code CAMPAIGN_MANAGE}; firing a send (queue) additionally requires the
 * {@code CAMPAIGN_DELIVERY} capability and {@code CAMPAIGN_SEND}. Person data is only reachable with
 * {@code CONSENT_MANAGE}, mirroring the snapshot flow.
 */
@Service
@RequiredArgsConstructor
public class CampaignSendService {

    private static final String DEFAULT_CHANNEL = "email";
    private static final String DEFAULT_PURPOSE = "marketing";
    private static final Set<String> LOCALES = Set.of("en", "ja");
    private static final int SQL_BATCH_SIZE = 500;
    private static final int TOKEN_BYTES = 32;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final CampaignMapper campaignMapper;
    private final CampaignMessageMapper campaignMessageMapper;
    private final CampaignSendMapper campaignSendMapper;
    private final CampaignDeliveryMapper campaignDeliveryMapper;
    private final PersonMapper personMapper;
    private final CapabilityRegistry capabilityRegistry;
    private final DeliveryProviderConfigService deliveryProviderConfigService;
    private final WorkspaceService workspaceService;
    private final AuthService authService;
    private final AuditService auditService;

    /** Lists a campaign's messages with their immutable revisions. */
    @RequirePermission(Permission.CAMPAIGN_VIEW)
    public List<CampaignMessageDto> listMessages(int campaignId) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        requireCampaign(workspaceId, campaignId);
        return campaignMessageMapper.getMessages(workspaceId, campaignId).stream()
                .map(message -> toMessageDto(workspaceId, message)).toList();
    }

    /** Returns one campaign message with its revisions. */
    @RequirePermission(Permission.CAMPAIGN_VIEW)
    public CampaignMessageDto getMessage(int campaignId, int messageId) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        requireCampaign(workspaceId, campaignId);
        return toMessageDto(workspaceId, requireMessage(workspaceId, campaignId, messageId));
    }

    /** Creates a draft message under a campaign. */
    @Transactional
    @RequirePermission(Permission.CAMPAIGN_MANAGE)
    public CampaignMessageDto createMessage(int campaignId, CampaignMessageRequest request) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        Campaign campaign = requireCampaign(workspaceId, campaignId);
        if (request == null || request.name() == null || request.name().isBlank()) {
            throw new BadRequestException("Campaign message name is required");
        }
        String channel = normalizeChannel(request.channel());
        CampaignMessage message = new CampaignMessage();
        message.setWorkspaceId(workspaceId);
        message.setCampaignId(campaignId);
        message.setChannel(channel);
        message.setName(request.name().trim());
        message.setStatus("draft");
        message.setCreatedById(authService.getCurrentUser().getId());
        campaignMessageMapper.insertMessage(message);
        auditService.record("campaign.message.create", "campaign", campaignId, campaign.getName(),
                "Created campaign message " + message.getName(), null);
        return toMessageDto(workspaceId, requireMessage(workspaceId, campaignId, message.getId()));
    }

    /** Appends an immutable revision to a message and returns the updated message. */
    @Transactional
    @RequirePermission(Permission.CAMPAIGN_MANAGE)
    public CampaignMessageDto addRevision(int campaignId, int messageId, CampaignMessageRevisionRequest request) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        Campaign campaign = requireCampaign(workspaceId, campaignId);
        CampaignMessage message = requireMessage(workspaceId, campaignId, messageId);
        if (request == null) {
            throw new BadRequestException("Campaign message revision is required");
        }
        String locale = normalizeLocale(request.locale());
        if (request.subject() == null || request.subject().isBlank()) {
            throw new BadRequestException("Campaign message subject is required");
        }
        if (request.bodyHtml() == null || request.bodyHtml().isBlank()) {
            throw new BadRequestException("Campaign message body is required");
        }
        CampaignMessageRevision revision = new CampaignMessageRevision();
        revision.setWorkspaceId(workspaceId);
        revision.setMessageId(messageId);
        revision.setVersion(campaignMessageMapper.nextRevisionVersion(workspaceId, messageId));
        revision.setLocale(locale);
        revision.setSubject(request.subject().trim());
        revision.setBodyHtml(request.bodyHtml());
        revision.setBodyText(trimToNull(request.bodyText()));
        campaignMessageMapper.insertRevision(revision);
        auditService.record("campaign.message.revision", "campaign", campaignId, campaign.getName(),
                "Added campaign message revision", Map.of("messageId", messageId, "version", revision.getVersion()));
        return toMessageDto(workspaceId, message);
    }

    /** Lists a campaign's sends. */
    @RequirePermission(Permission.CAMPAIGN_VIEW)
    public List<CampaignSendDto> listSends(int campaignId) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        requireCampaign(workspaceId, campaignId);
        return campaignSendMapper.getSendsByCampaign(workspaceId, campaignId).stream()
                .map(CampaignSendService::toSendDto).toList();
    }

    /** Returns one send. */
    @RequirePermission(Permission.CAMPAIGN_VIEW)
    public CampaignSendDto getSend(int campaignId, int sendId) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        requireCampaign(workspaceId, campaignId);
        return toSendDto(requireSend(workspaceId, campaignId, sendId));
    }

    /**
     * Creates a draft send bound to a frozen snapshot version and message revision, materializing one
     * pending {@code campaign_delivery} row per included person that has a resolvable email address.
     */
    @Transactional
    @RequirePermission(Permission.CAMPAIGN_MANAGE)
    public CampaignSendDto createSend(int campaignId, CampaignSendRequest request) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        Campaign campaign = requireCampaign(workspaceId, campaignId);
        if (request == null) {
            throw new BadRequestException("Campaign send is required");
        }
        CampaignMessage message = requireMessage(workspaceId, campaignId, request.messageId());
        if (!DEFAULT_CHANNEL.equals(message.getChannel())) {
            throw new BadRequestException("Only email messages can be sent in this release");
        }
        CampaignMessageRevision revision =
                campaignMessageMapper.getRevision(workspaceId, request.messageId(), request.messageVersion());
        if (revision == null) {
            throw new ResourceNotFoundException("Campaign message revision not found for version: "
                    + request.messageVersion());
        }
        CampaignAudienceSnapshot snapshot =
                campaignMapper.getSnapshot(workspaceId, campaignId, request.snapshotVersion());
        if (snapshot == null) {
            throw new ResourceNotFoundException("Campaign audience snapshot not found for version: "
                    + request.snapshotVersion());
        }
        if (!"person".equals(snapshot.getRecordType())) {
            throw new BadRequestException("Only person audiences can be emailed");
        }
        workspaceService.requirePermission(Permission.CONSENT_MANAGE);
        String purpose = normalizePurpose(request.purpose());

        CampaignSend send = new CampaignSend();
        send.setWorkspaceId(workspaceId);
        send.setCampaignId(campaignId);
        send.setSnapshotId(snapshot.getId());
        send.setMessageId(message.getId());
        send.setMessageVersion(revision.getVersion());
        send.setChannel(DEFAULT_CHANNEL);
        send.setPurpose(purpose);
        send.setStatus("draft");
        send.setScheduledAt(request.scheduledAt());
        send.setCreatedById(authService.getCurrentUser().getId());
        List<CampaignDelivery> deliveries = materialize(workspaceId, snapshot.getId());
        send.setTotalRecipients(deliveries.size());
        campaignSendMapper.insertSend(send);
        for (CampaignDelivery delivery : deliveries) {
            delivery.setSendId(send.getId());
        }
        forEachBatch(deliveries, batch -> campaignDeliveryMapper.insertDeliveries(workspaceId, batch));
        auditService.record("campaign.send.create", "campaign", campaignId, campaign.getName(),
                "Created campaign send", Map.of("sendId", send.getId(), "recipients", deliveries.size()));
        return toSendDto(requireSend(workspaceId, campaignId, send.getId()));
    }

    /** Queues a draft send for background dispatch. */
    @Transactional
    @RequirePermission(Permission.CAMPAIGN_SEND)
    public CampaignSendDto queueSend(int campaignId, int sendId) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        Campaign campaign = requireCampaign(workspaceId, campaignId);
        if (!capabilityRegistry.isAvailable(Capability.CAMPAIGN_DELIVERY)) {
            throw new ForbiddenException("Campaign delivery is not enabled on this instance");
        }
        CampaignSend send = requireSend(workspaceId, campaignId, sendId);
        if (!deliveryProviderConfigService.isReady(workspaceId, DeliveryChannel.EMAIL)) {
            throw new BadRequestException("No usable mail transport is configured for delivery");
        }
        if (campaignSendMapper.transitionStatus(workspaceId, sendId, "draft", "queued") == 0) {
            throw new BadRequestException("Only a draft send can be queued");
        }
        auditService.record("campaign.send.queue", "campaign", campaignId, campaign.getName(),
                "Queued campaign send", Map.of("sendId", sendId));
        return toSendDto(requireSend(workspaceId, campaignId, sendId));
    }

    /** Pauses a queued or running send. */
    @Transactional
    @RequirePermission(Permission.CAMPAIGN_SEND)
    public CampaignSendDto pauseSend(int campaignId, int sendId) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        Campaign campaign = requireCampaign(workspaceId, campaignId);
        CampaignSend send = requireSend(workspaceId, campaignId, sendId);
        if (campaignSendMapper.transitionStatus(workspaceId, sendId, "queued", "paused") == 0
                && campaignSendMapper.transitionStatus(workspaceId, sendId, "running", "paused") == 0) {
            throw new BadRequestException("Only a queued or running send can be paused");
        }
        auditService.record("campaign.send.pause", "campaign", campaignId, campaign.getName(),
                "Paused campaign send", Map.of("sendId", sendId));
        return toSendDto(requireSend(workspaceId, campaignId, sendId));
    }

    /** Cancels a send that has not completed. */
    @Transactional
    @RequirePermission(Permission.CAMPAIGN_SEND)
    public CampaignSendDto cancelSend(int campaignId, int sendId) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        Campaign campaign = requireCampaign(workspaceId, campaignId);
        CampaignSend send = requireSend(workspaceId, campaignId, sendId);
        boolean cancelled = campaignSendMapper.transitionStatus(workspaceId, sendId, "draft", "cancelled") > 0
                || campaignSendMapper.transitionStatus(workspaceId, sendId, "queued", "cancelled") > 0
                || campaignSendMapper.transitionStatus(workspaceId, sendId, "running", "cancelled") > 0
                || campaignSendMapper.transitionStatus(workspaceId, sendId, "paused", "cancelled") > 0;
        if (!cancelled) {
            throw new BadRequestException("A completed send cannot be cancelled");
        }
        auditService.record("campaign.send.cancel", "campaign", campaignId, campaign.getName(),
                "Cancelled campaign send", Map.of("sendId", sendId));
        return toSendDto(requireSend(workspaceId, campaignId, sendId));
    }

    private List<CampaignDelivery> materialize(int workspaceId, int snapshotId) {
        List<Integer> personIds = campaignMapper.getSnapshotMembers(workspaceId, snapshotId).stream()
                .filter(member -> "included".equals(member.getStatus()))
                .map(CampaignAudienceMember::getRecordId)
                .toList();
        if (personIds.isEmpty()) {
            return List.of();
        }
        Map<Integer, String> addresses = new HashMap<>();
        for (Person person : personMapper.getByIds(workspaceId, personIds)) {
            if (person.getEmail() != null && !person.getEmail().isBlank()) {
                addresses.put(person.getId(), person.getEmail().trim().toLowerCase(Locale.ROOT));
            }
        }
        List<CampaignDelivery> deliveries = new ArrayList<>();
        for (int personId : personIds) {
            String address = addresses.get(personId);
            if (address == null) {
                continue;
            }
            CampaignDelivery delivery = new CampaignDelivery();
            delivery.setWorkspaceId(workspaceId);
            delivery.setPersonId(personId);
            delivery.setAddress(address);
            delivery.setStatus("pending");
            delivery.setUnsubscribeToken(newToken());
            deliveries.add(delivery);
        }
        return deliveries;
    }

    private Campaign requireCampaign(int workspaceId, int campaignId) {
        Campaign campaign = campaignMapper.getCampaign(workspaceId, campaignId);
        if (campaign == null) {
            throw new ResourceNotFoundException("Campaign not found with id: " + campaignId);
        }
        return campaign;
    }

    private CampaignMessage requireMessage(int workspaceId, int campaignId, int messageId) {
        CampaignMessage message = campaignMessageMapper.getMessage(workspaceId, messageId);
        if (message == null || message.getCampaignId() != campaignId) {
            throw new ResourceNotFoundException("Campaign message not found with id: " + messageId);
        }
        return message;
    }

    private CampaignSend requireSend(int workspaceId, int campaignId, int sendId) {
        CampaignSend send = campaignSendMapper.getSend(workspaceId, sendId);
        if (send == null || send.getCampaignId() != campaignId) {
            throw new ResourceNotFoundException("Campaign send not found with id: " + sendId);
        }
        return send;
    }

    private CampaignMessageDto toMessageDto(int workspaceId, CampaignMessage message) {
        List<CampaignMessageRevisionDto> revisions =
                campaignMessageMapper.getRevisions(workspaceId, message.getId()).stream()
                        .map(revision -> new CampaignMessageRevisionDto(
                                revision.getVersion(), revision.getLocale(), revision.getSubject(),
                                revision.getBodyHtml(), revision.getBodyText(), revision.getCreatedAt()))
                        .toList();
        return new CampaignMessageDto(
                message.getId(), message.getCampaignId(), message.getChannel(), message.getName(),
                message.getStatus(), message.getCreatedById(), message.getCreatedAt(),
                message.getUpdatedAt(), revisions);
    }

    private static CampaignSendDto toSendDto(CampaignSend send) {
        return new CampaignSendDto(
                send.getId(), send.getCampaignId(), send.getSnapshotId(), send.getMessageId(),
                send.getMessageVersion(), send.getChannel(), send.getPurpose(), send.getProviderId(),
                send.getStatus(), send.getScheduledAt(), send.getStartedAt(), send.getCompletedAt(),
                send.getTotalRecipients(), send.getDispatchedCount(), send.getSkippedCount(),
                send.getFailedCount(), send.getCreatedById(), send.getCreatedAt(), send.getUpdatedAt());
    }

    private static String normalizeChannel(String value) {
        if (value == null || value.isBlank()) {
            return DEFAULT_CHANNEL;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (!DEFAULT_CHANNEL.equals(normalized)) {
            throw new BadRequestException("Only the email channel is supported in this release");
        }
        return normalized;
    }

    private static String normalizeLocale(String value) {
        String normalized = value == null || value.isBlank() ? "en" : value.trim().toLowerCase(Locale.ROOT);
        if (!LOCALES.contains(normalized)) {
            throw new BadRequestException("Campaign message locale must be en or ja");
        }
        return normalized;
    }

    private static String normalizePurpose(String value) {
        if (value == null || value.isBlank()) {
            return DEFAULT_PURPOSE;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (!normalized.matches("[a-z][a-z0-9_-]{0,31}")) {
            throw new BadRequestException("Campaign send purpose is invalid");
        }
        return normalized;
    }

    private static String newToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        StringBuilder builder = new StringBuilder(TOKEN_BYTES * 2);
        for (byte value : bytes) {
            builder.append(Character.forDigit((value >> 4) & 0xF, 16));
            builder.append(Character.forDigit(value & 0xF, 16));
        }
        return builder.toString();
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static <T> void forEachBatch(List<T> values, Consumer<List<T>> consumer) {
        for (int offset = 0; offset < values.size(); offset += SQL_BATCH_SIZE) {
            consumer.accept(values.subList(offset, Math.min(values.size(), offset + SQL_BATCH_SIZE)));
        }
    }
}
