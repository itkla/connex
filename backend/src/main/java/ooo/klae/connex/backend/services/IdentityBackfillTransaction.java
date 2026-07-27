package ooo.klae.connex.backend.services;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.beans.CompanyIdentityBackfillCandidate;
import ooo.klae.connex.backend.beans.IdentityKeyRow;
import ooo.klae.connex.backend.beans.PersonIdentityBackfillCandidate;
import ooo.klae.connex.backend.mappers.IdentityCollisionMapper;
import ooo.klae.connex.backend.mappers.IdentityMapper;

/**
 * Bounded transactions for canonical identity backfill pages and collision rebuilds.
 *
 * <p>Backfill provenance records the reconciliation time as {@code acquired_at}: pre-spine parent
 * rows do not retain a trustworthy field-level acquisition timestamp, so their creation time must
 * not be presented as one.
 */
@Service
@RequiredArgsConstructor
public class IdentityBackfillTransaction {

    private static final int MAX_PAGE_SIZE = 500;

    private final IdentityMapper identityMapper;
    private final IdentityCollisionMapper identityCollisionMapper;
    private final MatchingService matchingService;
    private final Clock clock;

    /**
     * Backfills one keyset page of eligible person email and phone values.
     * @param catalog pinned catalog used for failure context
     * @param workspaceId workspace being backfilled
     * @param afterPersonId exclusive person cursor
     * @param limit bounded page size
     * @return page cursor and outcome counters
     */
    @Transactional
    public IdentityBackfillBatch backfillPersonPage(
            String catalog, int workspaceId, int afterPersonId, int limit) {
        requirePage(catalog, workspaceId, afterPersonId, limit);
        List<PersonIdentityBackfillCandidate> candidates =
            identityMapper.findPersonBackfillCandidates(workspaceId, afterPersonId, limit);
        if (candidates.isEmpty()) {
            return IdentityBackfillBatch.empty(afterPersonId);
        }
        List<PersonIdentityBackfillCandidate> lockedCandidates = candidates.stream()
            .map(candidate ->
                identityMapper.lockPersonIdentityParent(workspaceId, candidate.getId()))
            .filter(Objects::nonNull)
            .toList();
        List<Integer> recordIds = lockedCandidates.stream()
            .map(candidate -> Objects.requireNonNull(candidate, "person candidate").getId())
            .toList();
        Set<IdentityKey> existing = recordIds.isEmpty()
            ? new HashSet<>()
            : identityKeys(identityMapper.findPersonIdentityKeys(workspaceId, recordIds));
        int created = 0;
        int alreadyPresent = 0;
        int invalidEmail = 0;
        int invalidPhone = 0;
        int skippedWrites = candidates.size() - lockedCandidates.size();
        LocalDateTime reconciledAt = utcNow();
        for (PersonIdentityBackfillCandidate candidate : lockedCandidates) {
            identityMapper.updatePersonNormalizedName(
                workspaceId,
                candidate.getId(),
                candidate.getName(),
                matchingService.normalizeName(candidate.getName()).orElse(null));
            String email = candidate.getEmail();
            Optional<String> normalizedEmail =
                matchingService.normalizeIdentifier(IdentityKind.EMAIL, email);
            String normalizedEmailValue = normalizedEmail.orElse(null);
            identityMapper.supersedePersonEmailIdentities(
                workspaceId, candidate.getId(), email, normalizedEmailValue, reconciledAt);
            retainCurrentKey(
                existing, candidate.getId(), IdentityKind.EMAIL, normalizedEmailValue);
            if (email != null && !email.isBlank()) {
                Optional<String> normalized = normalizedEmail;
                if (normalized.isEmpty()) {
                    invalidEmail++;
                } else {
                    IdentityKey key = new IdentityKey(
                        candidate.getId(), IdentityKind.EMAIL.getDatabaseValue(), normalized.orElseThrow());
                    if (existing.contains(key)) {
                        alreadyPresent++;
                    } else {
                        int written = identityMapper.upsertPersonEmailIdentity(
                            workspaceId, candidate.getId(), email, normalized.orElseThrow(),
                            IdentityAcquisitionSource.BACKFILL.getDatabaseValue(),
                            "person:" + candidate.getId(), reconciledAt);
                        if (written == 1) {
                            existing.add(key);
                            created++;
                        } else if (written == 2) {
                            existing.add(key);
                            alreadyPresent++;
                        } else {
                            skippedWrites++;
                        }
                    }
                }
            }
            String phone = candidate.getPhone();
            Optional<String> normalizedPhone =
                matchingService.normalizeIdentifier(IdentityKind.PHONE, phone);
            String normalizedPhoneValue = normalizedPhone.orElse(null);
            identityMapper.supersedePersonPhoneIdentities(
                workspaceId, candidate.getId(), phone, normalizedPhoneValue, reconciledAt);
            retainCurrentKey(
                existing, candidate.getId(), IdentityKind.PHONE, normalizedPhoneValue);
            if (phone != null && !phone.isBlank()) {
                Optional<String> normalized = normalizedPhone;
                if (normalized.isEmpty()) {
                    invalidPhone++;
                } else {
                    IdentityKey key = new IdentityKey(
                        candidate.getId(), IdentityKind.PHONE.getDatabaseValue(), normalized.orElseThrow());
                    if (existing.contains(key)) {
                        alreadyPresent++;
                    } else {
                        int written = identityMapper.upsertPersonPhoneIdentity(
                            workspaceId, candidate.getId(), phone, normalized.orElseThrow(),
                            IdentityAcquisitionSource.BACKFILL.getDatabaseValue(),
                            "person:" + candidate.getId(), reconciledAt);
                        if (written == 1) {
                            existing.add(key);
                            created++;
                        } else if (written == 2) {
                            existing.add(key);
                            alreadyPresent++;
                        } else {
                            skippedWrites++;
                        }
                    }
                }
            }
        }
        return new IdentityBackfillBatch(
            candidates.getLast().getId(),
            candidates.size(),
            created,
            alreadyPresent,
            invalidEmail,
            invalidPhone,
            0,
            skippedWrites);
    }

