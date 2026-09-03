package ooo.klae.connex.backend.services;

import java.security.SecureRandom;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import ooo.klae.connex.backend.beans.Campaign;
import ooo.klae.connex.backend.beans.CampaignAudienceSnapshot;
import ooo.klae.connex.backend.beans.CampaignDelivery;
import ooo.klae.connex.backend.beans.CampaignMessage;
import ooo.klae.connex.backend.beans.CampaignMessageRevision;
import ooo.klae.connex.backend.beans.CampaignSend;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.capability.Capability;
import ooo.klae.connex.backend.capability.CapabilityRegistry;
import ooo.klae.connex.backend.delivery.ChannelAddressNormalizer;
import ooo.klae.connex.backend.delivery.DeliveryChannel;
import ooo.klae.connex.backend.delivery.DeliveryProviderConfigService;
import ooo.klae.connex.backend.delivery.DeliveryProviderException;
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
import ooo.klae.connex.backend.tenant.TenantContext;

/** Enrols one contact idempotently into a rollback-isolated triggered campaign send. */
@Service
@RequiredArgsConstructor
public class CampaignTriggeredSendService {

    private static final String PURPOSE = "marketing";
    private static final int TOKEN_BYTES = 32;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final List<Permission> REQUIRED_PERMISSIONS = List.of(
            Permission.CAMPAIGN_MANAGE,
            Permission.CAMPAIGN_SEND,
            Permission.CONSENT_MANAGE);

    private final CampaignMapper campaignMapper;
    private final CampaignMessageMapper campaignMessageMapper;
    private final CampaignSendMapper campaignSendMapper;
    private final CampaignDeliveryMapper campaignDeliveryMapper;
    private final PersonMapper personMapper;
    private final AudienceEligibilityService audienceEligibilityService;
    private final CapabilityRegistry capabilityRegistry;
    private final DeliveryProviderConfigService deliveryProviderConfigService;
    private final WorkspaceService workspaceService;
    private final TenantContext tenantContext;
    private final AuditService auditService;
    private final WorkflowTriggeredSendGate triggeredSendGate;

    /**
     * Queues one delivery after acquiring the authorization hierarchy before campaign state.
     *
     * @param personId triggering contact id
     * @param messageIdValue campaign message id
     * @param messageVersionValue immutable message revision
     * @return the stable enrollment outcome
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    @RequirePermission(Permission.CAMPAIGN_SEND)
    public EnrollmentResult enroll(int personId, Integer messageIdValue, Integer messageVersionValue) {
        Request request = requireRequest(personId, messageIdValue, messageVersionValue);
        int workspaceId = requireResolvedWorkspaceId();
        CampaignSend discovered = campaignSendMapper.getTriggeredSend(
                workspaceId, request.messageId(), request.messageVersion());
        int actorId = workspaceService.getCurrentUserId();
        Set<Permission> permissions = workspaceService.lockedPermissionsFor(workspaceId, actorId);
        return enrollLocked(workspaceId, actorId, permissions, request, discovered);
    }

    /**
     * Queues one delivery when the canonical workflow step already holds the actor's authorization
     * roots. The workflow transaction passes the exact locked permission snapshot so this method
     * does not reacquire those roots after locking {@code workflow_run}.
     */
    EnrollmentResult enrollWithLockedAuthorization(
            int personId,
            Integer messageIdValue,
            Integer messageVersionValue,
            int actorId,
            Set<Permission> lockedPermissions) {
        Request request = requireRequest(personId, messageIdValue, messageVersionValue);
        int workspaceId = requireResolvedWorkspaceId();
        CampaignSend discovered = campaignSendMapper.getTriggeredSend(
                workspaceId, request.messageId(), request.messageVersion());
        return enrollLocked(workspaceId, actorId, lockedPermissions, request, discovered);
    }

