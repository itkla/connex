package ooo.klae.connex.backend.services;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.dto.CompanyDuplicatePreflightRequest;
import ooo.klae.connex.backend.dto.DuplicateCandidateDto;
import ooo.klae.connex.backend.dto.DuplicateCandidateRow;
import ooo.klae.connex.backend.dto.DuplicateIdentityKey;
import ooo.klae.connex.backend.dto.DuplicateMatchEvidenceDto;
import ooo.klae.connex.backend.dto.DuplicateMatchKind;
import ooo.klae.connex.backend.dto.DuplicateMatchStrength;
import ooo.klae.connex.backend.dto.DuplicateNameKey;
import ooo.klae.connex.backend.dto.DuplicatePreflightResponse;
import ooo.klae.connex.backend.dto.PersonDuplicatePreflightRequest;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.mappers.IdentityMapper;
import ooo.klae.connex.backend.tenant.Permission;
import ooo.klae.connex.backend.tenant.RequirePermission;

/**
 * Ranked, visibility-safe duplicate checks over current canonical identities and exact names.
 *
 * <p>Email, phone, domain, and external-id evidence is {@link DuplicateMatchStrength#STRONG}.
 * Name evidence is {@link DuplicateMatchStrength#WEAK} and is accepted only when
 * {@link MatchingService#normalizeName(String)} is exactly equal. No fuzzy or edit-distance
 * matching is performed. Every persistence query applies owned-or-shared visibility before
 * ranking or limits, so invisible records cannot influence the response.
 */
@Service
@RequiredArgsConstructor
public class DuplicatePreflightService {

    private static final int MAX_IDENTITY_VALUES = 16;
    private static final int PUBLIC_CANDIDATE_LIMIT = 50;
    private static final int IMPORT_CANDIDATE_LIMIT = 8;
    private static final int IMPORT_REQUEST_LIMIT = 5_000;
    private static final int IDENTITY_KEY_CHUNK_SIZE = 200;
    private static final int NAME_KEY_CHUNK_SIZE = 100;
    private static final int LOOKUPS_PER_WORK_UNIT = 250;

    private final IdentityMapper identityMapper;
    private final MatchingService matchingService;
    private final WorkspaceService workspaceService;
    private final DuplicatePreflightRateLimiter rateLimiter;

    /**
     * Checks one proposed person using {@code PERSON_CREATE}.
     *
     * @param request bounded candidate values
     * @return ranked visible candidates
     */
    @Transactional(readOnly = true)
    @RequirePermission(Permission.PERSON_CREATE)
    public DuplicatePreflightResponse preflightPerson(PersonDuplicatePreflightRequest request) {
        return matchPersons(List.of(Objects.requireNonNull(request, "request")), PUBLIC_CANDIDATE_LIMIT)
            .getFirst();
    }

    /**
     * Checks one proposed company using {@code COMPANY_CREATE}.
     *
     * @param request bounded candidate values
     * @return ranked visible candidates
     */
    @Transactional(readOnly = true)
    @RequirePermission(Permission.COMPANY_CREATE)
    public DuplicatePreflightResponse preflightCompany(CompanyDuplicatePreflightRequest request) {
        return matchCompanies(List.of(Objects.requireNonNull(request, "request")), PUBLIC_CANDIDATE_LIMIT)
            .getFirst();
    }

    /**
     * Applies the person preflight matcher to a bounded CSV batch.
     *
     * @param requests proposed rows in source order
     * @return one bounded result per row
     */
    @Transactional(readOnly = true)
    @RequirePermission(Permission.PERSON_CREATE)
    public List<DuplicatePreflightResponse> preflightPersonImport(
            List<PersonDuplicatePreflightRequest> requests) {
        return matchPersons(boundedImportRequests(requests), IMPORT_CANDIDATE_LIMIT);
    }

    /**
     * Applies the company preflight matcher to a bounded CSV batch.
     *
     * @param requests proposed rows in source order
     * @return one bounded result per row
     */
    @Transactional(readOnly = true)
    @RequirePermission(Permission.COMPANY_CREATE)
    public List<DuplicatePreflightResponse> preflightCompanyImport(
            List<CompanyDuplicatePreflightRequest> requests) {
        return matchCompanies(boundedImportRequests(requests), IMPORT_CANDIDATE_LIMIT);
    }

    private List<DuplicatePreflightResponse> matchPersons(
            List<PersonDuplicatePreflightRequest> requests,
            int candidateLimit) {
        List<NormalizedRequest> normalized = requests.stream()
            .map(this::normalizePerson)
            .toList();
        rateLimiter.requireAllowed(workUnits(normalized));
        return match(
            "person",
            normalized,
            candidateLimit,
            identityMapper::findVisiblePersonIdentityMatches,
            identityMapper::findVisiblePersonNameMatches);
    }