    /**
     * Backfills one keyset page of eligible company website-domain and phone values.
     * @param catalog pinned catalog used for failure context
     * @param workspaceId workspace being backfilled
     * @param afterCompanyId exclusive company cursor
     * @param limit bounded page size
     * @return page cursor and outcome counters
     */
    @Transactional
    public IdentityBackfillBatch backfillCompanyPage(
            String catalog, int workspaceId, int afterCompanyId, int limit) {
        requirePage(catalog, workspaceId, afterCompanyId, limit);
        List<CompanyIdentityBackfillCandidate> candidates =
            identityMapper.findCompanyBackfillCandidates(workspaceId, afterCompanyId, limit);
        if (candidates.isEmpty()) {
            return IdentityBackfillBatch.empty(afterCompanyId);
        }
        List<CompanyIdentityBackfillCandidate> lockedCandidates = candidates.stream()
            .map(candidate ->
                identityMapper.lockCompanyIdentityParent(workspaceId, candidate.getId()))
            .filter(Objects::nonNull)
            .toList();
        List<Integer> recordIds = lockedCandidates.stream()
            .map(candidate -> Objects.requireNonNull(candidate, "company candidate").getId())
            .toList();
        Set<IdentityKey> existing = recordIds.isEmpty()
            ? new HashSet<>()
            : identityKeys(identityMapper.findCompanyIdentityKeys(workspaceId, recordIds));
        int created = 0;
        int alreadyPresent = 0;
        int invalidDomain = 0;
        int invalidPhone = 0;
        int skippedWrites = candidates.size() - lockedCandidates.size();
        LocalDateTime reconciledAt = utcNow();
        for (CompanyIdentityBackfillCandidate candidate : lockedCandidates) {
            identityMapper.updateCompanyNormalizedName(
                workspaceId,
                candidate.getId(),
                candidate.getName(),
                matchingService.normalizeName(candidate.getName()).orElse(null));
            String website = candidate.getWebsite();
            Optional<String> normalizedDomain =
                matchingService.normalizeIdentifier(IdentityKind.DOMAIN, website);
            String normalizedDomainValue = normalizedDomain.orElse(null);
            identityMapper.supersedeCompanyDomainIdentities(
                workspaceId, candidate.getId(), website, normalizedDomainValue, reconciledAt);
            retainCurrentKey(
                existing, candidate.getId(), IdentityKind.DOMAIN, normalizedDomainValue);
            if (website != null && !website.isBlank()) {
                if (normalizedDomain.isEmpty()) {
                    invalidDomain++;
                } else {
                    IdentityKey key = new IdentityKey(
                        candidate.getId(), IdentityKind.DOMAIN.getDatabaseValue(),
                        normalizedDomain.orElseThrow());
                    if (existing.contains(key)) {
                        alreadyPresent++;
                    } else {
                        int written = identityMapper.upsertCompanyDomainIdentity(
                            workspaceId, candidate.getId(), website, normalizedDomain.orElseThrow(),
                            IdentityAcquisitionSource.BACKFILL.getDatabaseValue(),
                            "company:" + candidate.getId(), reconciledAt);
                        if (written == 1) {
                            existing.add(key);
                            created++;
                        } else if (written == 2) {
                            existing.add(key);
                            alreadyPresent++;
                        } else {
                            skippedWrites++;
                        }
                    }
                }
            }
            String phone = candidate.getPhone();
            Optional<String> normalizedPhone =
                matchingService.normalizeIdentifier(IdentityKind.PHONE, phone);
            String normalizedPhoneValue = normalizedPhone.orElse(null);
            identityMapper.supersedeCompanyPhoneIdentities(
                workspaceId, candidate.getId(), phone, normalizedPhoneValue, reconciledAt);
            retainCurrentKey(
                existing, candidate.getId(), IdentityKind.PHONE, normalizedPhoneValue);
            if (phone != null && !phone.isBlank()) {
                if (normalizedPhone.isEmpty()) {
                    invalidPhone++;
                } else {
                    IdentityKey key = new IdentityKey(
                        candidate.getId(), IdentityKind.PHONE.getDatabaseValue(),
                        normalizedPhone.orElseThrow());
                    if (existing.contains(key)) {
                        alreadyPresent++;
                    } else {
                        int written = identityMapper.upsertCompanyPhoneIdentity(
                            workspaceId, candidate.getId(), phone, normalizedPhone.orElseThrow(),
                            IdentityAcquisitionSource.BACKFILL.getDatabaseValue(),
                            "company:" + candidate.getId(), reconciledAt);
                        if (written == 1) {
                            existing.add(key);
                            created++;
                        } else if (written == 2) {
                            existing.add(key);
                            alreadyPresent++;
                        } else {
                            skippedWrites++;
                        }
                    }
                }
            }
        }
        return new IdentityBackfillBatch(
            candidates.getLast().getId(),
            candidates.size(),
            created,
            alreadyPresent,
            0,
            invalidPhone,
            invalidDomain,
            skippedWrites);
    }