    private EnrollmentResult enrollLocked(
            int workspaceId,
            int actorId,
            Set<Permission> permissions,
            Request request,
            CampaignSend discoveredSend) {
        if (!triggeredSendGate.enabled()) {
            throw new ForbiddenException("Triggered campaign delivery is not enabled on this instance");
        }
        requirePermissions(permissions);
        CampaignMessage discoveredMessage = campaignMessageMapper.getMessage(
                workspaceId, request.messageId());
        if (discoveredMessage == null) {
            throw new ResourceNotFoundException(
                    "Campaign message not found with id: " + request.messageId());
        }
        Campaign campaign = campaignMapper.getCampaignForUpdate(
                workspaceId, discoveredMessage.getCampaignId());
        if (campaign == null) {
            throw new ResourceNotFoundException(
                    "Campaign not found with id: " + discoveredMessage.getCampaignId());
        }
        CampaignMessage message = campaignMessageMapper.getMessageForShare(
                workspaceId, request.messageId());
        if (message == null || message.getCampaignId() != campaign.getId()) {
            throw new ResourceNotFoundException(
                    "Campaign message not found with id: " + request.messageId());
        }
        CampaignMessageRevision revision = campaignMessageMapper.getRevisionForShare(
                workspaceId, request.messageId(), request.messageVersion());
        if (revision == null) {
            throw new ResourceNotFoundException(
                    "Campaign message revision not found for version: " + request.messageVersion());
        }
        DeliveryChannel channel = resolveChannel(message.getChannel());
        requireDeliveryReady(workspaceId, channel);

        AudienceEligibilityService.AudienceClassification eligibility =
                audienceEligibilityService.classify(
                    workspaceId, List.of(request.personId()), channel.token(), PURPOSE);
        String exclusionReason = eligibility.reasonFor(request.personId());
        if (exclusionReason != null) {
            auditExclusion(campaign, request, exclusionReason);
            return EnrollmentResult.excluded(exclusionReason);
        }
        Person person = personMapper.getPersonById(workspaceId, request.personId());
        if (person == null) {
            throw new ResourceNotFoundException(
                    "Contact not found with id: " + request.personId());
        }
        String address = ChannelAddressNormalizer.addressFor(channel, person);
        if (address == null) {
            throw new BadRequestException(
                    "The contact has no usable address for the " + channel.name() + " channel");
        }

        CampaignAudienceSnapshot snapshot = resolveSnapshot(
                workspaceId, actorId, campaign, message, revision);
        CampaignSend send = resolveSend(
                workspaceId, actorId, campaign, message, revision, snapshot, discoveredSend);
        if (!"triggered".equals(send.getStatus())) {
            throw new BadRequestException("The triggered campaign send is not accepting enrollments");
        }
        CampaignDelivery existing = campaignDeliveryMapper.getBySendAndPerson(
                workspaceId, send.getId(), request.personId());
        if (existing != null) {
            return replay(send.getId(), existing);
        }

        CampaignDelivery delivery = new CampaignDelivery();
        delivery.setWorkspaceId(workspaceId);
        delivery.setSendId(send.getId());
        delivery.setPersonId(request.personId());
        delivery.setAddress(address);
        delivery.setStatus("pending");
        delivery.setUnsubscribeToken(newToken());
        try {
            campaignDeliveryMapper.insertDeliveries(workspaceId, List.of(delivery));
        } catch (DuplicateKeyException exception) {
            CampaignDelivery concurrent = campaignDeliveryMapper.getBySendAndPerson(
                    workspaceId, send.getId(), request.personId());
            if (concurrent == null) {
                throw exception;
            }
            return replay(send.getId(), concurrent);
        }
        campaignSendMapper.refreshCounters(workspaceId, send.getId());
        CampaignDelivery inserted = campaignDeliveryMapper.getBySendAndPerson(
                workspaceId, send.getId(), request.personId());
        if (inserted == null) {
            throw new IllegalStateException("Triggered campaign delivery was not persisted");
        }
        auditService.record(
                "campaign.send.triggered_enroll",
                "campaign",
                campaign.getId(),
                campaign.getName(),
                "Queued campaign delivery",
                Map.of(
                        "sendId", send.getId(),
                        "messageId", request.messageId(),
                        "messageVersion", request.messageVersion()));
        return EnrollmentResult.queued(send.getId(), inserted.getId());
    }

    private static EnrollmentResult replay(int sendId, CampaignDelivery delivery) {
        if ("skipped".equals(delivery.getStatus())
                && "frequency_capped".equals(delivery.getSkipReason())) {
            return EnrollmentResult.capped(sendId, delivery.getId());
        }
        return EnrollmentResult.deduplicated(sendId, delivery.getId());
    }

    private CampaignAudienceSnapshot resolveSnapshot(
            int workspaceId,
            int actorId,
            Campaign campaign,
            CampaignMessage message,
            CampaignMessageRevision revision) {
        CampaignAudienceSnapshot existing = campaignMapper.getTriggeredSnapshot(
                workspaceId, message.getId(), revision.getVersion());
        if (existing != null) {
            return existing;
        }
        CampaignAudienceSnapshot snapshot = new CampaignAudienceSnapshot();
        snapshot.setWorkspaceId(workspaceId);
        snapshot.setCampaignId(campaign.getId());
        snapshot.setVersion(campaignMapper.nextSnapshotVersion(workspaceId, campaign.getId()));
        snapshot.setRecordType("person");
        snapshot.setDefinitionJson("{}");
        snapshot.setChannel(message.getChannel());
        snapshot.setPurpose(PURPOSE);
        snapshot.setOrigin("triggered");
        snapshot.setTriggeredMessageId(message.getId());
        snapshot.setTriggeredMessageVersion(revision.getVersion());
        snapshot.setEstimatedIncluded(0);
        snapshot.setExcludedTotal(0);
        snapshot.setExcludedConsent(0);
        snapshot.setExcludedSuppressed(0);
        snapshot.setExcludedRestricted(0);
        snapshot.setExcludedNoAddress(0);
        snapshot.setCreatedById(actorId);
        campaignMapper.insertSnapshot(snapshot);
        return snapshot;
    }

