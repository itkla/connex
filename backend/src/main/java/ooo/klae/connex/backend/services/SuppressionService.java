package ooo.klae.connex.backend.services;

import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import ooo.klae.connex.backend.beans.SuppressionEntry;
import ooo.klae.connex.backend.delivery.ChannelAddressNormalizer;
import ooo.klae.connex.backend.delivery.DeliveryChannel;
import ooo.klae.connex.backend.dto.SuppressionEntryDto;
import ooo.klae.connex.backend.dto.SuppressionEntryRequest;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.PersonMapper;
import ooo.klae.connex.backend.mappers.SuppressionMapper;
import ooo.klae.connex.backend.tenant.Permission;
import ooo.klae.connex.backend.tenant.RequirePermission;

/**
 * Business logic for workspace-owned contact-channel suppression entries. Addresses are stored in the
 * canonical form {@link ChannelAddressNormalizer} defines for the entry's channel, which is the same
 * form the send path materializes and re-checks, so a suppression matches regardless of how the
 * address was formatted when it was captured.
 */
@Service
@RequiredArgsConstructor
public class SuppressionService {
    private static final Set<String> SCOPES = Set.of("workspace", "global");
    private static final Set<String> CHANNELS = Set.of("email", "sms", "line", "whatsapp");
    private static final Set<String> REASONS = Set.of(
            "unsubscribe", "hard_bounce", "complaint", "do_not_contact", "manual");

    private final SuppressionMapper suppressionMapper;
    private final PersonMapper personMapper;
    private final WorkspaceService workspaceService;
    private final AuthService authService;
    private final AuditService auditService;

    /** Lists suppression entries owned by the active workspace. */
    @RequirePermission(Permission.CONSENT_MANAGE)
    public List<SuppressionEntryDto> list() {
        return suppressionMapper.getAll(workspaceService.getCurrentWorkspaceId())
                .stream().map(SuppressionService::toDto).toList();
    }

    /** Adds a normalized suppression entry in the active workspace. */
    @Transactional
    @RequirePermission(Permission.CONSENT_MANAGE)
    public SuppressionEntryDto add(SuppressionEntryRequest request) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        if (request == null) {
            throw new BadRequestException("Suppression entry is required");
        }
        String scope = normalize(request.scope());
        String channel = normalize(request.channel());
        String reason = normalize(request.reason());
        if (!SCOPES.contains(scope)) {
            throw new BadRequestException("Suppression scope is invalid");
        }
        if (!CHANNELS.contains(channel)) {
            throw new BadRequestException("Suppression channel is invalid");
        }
        if (!REASONS.contains(reason)) {
            throw new BadRequestException("Suppression reason is invalid");
        }
        String address = normalizeAddress(channel, request.address());
        if (request.personId() != null && !personMapper.existsOwned(workspaceId, request.personId())) {
            throw new ResourceNotFoundException("Contact not found");
        }
        SuppressionEntry entry = new SuppressionEntry();
        entry.setWorkspaceId(workspaceId);
        entry.setScope(scope);
        entry.setChannel(channel);
        entry.setAddress(address);
        entry.setPersonId(request.personId());
        entry.setReason(reason);
        entry.setNote(trimToNull(request.note()));
        entry.setCreatedById(authService.getCurrentUser().getId());
        suppressionMapper.insert(entry);
        auditService.record("suppression.create", "suppression", entry.getId(), null,
                "Created suppression entry", null);
        return toDto(requireEntry(workspaceId, entry.getId()));
    }

    /** Removes a suppression entry owned by the active workspace. */
    @Transactional
    @RequirePermission(Permission.CONSENT_MANAGE)
    public void remove(int id) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        requireEntry(workspaceId, id);
        if (suppressionMapper.delete(workspaceId, id) == 0) {
            throw new ResourceNotFoundException("Suppression entry not found with id: " + id);
        }
        auditService.record("suppression.delete", "suppression", id, null,
                "Deleted suppression entry", null);
    }

    private SuppressionEntry requireEntry(int workspaceId, int id) {
        SuppressionEntry entry = suppressionMapper.getById(workspaceId, id);
        if (entry == null) {
            throw new ResourceNotFoundException("Suppression entry not found with id: " + id);
        }
        return entry;
    }

    private static SuppressionEntryDto toDto(SuppressionEntry entry) {
        return new SuppressionEntryDto(
                entry.getId(), entry.getScope(), entry.getChannel(), entry.getAddress(),
                entry.getPersonId(), entry.getReason(), entry.getNote(), entry.getCreatedById(),
                entry.getCreatedAt());
    }

    private static String normalizeAddress(String channel, String value) {
        if (value == null || value.isBlank()) {
            throw new BadRequestException("Suppression address is required");
        }
        String normalized = ChannelAddressNormalizer.normalize(DeliveryChannel.fromToken(channel), value);
        if (normalized == null || normalized.isBlank()) {
            throw new BadRequestException("Suppression address is not valid for the " + channel + " channel");
        }
        if (normalized.length() > 320) {
            throw new BadRequestException("Suppression address must not exceed 320 characters");
        }
        return normalized;
    }

    private static String normalize(String value) {
        return value == null ? null : value.trim().toLowerCase(Locale.ROOT);
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