    /**
     * Atomically replaces one workspace's collision membership artifact.
     * @param catalog pinned catalog used for failure context
     * @param workspaceId workspace whose report is rebuilt
     * @return collision membership count held by the workspace after the rebuild
     */
    @Transactional
    public int rebuildCollisionReport(String catalog, int workspaceId) {
        requireWorkspace(catalog, workspaceId);
        LocalDateTime rebuiltAt = utcNow();
        identityCollisionMapper.deleteForWorkspace(workspaceId);
        identityCollisionMapper.insertPersonCollisionMembers(workspaceId, rebuiltAt);
        identityCollisionMapper.insertCompanyCollisionMembers(workspaceId, rebuiltAt);
        return Math.toIntExact(identityCollisionMapper.countForWorkspace(workspaceId));
    }

    private Set<IdentityKey> identityKeys(List<IdentityKeyRow> rows) {
        Set<IdentityKey> keys = new HashSet<>();
        for (IdentityKeyRow row : rows) {
            IdentityKey required = new IdentityKey(
                Objects.requireNonNull(row, "identity key").getRecordId(),
                Objects.requireNonNull(row.getKind(), "identity kind"),
                Objects.requireNonNull(row.getNormalizedValue(), "normalized identity value"));
            if (!keys.add(required)) {
                throw new IllegalStateException("Duplicate canonical identity key returned by persistence");
            }
        }
        return keys;
    }

    private void retainCurrentKey(
            Set<IdentityKey> keys,
            int recordId,
            IdentityKind kind,
            String normalizedValue) {
        keys.removeIf(key ->
            key.recordId() == recordId
                && kind.getDatabaseValue().equals(key.kind())
                && !Objects.equals(normalizedValue, key.normalizedValue()));
    }

    private LocalDateTime utcNow() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).withNano(0);
    }

    private void requirePage(String catalog, int workspaceId, int afterId, int limit) {
        requireWorkspace(catalog, workspaceId);
        if (afterId < 0 || limit < 1 || limit > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("Invalid identity backfill page");
        }
    }

    private void requireWorkspace(String catalog, int workspaceId) {
        if (workspaceId <= 0) {
            throw new IllegalArgumentException(
                "Invalid identity backfill workspace in " + Objects.toString(catalog, "default"));
        }
    }

    private record IdentityKey(int recordId, String kind, String normalizedValue) {
    }

    /**
     * Outcome and next cursor for one bounded identity backfill page.
     * @param lastRecordId final record ID observed by the page
     * @param recordsScanned number of parent rows scanned
     * @param identitiesCreated newly inserted identity rows
     * @param identitiesAlreadyPresent existing identity rows skipped
     * @param invalidEmails invalid email values skipped
     * @param invalidPhones invalid phone values skipped
     * @param invalidDomains invalid domain values skipped
     * @param skippedWrites rows skipped after write-time revalidation
     */
    public record IdentityBackfillBatch(
            int lastRecordId,
            int recordsScanned,
            int identitiesCreated,
            int identitiesAlreadyPresent,
            int invalidEmails,
            int invalidPhones,
            int invalidDomains,
            int skippedWrites) {

        private static IdentityBackfillBatch empty(int afterId) {
            return new IdentityBackfillBatch(afterId, 0, 0, 0, 0, 0, 0, 0);
        }
    }
}