    private CampaignSend resolveSend(
            int workspaceId,
            int actorId,
            Campaign campaign,
            CampaignMessage message,
            CampaignMessageRevision revision,
            CampaignAudienceSnapshot snapshot,
            CampaignSend discovered) {
        if (discovered != null) {
            CampaignSend current = campaignSendMapper.getTriggeredSend(
                    workspaceId, message.getId(), revision.getVersion());
            if (current != null) {
                return current;
            }
        }
        CampaignSend send = new CampaignSend();
        send.setWorkspaceId(workspaceId);
        send.setCampaignId(campaign.getId());
        send.setSnapshotId(snapshot.getId());
        send.setOrigin("triggered");
        send.setMessageId(message.getId());
        send.setMessageVersion(revision.getVersion());
        send.setChannel(message.getChannel());
        send.setPurpose(PURPOSE);
        send.setStatus("triggered");
        send.setCreatedById(actorId);
        try {
            campaignSendMapper.insertSend(send);
        } catch (DuplicateKeyException exception) {
            CampaignSend concurrent = campaignSendMapper.getTriggeredSend(
                    workspaceId, message.getId(), revision.getVersion());
            if (concurrent == null) {
                throw exception;
            }
            return concurrent;
        }
        auditService.record(
                "campaign.send.triggered_create",
                "campaign",
                campaign.getId(),
                campaign.getName(),
                "Created triggered campaign send",
                Map.of(
                        "sendId", send.getId(),
                        "messageId", message.getId(),
                        "messageVersion", revision.getVersion()));
        return send;
    }

    private void auditExclusion(Campaign campaign, Request request, String reason) {
        auditService.recordStrict(
                "campaign.send.triggered_exclude",
                "campaign",
                campaign.getId(),
                campaign.getName(),
                "Skipped triggered campaign delivery",
                Map.of(
                        "messageId", request.messageId(),
                        "messageVersion", request.messageVersion(),
                        "reason", reason));
    }

    private void requireDeliveryReady(int workspaceId, DeliveryChannel channel) {
        if (!capabilityRegistry.isAvailable(Capability.CAMPAIGN_DELIVERY)) {
            throw new ForbiddenException("Campaign delivery is not enabled on this instance");
        }
        if (!deliveryProviderConfigService.isReady(workspaceId, channel)) {
            throw new BadRequestException(
                    "No usable transport is configured for delivery on the " + channel.name() + " channel");
        }
    }

    private static DeliveryChannel resolveChannel(String token) {
        try {
            DeliveryChannel channel = DeliveryChannel.fromToken(token);
            if (channel != DeliveryChannel.EMAIL && channel != DeliveryChannel.SMS) {
                throw new BadRequestException(
                        "The configured campaign channel cannot be sent in this release");
            }
            return channel;
        } catch (DeliveryProviderException exception) {
            throw new BadRequestException(
                    "The configured campaign channel cannot be sent in this release");
        }
    }

    private static Request requireRequest(
            int personId, Integer messageIdValue, Integer messageVersionValue) {
        if (personId <= 0 || messageIdValue == null || messageIdValue <= 0
                || messageVersionValue == null || messageVersionValue <= 0) {
            throw new BadRequestException("A contact and campaign message revision are required");
        }
        return new Request(personId, messageIdValue, messageVersionValue);
    }

    private static void requirePermissions(Set<Permission> permissions) {
        for (Permission permission : REQUIRED_PERMISSIONS) {
            if (!permissions.contains(permission)) {
                throw new ForbiddenException(
                        "Requires the " + permission.name() + " permission in this workspace");
            }
        }
    }

    private int requireResolvedWorkspaceId() {
        if (!tenantContext.isResolved()) {
            throw new ForbiddenException("A resolved workspace membership is required");
        }
        Integer workspaceId = tenantContext.getWorkspaceId();
        if (workspaceId == null || workspaceId <= 0) {
            throw new ForbiddenException("A resolved workspace membership is required");
        }
        return workspaceId;
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

    private record Request(int personId, int messageId, int messageVersion) { }

    /** Stable outcome of an idempotent triggered-delivery enrollment. */
    public record EnrollmentResult(
            int sendId, Integer deliveryId, String outcome, String exclusionReason) {

        static EnrollmentResult queued(int sendId, int deliveryId) {
            return new EnrollmentResult(sendId, deliveryId, "delivery_queued", null);
        }

        static EnrollmentResult deduplicated(int sendId, int deliveryId) {
            return new EnrollmentResult(sendId, deliveryId, "delivery_dedup_skipped", null);
        }

        static EnrollmentResult capped(int sendId, int deliveryId) {
            return new EnrollmentResult(sendId, deliveryId, "delivery_capped", null);
        }

        static EnrollmentResult excluded(String reason) {
            return new EnrollmentResult(0, null, null, reason);
        }

        /** Returns whether this call created a delivery row. */
        public boolean enrolled() {
            return "delivery_queued".equals(outcome);
        }
    }
}
