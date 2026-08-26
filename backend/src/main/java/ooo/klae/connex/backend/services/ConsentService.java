package ooo.klae.connex.backend.services;

import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import ooo.klae.connex.backend.beans.ContactChannelConsent;
import ooo.klae.connex.backend.beans.ContactChannelConsentEvent;
import ooo.klae.connex.backend.dto.ContactChannelConsentDto;
import ooo.klae.connex.backend.dto.ContactChannelConsentRequest;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.ConsentMapper;
import ooo.klae.connex.backend.mappers.PersonMapper;
import ooo.klae.connex.backend.tenant.Permission;
import ooo.klae.connex.backend.tenant.RequirePermission;

/** Business logic for workspace-owned person consent and append-only consent history. */
@Service
@RequiredArgsConstructor
public class ConsentService {
    private static final Set<String> CHANNELS = Set.of("email", "sms", "line", "whatsapp");
    private static final Set<String> STATUSES = Set.of("granted", "revoked", "unknown");

    private final ConsentMapper consentMapper;
    private final PersonMapper personMapper;
    private final WorkspaceService workspaceService;
    private final AuthService authService;
    private final AuditService auditService;

    /** Returns every current consent state for an owned person. */
    @RequirePermission(Permission.CONSENT_MANAGE)
    public List<ContactChannelConsentDto> getForPerson(int personId) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        requireOwnedPerson(workspaceId, personId);
        return consentMapper.getForPerson(workspaceId, personId).stream()
                .map(ConsentService::toDto).toList();
    }

    /** Upserts one consent state and appends a history event for every accepted change request. */
    @Transactional
    @RequirePermission(Permission.CONSENT_MANAGE)
    public ContactChannelConsentDto setForPerson(int personId, ContactChannelConsentRequest request) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        requireOwnedPerson(workspaceId, personId);
        if (request == null) {
            throw new BadRequestException("Consent state is required");
        }
        String channel = normalize(request.channel());
        String purpose = normalizeToken(request.purpose(), "Consent purpose");
        String status = normalize(request.status());
        if (!CHANNELS.contains(channel)) {
            throw new BadRequestException("Consent channel is invalid");
        }
        if (!STATUSES.contains(status)) {
            throw new BadRequestException("Consent status is invalid");
        }
        String source = requireText(request.source(), "Consent source", 64);
        String evidenceRef = trimToNull(request.evidenceRef());
        String previousStatus = consentMapper.getForPerson(workspaceId, personId).stream()
                .filter(current -> channel.equals(current.getChannel()) && purpose.equals(current.getPurpose()))
                .map(ContactChannelConsent::getStatus)
                .findFirst()
                .orElse(null);

        ContactChannelConsent consent = new ContactChannelConsent();
        consent.setWorkspaceId(workspaceId);
        consent.setPersonId(personId);
        consent.setChannel(channel);
        consent.setPurpose(purpose);
        consent.setStatus(status);
        consent.setSource(source);
        consent.setEvidenceRef(evidenceRef);
        consent.setCapturedAt(request.capturedAt());
        consentMapper.upsert(consent);

        ContactChannelConsentEvent event = new ContactChannelConsentEvent();
        event.setWorkspaceId(workspaceId);
        event.setConsentId(consent.getId());
        event.setPersonId(personId);
        event.setChannel(channel);
        event.setPurpose(purpose);
        event.setStatus(status);
        event.setSource(source);
        event.setEvidenceRef(evidenceRef);
        event.setCreatedById(authService.getCurrentUser().getId());
        consentMapper.insertEvent(event);
        auditService.record("consent.update", "person", personId, null,
                "Updated contact consent", auditService.singleChange("status", previousStatus, status));
        return consentMapper.getForPerson(workspaceId, personId).stream()
                .filter(current -> channel.equals(current.getChannel()) && purpose.equals(current.getPurpose()))
                .findFirst()
                .map(ConsentService::toDto)
                .orElseThrow(() -> new ResourceNotFoundException("Consent state was not persisted"));
    }

    private void requireOwnedPerson(int workspaceId, int personId) {
        if (!personMapper.existsOwned(workspaceId, personId)) {
            throw new ResourceNotFoundException("Contact not found");
        }
    }

    private static ContactChannelConsentDto toDto(ContactChannelConsent consent) {
        return new ContactChannelConsentDto(
                consent.getId(), consent.getPersonId(), consent.getChannel(), consent.getPurpose(),
                consent.getStatus(), consent.getSource(), consent.getEvidenceRef(), consent.getCapturedAt(),
                consent.getUpdatedAt());
    }

    private static String normalizeToken(String value, String label) {
        String normalized = normalize(value);
        if (normalized == null || !normalized.matches("[a-z][a-z0-9_-]{0,31}")) {
            throw new BadRequestException(label + " is invalid");
        }
        return normalized;
    }

    private static String requireText(String value, String label, int max) {
        if (value == null || value.isBlank() || value.trim().length() > max) {
            throw new BadRequestException(label + " is required and must not exceed " + max + " characters");
        }
        return value.trim();
    }

    private static String normalize(String value) {
        return value == null ? null : value.trim().toLowerCase(Locale.ROOT);
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
