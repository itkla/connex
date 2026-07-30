package ooo.klae.connex.backend.services;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.beans.IdentityKeyRow;
import ooo.klae.connex.backend.mappers.IdentityCollisionMapper;
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

    private static final Comparator<IdentityGroup> IDENTITY_GROUP_ORDER =
        Comparator.comparing(IdentityGroup::kind)
            .thenComparing(IdentityGroup::normalizedValue);

    private final IdentityMapper identityMapper;
    private final IdentityCollisionMapper identityCollisionMapper;
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
        String normalizedEmail =
            emailAcquired ? normalized(IdentityKind.EMAIL, email) : null;
        String normalizedPhone =
            phoneAcquired ? normalized(IdentityKind.PHONE, phone) : null;
        List<IdentityGroup> affectedGroups = affectedGroups(
            identityMapper.lockCurrentPersonIdentityKeysForRecord(workspaceId, personId),
            emailAcquired, IdentityKind.EMAIL, normalizedEmail,
            phoneAcquired, IdentityKind.PHONE, normalizedPhone);
        lockPersonGroups(workspaceId, affectedGroups);
        deletePersonGroups(workspaceId, affectedGroups);
        if (emailAcquired) {
            reconcilePersonEmail(
                workspaceId, personId, email, normalizedEmail,
                source, sourceRowRef, acquiredAt);
        }
        if (phoneAcquired) {
            reconcilePersonPhone(
                workspaceId, personId, phone, normalizedPhone,
                source, sourceRowRef, acquiredAt);
        }
        insertPersonGroups(workspaceId, affectedGroups, acquiredAt);
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
        String normalizedDomain =
            websiteAcquired ? normalized(IdentityKind.DOMAIN, website) : null;
        String normalizedPhone =
            phoneAcquired ? normalized(IdentityKind.PHONE, phone) : null;
        List<IdentityGroup> affectedGroups = affectedGroups(
            identityMapper.lockCurrentCompanyIdentityKeysForRecord(workspaceId, companyId),
            websiteAcquired, IdentityKind.DOMAIN, normalizedDomain,
            phoneAcquired, IdentityKind.PHONE, normalizedPhone);
        lockCompanyGroups(workspaceId, affectedGroups);
        deleteCompanyGroups(workspaceId, affectedGroups);
        if (websiteAcquired) {
            reconcileCompanyDomain(
                workspaceId, companyId, website, normalizedDomain,
                source, sourceRowRef, acquiredAt);
        }
        if (phoneAcquired) {
            reconcileCompanyPhone(
                workspaceId, companyId, phone, normalizedPhone,
                source, sourceRowRef, acquiredAt);
        }
        insertCompanyGroups(workspaceId, affectedGroups, acquiredAt);
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

    private List<IdentityGroup> affectedGroups(
            List<IdentityKeyRow> currentKeys,
            boolean firstAcquired,
            IdentityKind firstKind,
            String firstNormalizedValue,
            boolean secondAcquired,
            IdentityKind secondKind,
            String secondNormalizedValue) {
        List<IdentityGroup> groups = new ArrayList<>();
        for (IdentityKeyRow currentKey : currentKeys) {
            IdentityKeyRow required = Objects.requireNonNull(currentKey, "current identity key");
            String kind = Objects.requireNonNull(required.getKind(), "current identity kind");
            if ((firstAcquired && firstKind.getDatabaseValue().equals(kind))
                    || (secondAcquired && secondKind.getDatabaseValue().equals(kind))) {
                groups.add(new IdentityGroup(
                    kind,
                    Objects.requireNonNull(
                        required.getNormalizedValue(), "current normalized identity value")));
            }
        }
        addGroup(groups, firstAcquired, firstKind, firstNormalizedValue);
        addGroup(groups, secondAcquired, secondKind, secondNormalizedValue);
        return groups.stream()
            .distinct()
            .sorted(IDENTITY_GROUP_ORDER)
            .toList();
    }

    private static void addGroup(
            List<IdentityGroup> groups,
            boolean acquired,
            IdentityKind kind,
            String normalizedValue) {
        if (acquired && normalizedValue != null) {
            groups.add(new IdentityGroup(kind.getDatabaseValue(), normalizedValue));
        }
    }

    private void lockPersonGroups(int workspaceId, List<IdentityGroup> groups) {
        for (IdentityGroup group : groups) {
            identityMapper.lockCurrentPersonIdentityGroup(
                workspaceId, group.kind(), group.normalizedValue());
        }
    }

    private void lockCompanyGroups(int workspaceId, List<IdentityGroup> groups) {
        for (IdentityGroup group : groups) {
            identityMapper.lockCurrentCompanyIdentityGroup(
                workspaceId, group.kind(), group.normalizedValue());
        }
    }

    private void deletePersonGroups(int workspaceId, List<IdentityGroup> groups) {
        for (IdentityGroup group : groups) {
            identityCollisionMapper.deletePersonCollisionGroup(
                workspaceId, group.kind(), group.normalizedValue());
        }
    }

    private void deleteCompanyGroups(int workspaceId, List<IdentityGroup> groups) {
        for (IdentityGroup group : groups) {
            identityCollisionMapper.deleteCompanyCollisionGroup(
                workspaceId, group.kind(), group.normalizedValue());
        }
    }

    private void insertPersonGroups(
            int workspaceId,
            List<IdentityGroup> groups,
            LocalDateTime rebuiltAt) {
        for (IdentityGroup group : groups) {
            identityCollisionMapper.insertPersonCollisionGroup(
                workspaceId, group.kind(), group.normalizedValue(), rebuiltAt);
        }
    }

    private void insertCompanyGroups(
            int workspaceId,
            List<IdentityGroup> groups,
            LocalDateTime rebuiltAt) {
        for (IdentityGroup group : groups) {
            identityCollisionMapper.insertCompanyCollisionGroup(
                workspaceId, group.kind(), group.normalizedValue(), rebuiltAt);
        }
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

    private record IdentityGroup(String kind, String normalizedValue) {
    }
}
