package ooo.klae.connex.backend.services;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.mappers.IdentityMapper;

/**
 * Persists canonical identities with acquisition provenance inside the parent-record transaction.
 *
 * <p>Identity rows are retained as history when a scalar changes. The old row receives
 * {@code superseded_at}, and only unsuperseded rows are eligible for matching. Repeated intake of
 * the same canonical key is idempotent; if a historical key becomes current again, its first raw
 * value, acquisition time, and provenance remain authoritative. A blank or invalid replacement
 * supersedes the previous current key without inserting a new identity and never prevents the
 * parent record from being saved.
 *
 * <p>{@code purpose_of_use_code} remains null for every intake path because Connex does not yet
 * have the governed APPI purpose registry required to assign a truthful code. Intake must not
 * invent one.
 */
@Service
@RequiredArgsConstructor
public class IdentityIntakeService {

    private final IdentityMapper identityMapper;
    private final MatchingService matchingService;
    private final Clock clock;

    /**
     * Reconciles current person email and phone identities.
     *
     * @param workspaceId owning workspace derived from tenant context
     * @param personId persisted person id
     * @param email current raw email value
     * @param phone current raw phone value
     * @param source acquisition source
     * @param sourceRowRef optional import row or request reference
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordPerson(
            int workspaceId,
            int personId,
            String email,
            String phone,
            IdentityAcquisitionSource source,
            String sourceRowRef) {
        recordPerson(
            workspaceId, personId, email, phone, source, sourceRowRef, true, true);
    }

    /**
     * Reconciles only person fields actually supplied by an import row.
     *
     * @param workspaceId owning workspace derived from tenant context
     * @param personId persisted person id
     * @param email current raw email value
     * @param phone current raw phone value
     * @param source acquisition source
     * @param sourceRowRef optional import row or request reference
     * @param emailAcquired whether this intake supplied the email field
     * @param phoneAcquired whether this intake supplied the phone field
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordPerson(
            int workspaceId,
            int personId,
            String email,
            String phone,
            IdentityAcquisitionSource source,
            String sourceRowRef,
            boolean emailAcquired,
            boolean phoneAcquired) {
        requireRecord(workspaceId, personId, source);
        LocalDateTime acquiredAt = now();
        if (emailAcquired) {
            reconcilePersonEmail(
                workspaceId, personId, email, normalized(IdentityKind.EMAIL, email),
                source, sourceRowRef, acquiredAt);
        }
        if (phoneAcquired) {
            reconcilePersonPhone(
                workspaceId, personId, phone, normalized(IdentityKind.PHONE, phone),
                source, sourceRowRef, acquiredAt);
        }
    }

    /**
     * Reconciles current company website-domain and phone identities.
     *
     * @param workspaceId owning workspace derived from tenant context
     * @param companyId persisted company id
     * @param website current raw website or domain value
     * @param phone current raw phone value
     * @param source acquisition source
     * @param sourceRowRef optional import row or request reference
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordCompany(
            int workspaceId,
            int companyId,
            String website,
            String phone,
            IdentityAcquisitionSource source,
            String sourceRowRef) {
        recordCompany(
            workspaceId, companyId, website, phone, source, sourceRowRef, true, true);
    }

    /**
     * Reconciles only company fields actually supplied by an import row.
     *
     * @param workspaceId owning workspace derived from tenant context
     * @param companyId persisted company id
     * @param website current raw website or domain value
     * @param phone current raw phone value
     * @param source acquisition source
     * @param sourceRowRef optional import row or request reference
     * @param websiteAcquired whether this intake supplied the website field
     * @param phoneAcquired whether this intake supplied the phone field
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordCompany(
            int workspaceId,
            int companyId,
            String website,
            String phone,
            IdentityAcquisitionSource source,
            String sourceRowRef,
            boolean websiteAcquired,
            boolean phoneAcquired) {
        requireRecord(workspaceId, companyId, source);
        LocalDateTime acquiredAt = now();
        if (websiteAcquired) {
            reconcileCompanyDomain(
                workspaceId, companyId, website, normalized(IdentityKind.DOMAIN, website),
                source, sourceRowRef, acquiredAt);
        }
        if (phoneAcquired) {
            reconcileCompanyPhone(
                workspaceId, companyId, phone, normalized(IdentityKind.PHONE, phone),
                source, sourceRowRef, acquiredAt);
        }
    }

    private void reconcilePersonEmail(
            int workspaceId,
            int personId,
            String rawValue,
            String normalizedValue,
            IdentityAcquisitionSource source,
            String sourceRowRef,
            LocalDateTime acquiredAt) {
        identityMapper.supersedePersonEmailIdentities(
            workspaceId, personId, rawValue, normalizedValue, acquiredAt);
        if (normalizedValue != null) {
            identityMapper.upsertPersonEmailIdentity(
                workspaceId, personId, rawValue, normalizedValue,
                source.getDatabaseValue(), sourceRowRef, acquiredAt);
        }
    }

    private void reconcilePersonPhone(
            int workspaceId,
            int personId,
            String rawValue,
            String normalizedValue,
            IdentityAcquisitionSource source,
            String sourceRowRef,
            LocalDateTime acquiredAt) {
        identityMapper.supersedePersonPhoneIdentities(
            workspaceId, personId, rawValue, normalizedValue, acquiredAt);
        if (normalizedValue != null) {
            identityMapper.upsertPersonPhoneIdentity(
                workspaceId, personId, rawValue, normalizedValue,
                source.getDatabaseValue(), sourceRowRef, acquiredAt);
        }
    }

    private void reconcileCompanyDomain(
            int workspaceId,
            int companyId,
            String rawValue,
            String normalizedValue,
            IdentityAcquisitionSource source,
            String sourceRowRef,
            LocalDateTime acquiredAt) {
        identityMapper.supersedeCompanyDomainIdentities(
            workspaceId, companyId, rawValue, normalizedValue, acquiredAt);
        if (normalizedValue != null) {
            identityMapper.upsertCompanyDomainIdentity(
                workspaceId, companyId, rawValue, normalizedValue,
                source.getDatabaseValue(), sourceRowRef, acquiredAt);
        }
    }

    private void reconcileCompanyPhone(
            int workspaceId,
            int companyId,
            String rawValue,
            String normalizedValue,
            IdentityAcquisitionSource source,
            String sourceRowRef,
            LocalDateTime acquiredAt) {
        identityMapper.supersedeCompanyPhoneIdentities(
            workspaceId, companyId, rawValue, normalizedValue, acquiredAt);
        if (normalizedValue != null) {
            identityMapper.upsertCompanyPhoneIdentity(
                workspaceId, companyId, rawValue, normalizedValue,
                source.getDatabaseValue(), sourceRowRef, acquiredAt);
        }
    }

    private String normalized(IdentityKind kind, String rawValue) {
        Optional<String> normalized = matchingService.normalizeIdentifier(kind, rawValue);
        return normalized.orElse(null);
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).withNano(0);
    }

    private static void requireRecord(
            int workspaceId, int recordId, IdentityAcquisitionSource source) {
        if (workspaceId <= 0 || recordId <= 0) {
            throw new IllegalArgumentException("Identity intake requires a persisted tenant record");
        }
        Objects.requireNonNull(source, "source");
    }
}