    private List<DuplicatePreflightResponse> matchCompanies(
            List<CompanyDuplicatePreflightRequest> requests,
            int candidateLimit) {
        List<NormalizedRequest> normalized = requests.stream()
            .map(this::normalizeCompany)
            .toList();
        rateLimiter.requireAllowed(workUnits(normalized));
        return match(
            "company",
            normalized,
            candidateLimit,
            identityMapper::findVisibleCompanyIdentityMatches,
            identityMapper::findVisibleCompanyNameMatches);
    }

    private List<DuplicatePreflightResponse> match(
            String recordType,
            List<NormalizedRequest> requests,
            int candidateLimit,
            IdentityQuery identityQuery,
            NameQuery nameQuery) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        List<DuplicateIdentityKey> identityKeys = requests.stream()
            .flatMap(request -> request.identityKeys().stream())
            .distinct()
            .toList();
        List<DuplicateNameKey> nameKeys = requests.stream()
            .map(NormalizedRequest::normalizedName)
            .flatMap(Optional::stream)
            .distinct()
            .map(DuplicateNameKey::new)
            .toList();
        int perKeyLimit = candidateLimit + 1;
        Map<DuplicateIdentityKey, List<DuplicateCandidateRow>> identityRows =
            identityRows(workspaceId, identityKeys, perKeyLimit, identityQuery);
        Map<String, List<DuplicateCandidateRow>> nameRows =
            nameRows(workspaceId, nameKeys, perKeyLimit, nameQuery);
        List<DuplicatePreflightResponse> responses = new ArrayList<>(requests.size());
        for (NormalizedRequest request : requests) {
            responses.add(response(
                workspaceId, recordType, request, candidateLimit, perKeyLimit,
                identityRows, nameRows));
        }
        return List.copyOf(responses);
    }

    private DuplicatePreflightResponse response(
            int workspaceId,
            String recordType,
            NormalizedRequest request,
            int candidateLimit,
            int perKeyLimit,
            Map<DuplicateIdentityKey, List<DuplicateCandidateRow>> identityRows,
            Map<String, List<DuplicateCandidateRow>> nameRows) {
        Map<Integer, CandidateBuilder> builders = new LinkedHashMap<>();
        boolean truncated = false;
        for (DuplicateIdentityKey key : request.identityKeys()) {
            List<DuplicateCandidateRow> rows = identityRows.getOrDefault(key, List.of());
            if (rows.size() >= perKeyLimit) {
                truncated = true;
            }
            for (DuplicateCandidateRow row : rows) {
                candidate(builders, row, workspaceId, recordType)
                    .addEvidence(evidence(key));
            }
        }
        if (request.normalizedName().isPresent()) {
            String normalizedName = request.normalizedName().orElseThrow();
            int exactNameMatches = 0;
            for (DuplicateCandidateRow row : nameRows.getOrDefault(normalizedName, List.of())) {
                if (!normalizedName.equals(
                        matchingService.normalizeName(row.getName()).orElse(null))) {
                    continue;
                }
                exactNameMatches++;
                candidate(builders, row, workspaceId, recordType)
                    .addEvidence(new DuplicateMatchEvidenceDto(
                        DuplicateMatchKind.NAME,
                        normalizedName,
                        DuplicateMatchStrength.WEAK));
            }
            if (exactNameMatches >= perKeyLimit) {
                truncated = true;
            }
        }
        List<RankedCandidate> ranked = builders.values().stream()
            .map(CandidateBuilder::build)
            .sorted(ranking())
            .toList();
        if (ranked.size() > candidateLimit) {
            truncated = true;
        }
        List<DuplicateCandidateDto> candidates = ranked.stream()
            .limit(candidateLimit)
            .map(RankedCandidate::candidate)
            .toList();
        return new DuplicatePreflightResponse(recordType, candidates, truncated);
    }

    private Map<DuplicateIdentityKey, List<DuplicateCandidateRow>> identityRows(
            int workspaceId,
            List<DuplicateIdentityKey> keys,
            int perKeyLimit,
            IdentityQuery query) {
        Map<DuplicateIdentityKey, List<DuplicateCandidateRow>> rows = new HashMap<>();
        for (int offset = 0; offset < keys.size(); offset += IDENTITY_KEY_CHUNK_SIZE) {
            List<DuplicateIdentityKey> chunk =
                keys.subList(offset, Math.min(offset + IDENTITY_KEY_CHUNK_SIZE, keys.size()));
            for (DuplicateCandidateRow row : query.apply(workspaceId, chunk, perKeyLimit)) {
                DuplicateIdentityKey key = new DuplicateIdentityKey(
                    Objects.requireNonNull(row.getKind(), "match kind"),
                    Objects.requireNonNull(row.getNormalizedValue(), "match value"));
                rows.computeIfAbsent(key, ignored -> new ArrayList<>()).add(row);
            }
        }
        return rows;
    }

    private Map<String, List<DuplicateCandidateRow>> nameRows(
            int workspaceId,
            List<DuplicateNameKey> keys,
            int perKeyLimit,
            NameQuery query) {
        Map<String, List<DuplicateCandidateRow>> rows = new HashMap<>();
        for (int offset = 0; offset < keys.size(); offset += NAME_KEY_CHUNK_SIZE) {
            List<DuplicateNameKey> chunk =
                keys.subList(offset, Math.min(offset + NAME_KEY_CHUNK_SIZE, keys.size()));
            for (DuplicateCandidateRow row : query.apply(workspaceId, chunk, perKeyLimit)) {
                String normalizedName =
                    Objects.requireNonNull(row.getNormalizedValue(), "normalized name");
                rows.computeIfAbsent(normalizedName, ignored -> new ArrayList<>()).add(row);
            }
        }
        return rows;
    }

    private NormalizedRequest normalizePerson(PersonDuplicatePreflightRequest request) {
        Objects.requireNonNull(request, "person request");
        requireIdentityBound(request.emails(), request.phones(), request.externalIds());
        Set<DuplicateIdentityKey> keys = new LinkedHashSet<>();
        addKeys(keys, IdentityKind.EMAIL, request.emails());
        addKeys(keys, IdentityKind.PHONE, request.phones());
        addKeys(keys, IdentityKind.EXTERNAL_ID, request.externalIds());
        return normalizedRequest(keys, request.name());
    }

    private NormalizedRequest normalizeCompany(CompanyDuplicatePreflightRequest request) {
        Objects.requireNonNull(request, "company request");
        requireIdentityBound(request.websites(), request.phones(), request.externalIds());
        Set<DuplicateIdentityKey> keys = new LinkedHashSet<>();
        addKeys(keys, IdentityKind.DOMAIN, request.websites());
        addKeys(keys, IdentityKind.PHONE, request.phones());
        addKeys(keys, IdentityKind.EXTERNAL_ID, request.externalIds());
        return normalizedRequest(keys, request.name());
    }

    @SafeVarargs
    private final void requireIdentityBound(List<String>... values) {
        int count = Arrays.stream(values)
            .filter(Objects::nonNull)
            .mapToInt(List::size)
            .sum();
        if (count > MAX_IDENTITY_VALUES) {
            throw new BadRequestException(
                "At most " + MAX_IDENTITY_VALUES + " identity values may be checked");
        }
    }

    private void addKeys(
            Set<DuplicateIdentityKey> keys,
            IdentityKind kind,
            List<String> rawValues) {
        if (rawValues == null) {
            return;
        }
        for (String rawValue : rawValues) {
            matchingService.normalizeIdentifier(kind, rawValue)
                .ifPresent(normalized ->
                    keys.add(new DuplicateIdentityKey(kind.getDatabaseValue(), normalized)));
        }
    }

    private NormalizedRequest normalizedRequest(
            Set<DuplicateIdentityKey> keys,
            String rawName) {
        Optional<String> normalizedName = matchingService.normalizeName(rawName);
        if (keys.isEmpty() && normalizedName.isEmpty()) {
            throw new BadRequestException("At least one valid identity or name is required");
        }
        return new NormalizedRequest(List.copyOf(keys), normalizedName);
    }

    private static <T> List<T> boundedImportRequests(List<T> requests) {
        Objects.requireNonNull(requests, "import requests");
        if (requests.size() > IMPORT_REQUEST_LIMIT) {
            throw new BadRequestException(
                "At most " + IMPORT_REQUEST_LIMIT + " import rows may be checked");
        }
        return List.copyOf(requests);
    }

    private static int workUnits(List<NormalizedRequest> requests) {
        int lookups = requests.stream()
            .mapToInt(request ->
                Math.max(
                    1,
                    request.identityKeys().size() + (request.normalizedName().isPresent() ? 1 : 0)))
            .sum();
        return Math.max(1, Math.ceilDiv(lookups, LOOKUPS_PER_WORK_UNIT));
    }

    private CandidateBuilder candidate(
            Map<Integer, CandidateBuilder> builders,
            DuplicateCandidateRow row,
            int workspaceId,
            String recordType) {
        return builders.computeIfAbsent(
            row.getRecordId(),
            ignored -> new CandidateBuilder(
                row, recordType, row.getRecordWorkspaceId() == workspaceId));
    }

    private static DuplicateMatchEvidenceDto evidence(DuplicateIdentityKey key) {
        return new DuplicateMatchEvidenceDto(
            matchKind(key.kind()),
            key.normalizedValue(),
            DuplicateMatchStrength.STRONG);
    }

    private static DuplicateMatchKind matchKind(String kind) {
        return switch (kind) {
            case "email" -> DuplicateMatchKind.EMAIL;
            case "phone" -> DuplicateMatchKind.PHONE;
            case "domain" -> DuplicateMatchKind.DOMAIN;
            case "external_id" -> DuplicateMatchKind.EXTERNAL_ID;
            case "name" -> DuplicateMatchKind.NAME;
            default -> throw new IllegalStateException("Unsupported duplicate match kind");
        };
    }

    private Comparator<RankedCandidate> ranking() {
        return Comparator
            .comparing((RankedCandidate candidate) ->
                candidate.candidate().strength() == DuplicateMatchStrength.STRONG ? 0 : 1)
            .thenComparing(
                RankedCandidate::strongEvidenceCount,
                Comparator.reverseOrder())
            .thenComparing(
                RankedCandidate::evidenceCount,
                Comparator.reverseOrder())
            .thenComparing(candidate ->
                candidate.candidate().ownedByActiveWorkspace() ? 0 : 1)
            .thenComparing(RankedCandidate::normalizedName)
            .thenComparing(candidate -> candidate.candidate().recordId());
    }

    private String normalizedName(String name) {
        return matchingService.normalizeName(name).orElse("");
    }

    private record NormalizedRequest(
            List<DuplicateIdentityKey> identityKeys,
            Optional<String> normalizedName) {
    }

    private record RankedCandidate(
            DuplicateCandidateDto candidate,
            int strongEvidenceCount,
            int evidenceCount,
            String normalizedName) {
    }

    private final class CandidateBuilder {
        private final DuplicateCandidateRow row;
        private final String recordType;
        private final boolean owned;
        private final Map<DuplicateMatchKind, Map<String, DuplicateMatchEvidenceDto>> evidence =
            new EnumMap<>(DuplicateMatchKind.class);

        private CandidateBuilder(
                DuplicateCandidateRow row,
                String recordType,
                boolean owned) {
            this.row = Objects.requireNonNull(row, "candidate row");
            this.recordType = recordType;
            this.owned = owned;
        }

        private void addEvidence(DuplicateMatchEvidenceDto match) {
            evidence.computeIfAbsent(match.kind(), ignored -> new LinkedHashMap<>())
                .putIfAbsent(match.normalizedValue(), match);
        }

        private RankedCandidate build() {
            List<DuplicateMatchEvidenceDto> matches = evidence.values().stream()
                .flatMap(matchesByValue -> matchesByValue.values().stream())
                .sorted(Comparator
                    .comparing((DuplicateMatchEvidenceDto match) ->
                        match.strength() == DuplicateMatchStrength.STRONG ? 0 : 1)
                    .thenComparing(DuplicateMatchEvidenceDto::kind)
                    .thenComparing(DuplicateMatchEvidenceDto::normalizedValue))
                .toList();
            int strongEvidence = (int) matches.stream()
                .filter(match -> match.strength() == DuplicateMatchStrength.STRONG)
                .count();
            DuplicateMatchStrength strength = strongEvidence > 0
                ? DuplicateMatchStrength.STRONG
                : DuplicateMatchStrength.WEAK;
            DuplicateCandidateDto candidate = new DuplicateCandidateDto(
                row.getRecordId(),
                recordType,
                Objects.requireNonNull(row.getName(), "candidate name"),
                row.getCompanyName(),
                row.getTitle(),
                row.getWebsite(),
                row.getIndustry(),
                owned,
                strength,
                matches);
            return new RankedCandidate(
                candidate,
                strongEvidence,
                matches.size(),
                DuplicatePreflightService.this.normalizedName(candidate.name()));
        }
    }

    @FunctionalInterface
    private interface IdentityQuery {
        List<DuplicateCandidateRow> apply(
            int workspaceId,
            List<DuplicateIdentityKey> keys,
            int perKeyLimit);
    }

    @FunctionalInterface
    private interface NameQuery {
        List<DuplicateCandidateRow> apply(
            int workspaceId,
            List<DuplicateNameKey> keys,
            int perKeyLimit);
    }
}
