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
        List<Integer> recordIds = candidates.stream()
            .map(candidate -> Objects.requireNonNull(candidate, "person candidate").getId())
            .toList();
        Set<IdentityKey> existing = identityKeys(
            identityMapper.findPersonIdentityKeys(workspaceId, recordIds));
        int created = 0;
        int alreadyPresent = 0;
        int invalidEmail = 0;
        int invalidPhone = 0;
        int skippedWrites = 0;
        for (PersonIdentityBackfillCandidate candidate : candidates) {
            String email = candidate.getEmail();
            if (email != null && !email.isBlank()) {
                Optional<String> normalized =
                    matchingService.normalizeIdentifier(IdentityKind.EMAIL, email);
                if (normalized.isEmpty()) {
                    invalidEmail++;
                } else {
                    IdentityKey key = new IdentityKey(
                        candidate.getId(), IdentityKind.EMAIL.getDatabaseValue(), normalized.orElseThrow());
                    if (existing.contains(key)) {
                        alreadyPresent++;
                    } else if (identityMapper.insertBackfilledPersonEmailIfAbsent(
                            workspaceId, candidate.getId(), email, normalized.orElseThrow()) == 1) {
                        existing.add(key);
                        created++;
                    } else {
                        existing.add(key);
                        skippedWrites++;
                    }
                }
            }
            String phone = candidate.getPhone();
            if (phone != null && !phone.isBlank()) {
                Optional<String> normalized =
                    matchingService.normalizeIdentifier(IdentityKind.PHONE, phone);
                if (normalized.isEmpty()) {
                    invalidPhone++;
                } else {
                    IdentityKey key = new IdentityKey(
                        candidate.getId(), IdentityKind.PHONE.getDatabaseValue(), normalized.orElseThrow());
                    if (existing.contains(key)) {
                        alreadyPresent++;
                    } else if (identityMapper.insertBackfilledPersonPhoneIfAbsent(
                            workspaceId, candidate.getId(), phone, normalized.orElseThrow()) == 1) {
                        existing.add(key);
                        created++;
                    } else {
                        existing.add(key);
                        skippedWrites++;
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
     * Backfills one keyset page of eligible company website domains.
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
        List<Integer> recordIds = candidates.stream()
            .map(candidate -> Objects.requireNonNull(candidate, "company candidate").getId())
            .toList();
        Set<IdentityKey> existing = identityKeys(
            identityMapper.findCompanyIdentityKeys(workspaceId, recordIds));
        int created = 0;
        int alreadyPresent = 0;
        int invalidDomain = 0;
        int skippedWrites = 0;
        for (CompanyIdentityBackfillCandidate candidate : candidates) {
            String website = candidate.getWebsite();
            if (website == null || website.isBlank()) {
                continue;
            }
            Optional<String> normalized =
                matchingService.normalizeIdentifier(IdentityKind.DOMAIN, website);
            if (normalized.isEmpty()) {
                invalidDomain++;
                continue;
            }
            IdentityKey key = new IdentityKey(
                candidate.getId(), IdentityKind.DOMAIN.getDatabaseValue(), normalized.orElseThrow());
            if (existing.contains(key)) {
                alreadyPresent++;
            } else if (identityMapper.insertBackfilledCompanyDomainIfAbsent(
                    workspaceId, candidate.getId(), website, normalized.orElseThrow()) == 1) {
                existing.add(key);
                created++;
            } else {
                existing.add(key);
                skippedWrites++;
            }
        }
        return new IdentityBackfillBatch(
            candidates.getLast().getId(),
            candidates.size(),
            created,
            alreadyPresent,
            0,
            0,
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
        LocalDateTime rebuiltAt = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).withNano(0);
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
