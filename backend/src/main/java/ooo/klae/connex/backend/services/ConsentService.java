package ooo.klae.connex.backend.services;

import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import ooo.klae.connex.backend.beans.ContactChannelConsent;
import ooo.klae.connex.backend.beans.ContactChannelConsentEvent;
import ooo.klae.connex.backend.dto.ContactChannelConsentDto;
import ooo.klae.connex.backend.dto.ContactChannelConsentRequest;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.exceptions.StaleRecordException;
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

    /** Applies trusted internal state changes, including absolute unsubscribe/provider revocations. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    @RequirePermission(Permission.CONSENT_MANAGE)
    public ContactChannelConsentDto setForPerson(int personId, ContactChannelConsentRequest request) {
        return writeForPerson(personId, request, false);
    }

    /** Conditionally updates the version explicitly reviewed by an HTTP caller. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    @RequirePermission(Permission.CONSENT_MANAGE)
    public ContactChannelConsentDto setForPersonConditionally(int personId, ContactChannelConsentRequest request) {
        if (request == null || request.expectedVersion() == null || request.expectedVersion() < 0) {
            throw new BadRequestException("An expected consent version is required");
        }
        return writeForPerson(personId, request, true);
    }

    private ContactChannelConsentDto writeForPerson(int personId, ContactChannelConsentRequest request, boolean conditional) {
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
        ContactChannelConsent before = conditional
                ? consentMapper.getBySubjectForUpdate(workspaceId, personId, channel, purpose)
                : consentMapper.getForPerson(workspaceId, personId).stream()
                        .filter(current -> channel.equals(current.getChannel()) && purpose.equals(current.getPurpose()))
                        .findFirst().orElse(null);
        String previousStatus = before == null ? null : before.getStatus();

        ContactChannelConsent consent = new ContactChannelConsent();
        consent.setWorkspaceId(workspaceId);
        consent.setPersonId(personId);
        consent.setChannel(channel);
        consent.setPurpose(purpose);
        consent.setStatus(status);
        consent.setSource(source);
        consent.setEvidenceRef(evidenceRef);
        consent.setCapturedAt(request.capturedAt());
        if (!conditional) {
            consentMapper.upsert(consent);
        } else {
            long expectedVersion = request.expectedVersion();
            if (expectedVersion == 0) {
                if (before != null) throw new StaleRecordException(before.getVersion());
                try {
                    consentMapper.insert(consent);
                } catch (DuplicateKeyException ex) {
                    throw staleConsent(workspaceId, personId, channel, purpose);
                }
            } else if (consentMapper.updateIfVersion(consent, expectedVersion) == 0) {
                throw staleConsent(workspaceId, personId, channel, purpose);
            }
        }
        ContactChannelConsent persisted = consentMapper.getBySubjectForUpdate(workspaceId, personId, channel, purpose);
        if (persisted == null) throw new ResourceNotFoundException("Consent state was not persisted");

        ContactChannelConsentEvent event = new ContactChannelConsentEvent();
        event.setWorkspaceId(workspaceId);
        event.setConsentId(persisted.getId());
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
        return toDto(persisted);
    }

    private StaleRecordException staleConsent(int workspaceId, int personId, String channel, String purpose) {
        requireOwnedPerson(workspaceId, personId);
        ContactChannelConsent current = consentMapper.getBySubject(workspaceId, personId, channel, purpose);
        return new StaleRecordException(current == null ? 0 : current.getVersion());
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
                consent.getUpdatedAt(), consent.getVersion());
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
