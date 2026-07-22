package ooo.klae.connex.backend.services;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import ooo.klae.connex.backend.ai.businesscard.BusinessCardAiExtractionService;
import ooo.klae.connex.backend.beans.Attachment;
import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.businesscard.BusinessCardBinaryStore;
import ooo.klae.connex.backend.businesscard.BusinessCardExtractor;
import ooo.klae.connex.backend.businesscard.BusinessCardImageValidator;
import ooo.klae.connex.backend.businesscard.BusinessCardIdempotencyKey;
import ooo.klae.connex.backend.businesscard.BusinessCardImportRecord;
import ooo.klae.connex.backend.businesscard.BusinessCardOcrClient;
import ooo.klae.connex.backend.businesscard.BusinessCardProperties;
import ooo.klae.connex.backend.businesscard.BusinessCardRateLimiter;
import ooo.klae.connex.backend.businesscard.BusinessCardTextNormalizer;
import ooo.klae.connex.backend.businesscard.OcrLine;
import ooo.klae.connex.backend.businesscard.ValidatedBusinessCardImage;
import ooo.klae.connex.backend.capability.Capability;
import ooo.klae.connex.backend.capability.CapabilityEntitlement;
import ooo.klae.connex.backend.dto.AttachmentDto;
import ooo.klae.connex.backend.dto.BusinessCardAvailabilityResponse;
import ooo.klae.connex.backend.dto.BusinessCardCompanyAction;
import ooo.klae.connex.backend.dto.BusinessCardContactRequest;
import ooo.klae.connex.backend.dto.BusinessCardImportResponse;
import ooo.klae.connex.backend.dto.BusinessCardImportReservationResponse;
import ooo.klae.connex.backend.dto.BusinessCardScanResponse;
import ooo.klae.connex.backend.dto.BusinessCardScanResponse.CompanyCandidate;
import ooo.klae.connex.backend.dto.CompanyDto;
import ooo.klae.connex.backend.dto.PersonDto;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.BusinessCardImportResultGoneException;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.exceptions.ForbiddenException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.exceptions.ServiceUnavailableException;
import ooo.klae.connex.backend.exceptions.TooManyRequestsException;
import ooo.klae.connex.backend.mappers.BusinessCardImportRequestMapper;
import ooo.klae.connex.backend.services.CompanyService.NormalizedCompanyMatches;
import ooo.klae.connex.backend.tenant.Permission;
import ooo.klae.connex.backend.tenant.RequirePermission;

/**
 * Orchestrates review-first business-card scanning and confirmed contact imports.
 */
@Service
public class BusinessCardService {
    private final BusinessCardProperties properties;
    private final BusinessCardImageValidator imageValidator;
    private final BusinessCardOcrClient ocrClient;
    private final BusinessCardExtractor extractor;
    private final BusinessCardAiExtractionService aiExtractionService;
    private final BusinessCardBinaryStore binaryStore;
    private final CompanyService companyService;
    private final PersonService personService;
    private final AttachmentService attachmentService;
    private final WorkspaceService workspaceService;
    private final AuthService authService;
    private final BusinessCardImportRequestMapper importRequestMapper;
    private final BusinessCardRateLimiter rateLimiter;
    private final CapabilityEntitlement capabilityEntitlement;
    private final Clock clock;

    public BusinessCardService(
            BusinessCardProperties properties,
            BusinessCardImageValidator imageValidator,
            BusinessCardOcrClient ocrClient,
            BusinessCardExtractor extractor,
            BusinessCardAiExtractionService aiExtractionService,
            BusinessCardBinaryStore binaryStore,
            CompanyService companyService,
            PersonService personService,
            AttachmentService attachmentService,
            WorkspaceService workspaceService,
            AuthService authService,
            BusinessCardImportRequestMapper importRequestMapper,
            BusinessCardRateLimiter rateLimiter,
            CapabilityEntitlement capabilityEntitlement,
            Clock clock) {
        this.properties = properties;
        this.imageValidator = imageValidator;
        this.ocrClient = ocrClient;
        this.extractor = extractor;
        this.aiExtractionService = aiExtractionService;
        this.binaryStore = binaryStore;
        this.companyService = companyService;
        this.personService = personService;
        this.attachmentService = attachmentService;
        this.workspaceService = workspaceService;
        this.authService = authService;
        this.importRequestMapper = importRequestMapper;
        this.rateLimiter = rateLimiter;
        this.capabilityEntitlement = capabilityEntitlement;
        this.clock = clock;
    }

    /**
     * Returns whether scanning and confirmed card retention are both ready for this instance.
     *
     * @return capability readiness
     */
    public boolean isAvailable() {
        return binaryStore.isReady() && isLocalScannerReady();
    }

    /**
     * Returns whether reviewed manual imports can retain their source image without OCR.
     *
     * @return manual import capability readiness
     */
    public boolean isImportAvailable() {
        return binaryStore.isReady();
    }

    /**
     * Returns business-card readiness for the authorized active workspace.
     *
     * @return workspace-scoped scan and import readiness
     */
    @RequirePermission(Permission.PERSON_CREATE)
    public BusinessCardAvailabilityResponse availability() {
        workspaceService.requirePermission(Permission.ATTACHMENT_CREATE);
        boolean importing = capabilityEntitlement.isEntitled(Capability.BUSINESS_CARD_IMPORT)
                && binaryStore.isReady();
        boolean scanning = capabilityEntitlement.isEntitled(Capability.BUSINESS_CARD_SCANNING)
                && binaryStore.isReady()
                && (isLocalScannerReady() || aiExtractionService.isAvailable());
        return new BusinessCardAvailabilityResponse(scanning, importing);
    }

    /**
     * Extracts an editable draft from one validated image without persisting the image or OCR text.
     *
     * @param image uploaded card image
     * @return contact and company candidates
     */
    @RequirePermission(Permission.PERSON_CREATE)
    public BusinessCardScanResponse scan(MultipartFile image) {
        requireEntitled(Capability.BUSINESS_CARD_SCANNING);
        workspaceService.requirePermission(Permission.ATTACHMENT_CREATE);
        rateLimiter.requireScanAllowed();
        requireBinaryStorageReady();
        boolean localScannerReady = isLocalScannerReady();
        boolean readinessResolvedBeforeValidation = false;
        if (!localScannerReady && !aiExtractionService.isAvailable()) {
            localScannerReady = isLocalScannerReadyForScan();
            readinessResolvedBeforeValidation = true;
            if (!localScannerReady) {
                throw scanningUnavailable();
            }
        }
        ValidatedBusinessCardImage validated = imageValidator.validate(image);
        if (!readinessResolvedBeforeValidation) {
            localScannerReady = isLocalScannerReadyForScan();
        }
        if (localScannerReady) {
            List<OcrLine> lines;
            try {
                lines = ocrClient.recognize(validated);
            } catch (ServiceUnavailableException exception) {
                return withCompanyMatch(aiDraft(validated));
            }
            return withCompanyMatch(extractor.extract(lines));
        }
        return withCompanyMatch(aiDraft(validated));
    }

    /**
     * Creates the reviewed contact, applies the explicit company action, and retains a sanitized
     * card copy as a private contact attachment in one transaction.
     *
     * @param image original card image
     * @param contact reviewed contact values
     * @param companyAction explicit existing, create, or none decision
     * @param idempotencyKey caller-generated UUID retained across retries
     * @return created records
     */
    @Transactional
    @RequirePermission(Permission.PERSON_CREATE)
    public BusinessCardImportResponse importCard(
            MultipartFile image,
            BusinessCardContactRequest contact,
            BusinessCardCompanyAction companyAction,
            String idempotencyKey) {
        Objects.requireNonNull(contact, "contact");
        Objects.requireNonNull(companyAction, "companyAction");
        requireEntitled(Capability.BUSINESS_CARD_IMPORT);
        String requestId = BusinessCardIdempotencyKey.canonicalize(idempotencyKey);
        rateLimiter.requireImportAllowed();
        workspaceService.requirePermission(Permission.ATTACHMENT_CREATE);
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        int userId = currentUserId();
        BusinessCardImportRecord snapshot = importRequestMapper.get(workspaceId, requestId);
        if (snapshot == null) {
            throw new ConflictException("Business-card import reservation was not found");
        }
        requireOwnedByCurrentUser(snapshot, userId);
        requireCurrentImportState(snapshot, userId, utc(clock.instant()));
        ValidatedBusinessCardImage validated = imageValidator.validate(image);
        ReviewedImport reviewed = normalizeRequest(contact, companyAction);
        byte[] content = validated.content();
        byte[] requestFingerprint = fingerprint(content, reviewed);
        Instant currentInstant = clock.instant();
        LocalDateTime now = utc(currentInstant);
        LocalDateTime expiresAt = utc(currentInstant.plus(properties.getIdempotencyRetention()));
        BusinessCardImportRecord existing = importRequestMapper.getForUpdate(workspaceId, requestId);
        if (existing == null) {
            throw new BusinessCardImportResultGoneException(
                    "Business-card import reservation is no longer available");
        } else if (existing.requestFingerprint() == null) {
            requireOwnedByCurrentUser(existing, userId);
            int bound = importRequestMapper.bindReservation(
                    workspaceId, userId, requestId, requestFingerprint, expiresAt, now);
            if (bound != 1) {
                throw new BusinessCardImportResultGoneException(
                        "Business-card import reservation expired before submission");
            }
        } else {
            requireOwnedByCurrentUser(existing, userId);
            return replay(existing, requestFingerprint);
        }
        requireBinaryStorageReady();
        String fileName = "business-card." + validated.extension();
        BusinessCardBinaryStore.StoredBusinessCard stored = binaryStore.store(
                workspaceId, fileName, validated.contentType(), content);
        requireStored(stored, content.length);
        Company company = resolveCompany(reviewed);
        Person person = personService.create(toPerson(reviewed, company));
        Attachment attachment = attachment(validated, stored, fileName, person.getId());
        Attachment createdAttachment = attachmentService.createManaged(attachment);
        int completed = importRequestMapper.complete(
                workspaceId,
                requestId,
                person.getId(),
                createdAttachment.getId(),
                company == null ? null : company.getId());
        if (completed != 1) {
            throw new IllegalStateException("Business-card import idempotency result was not recorded");
        }
        return new BusinessCardImportResponse(
                PersonDto.from(person),
                AttachmentDto.from(createdAttachment),
                CompanyDto.from(company));
    }

    /**
     * Persists an opaque tenant-scoped import key before the client submits private multipart data.
     *
     * @param idempotencyKey caller-generated UUID retained across retries
     * @return the server-defined retention boundary
     */
    @Transactional(
        propagation = Propagation.REQUIRES_NEW,
        isolation = Isolation.READ_COMMITTED)
    @RequirePermission(Permission.PERSON_CREATE)
    public BusinessCardImportReservationResponse reserveImport(String idempotencyKey) {
        requireEntitled(Capability.BUSINESS_CARD_IMPORT);
        String requestId = BusinessCardIdempotencyKey.canonicalize(idempotencyKey);
        workspaceService.requirePermission(Permission.ATTACHMENT_CREATE);
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        int userId = currentUserId();
        Instant currentInstant = clock.instant();
        LocalDateTime now = utc(currentInstant);
        importRequestMapper.deleteAbandonedReservations(workspaceId, userId, now);
        BusinessCardImportRecord existing = importRequestMapper.get(workspaceId, requestId);
        if (existing != null) {
            return renewOrResolveReservation(
                existing, workspaceId, userId, requestId, currentInstant, now);
        }
        Instant expiration = currentInstant.plus(properties.getIdempotencyRetention());
        LocalDateTime submissionExpiresAt = utc(
                currentInstant.plus(properties.getReservationLease()));
        for (int slot = 1; slot <= properties.getMaxOutstandingReservations(); slot += 1) {
            if (importRequestMapper.reserve(
                    workspaceId,
                    userId,
                    requestId,
                    slot,
                    submissionExpiresAt,
                    utc(expiration)) == 1) {
                return reservation(utc(expiration));
            }
        }
        BusinessCardImportRecord record = importRequestMapper.get(workspaceId, requestId);
        if (record != null) {
            return renewOrResolveReservation(
                record, workspaceId, userId, requestId, currentInstant, now);
        }
        throw new TooManyRequestsException(
                "Too many pending business-card imports; retry after an earlier request expires");
    }

    /**
     * Returns a completed tenant-scoped import for response-loss reconciliation.
     *
     * @param idempotencyKey caller-generated UUID retained by the client
     * @return the completed import result
     */
    @Transactional(readOnly = true)
    @RequirePermission(Permission.PERSON_CREATE)
    public BusinessCardImportResponse importStatus(String idempotencyKey) {
        requireEntitled(Capability.BUSINESS_CARD_IMPORT);
        String requestId = BusinessCardIdempotencyKey.canonicalize(idempotencyKey);
        workspaceService.requirePermission(Permission.ATTACHMENT_CREATE);
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        int userId = currentUserId();
        BusinessCardImportRecord record = importRequestMapper.get(workspaceId, requestId);
        if (record == null) {
            throw new ResourceNotFoundException("Completed business-card import was not found");
        }
        requireOwnedByCurrentUser(record, userId);
        if (record.requestFingerprint() == null) {
            LocalDateTime submissionExpiresAt = record.submissionExpiresAt();
            if (submissionExpiresAt == null || !submissionExpiresAt.isAfter(utc(clock.instant()))) {
                throw new BusinessCardImportResultGoneException(
                        "Business-card import was not submitted before its reservation expired");
            }
            throw new ConflictException("Business-card import is still in progress");
        }
        if (record.personId() == null || record.attachmentId() == null) {
            throw new ConflictException("Business-card import is still in progress");
        }
        return completedResponse(record);
    }

    private BusinessCardImportResponse replay(
            BusinessCardImportRecord record,
            byte[] requestFingerprint) {
        if (record == null || record.requestFingerprint() == null) {
            throw new IllegalStateException("Business-card import idempotency claim is unavailable");
        }
        if (!MessageDigest.isEqual(record.requestFingerprint(), requestFingerprint)) {
            throw new ConflictException(
                    "Idempotency-Key was already used for a different business-card import");
        }
        if (record.personId() == null || record.attachmentId() == null) {
            throw new IllegalStateException("Business-card import idempotency result is incomplete");
        }
        return completedResponse(record);
    }

    private BusinessCardImportResponse completedResponse(BusinessCardImportRecord record) {
        if (record.personId() == null || record.attachmentId() == null) {
            throw new ResourceNotFoundException("Completed business-card import was not found");
        }
        try {
            Person person = personService.getPersonById(record.personId());
            Attachment attachment = attachmentService.getById(record.attachmentId());
            Company company = record.companyId() == null
                ? null
                : companyService.getCompanyById(record.companyId());
            return new BusinessCardImportResponse(
                PersonDto.from(person),
                AttachmentDto.from(attachment),
                CompanyDto.from(company));
        } catch (ResourceNotFoundException exception) {
            throw new BusinessCardImportResultGoneException(
                    "Business-card import completed, but its result is no longer available");
        }
    }

    private BusinessCardScanResponse withCompanyMatch(BusinessCardScanResponse draft) {
        CompanyCandidate candidate = draft.company();
        if (candidate == null || candidate.value() == null) {
            return draft;
        }
        NormalizedCompanyMatches result = companyService.findVisibleByNormalizedName(candidate.value());
        List<Company> matches = result.companies();
        Integer matchedId = matches.size() == 1 ? matches.getFirst().getId() : null;
        List<String> warnings = new ArrayList<>(draft.warnings());
        if (matches.size() > 1 || result.truncated()) {
            warnings.add("company_match_ambiguous");
            matchedId = null;
        }
        return new BusinessCardScanResponse(
                draft.fields(),
                new CompanyCandidate(candidate.value(), candidate.confidence(), matchedId),
                List.copyOf(warnings));
    }

    private Company resolveCompany(ReviewedImport reviewed) {
        return switch (reviewed.companyAction()) {
            case BusinessCardCompanyAction.Existing existing -> {
                yield companyService.getCompanyById(existing.companyId());
            }
            case BusinessCardCompanyAction.Create create -> {
                Company company = new Company();
                company.setName(create.companyName());
                yield companyService.createCompany(company);
            }
            case BusinessCardCompanyAction.None ignored -> null;
        };
    }

    private static Person toPerson(ReviewedImport reviewed, Company company) {
        Person person = new Person();
        person.setName(reviewed.name());
        person.setEmail(reviewed.email());
        person.setPhone(reviewed.phone());
        person.setTitle(reviewed.title());
        person.setCompany(company);
        return person;
    }

    private Attachment attachment(
            ValidatedBusinessCardImage validated,
            BusinessCardBinaryStore.StoredBusinessCard stored,
            String fileName,
            int personId) {
        Attachment attachment = new Attachment();
        attachment.setEntityType("person");
        attachment.setEntityId(personId);
        attachment.setFileName(fileName);
        attachment.setUrl(stored.url());
        attachment.setContentType(validated.contentType());
        attachment.setSize(stored.size());
        attachment.setUploadedBy(authService.getCurrentUser());
        return attachment;
    }

    private boolean isLocalScannerReady() {
        return properties.isEnabled() && ocrClient.isReady();
    }

    private boolean isLocalScannerReadyForScan() {
        return properties.isEnabled() && ocrClient.isReadyForScan();
    }

    private BusinessCardScanResponse aiDraft(ValidatedBusinessCardImage validated) {
        return aiExtractionService.extract(validated).orElseThrow(BusinessCardService::scanningUnavailable);
    }

    private static ServiceUnavailableException scanningUnavailable() {
        return new ServiceUnavailableException("Business-card scanning is unavailable");
    }

    private void requireBinaryStorageReady() {
        if (!binaryStore.isReady()) {
            throw new ServiceUnavailableException("Business-card importing is unavailable");
        }
    }

    private static void requireStored(BusinessCardBinaryStore.StoredBusinessCard stored, int expectedSize) {
        if (stored == null || stored.url() == null || stored.url().isBlank()
                || stored.size() != expectedSize) {
            throw new ServiceUnavailableException("Business-card storage did not confirm the write");
        }
    }

    private static String requiredText(String value, int maxLength, String field) {
        String normalized = BusinessCardTextNormalizer.text(value);
        if (normalized.isBlank() || normalized.length() > maxLength) {
            throw new BadRequestException(field + " is invalid after normalization");
        }
        return normalized;
    }

    private static String nullableText(String value, int maxLength, String field) {
        String normalized = BusinessCardTextNormalizer.text(value);
        if (normalized.isBlank()) {
            return null;
        }
        if (normalized.length() > maxLength) {
            throw new BadRequestException(field + " is too long after normalization");
        }
        return normalized;
    }

    private static ReviewedImport normalizeRequest(
            BusinessCardContactRequest contact,
            BusinessCardCompanyAction companyAction) {
        String name = requiredText(contact.name(), 255, "name");
        String email = nullableText(contact.email(), 255, "email");
        String phone = nullableText(contact.phone(), 64, "phone");
        String title = nullableText(contact.title(), 128, "title");
        BusinessCardCompanyAction normalizedAction = switch (companyAction) {
            case BusinessCardCompanyAction.Existing existing -> {
                if (existing.companyId() == null || existing.companyId() <= 0) {
                    throw new BadRequestException("companyId must be positive");
                }
                if (contact.companyId() != null && !contact.companyId().equals(existing.companyId())) {
                    throw new BadRequestException("contact.companyId must match the existing company action");
                }
                yield new BusinessCardCompanyAction.Existing(existing.companyId());
            }
            case BusinessCardCompanyAction.Create create -> {
                rejectConflictingCompanyId(contact);
                yield new BusinessCardCompanyAction.Create(
                        requiredText(create.companyName(), 255, "companyName"));
            }
            case BusinessCardCompanyAction.None ignored -> {
                rejectConflictingCompanyId(contact);
                yield new BusinessCardCompanyAction.None();
            }
        };
        return new ReviewedImport(name, email, phone, title, contact.companyId(), normalizedAction);
    }

    private static void rejectConflictingCompanyId(BusinessCardContactRequest contact) {
        if (contact.companyId() != null) {
            throw new BadRequestException("contact.companyId is only valid with an existing company action");
        }
    }

    private static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static BusinessCardImportReservationResponse reservation(LocalDateTime expiresAt) {
        return new BusinessCardImportReservationResponse(expiresAt.toInstant(ZoneOffset.UTC));
    }

    private int currentUserId() {
        User user = authService.getCurrentUser();
        if (user == null || user.getId() <= 0) {
            throw new IllegalStateException("Business-card import principal is unavailable");
        }
        return user.getId();
    }

    private static void requireOwnedByCurrentUser(BusinessCardImportRecord record, int userId) {
        if (record == null
                || record.createdByUserId() == null
                || record.createdByUserId() != userId) {
            throw new ResourceNotFoundException("Business-card import was not found");
        }
    }

    private BusinessCardImportReservationResponse renewOrResolveReservation(
            BusinessCardImportRecord record,
            int workspaceId,
            int userId,
            String requestId,
            Instant currentInstant,
            LocalDateTime now) {
        requireOwnedByCurrentUser(record, userId);
        if (record.expiresAt() == null || !record.expiresAt().isAfter(now)) {
            throw new BusinessCardImportResultGoneException(
                    "Business-card import reservation is no longer available");
        }
        if (record.requestFingerprint() != null) {
            return reservation(record.expiresAt());
        }
        if (record.reservationSlot() == null || record.submissionExpiresAt() == null) {
            throw new BusinessCardImportResultGoneException(
                    "Business-card import reservation is no longer available");
        }
        LocalDateTime submissionExpiresAt = utc(
                currentInstant.plus(properties.getReservationLease()));
        if (importRequestMapper.renewReservation(
                workspaceId,
                userId,
                requestId,
                submissionExpiresAt,
                now) == 1) {
            return reservation(record.expiresAt());
        }
        BusinessCardImportRecord current = importRequestMapper.get(workspaceId, requestId);
        if (current == null) {
            throw new BusinessCardImportResultGoneException(
                    "Business-card import reservation is no longer available");
        }
        requireOwnedByCurrentUser(current, userId);
        if (current.expiresAt() == null || !current.expiresAt().isAfter(now)) {
            throw new BusinessCardImportResultGoneException(
                    "Business-card import reservation is no longer available");
        }
        if (current.requestFingerprint() == null
                && (current.reservationSlot() == null
                    || current.submissionExpiresAt() == null
                    || !current.submissionExpiresAt().isAfter(now))) {
            throw new BusinessCardImportResultGoneException(
                    "Business-card import reservation is no longer available");
        }
        return reservation(current.expiresAt());
    }

    private void requireEntitled(Capability capability) {
        if (!capabilityEntitlement.isEntitled(capability)) {
            throw new ForbiddenException("This business-card capability is not entitled");
        }
    }

    private static void requireCurrentImportState(
            BusinessCardImportRecord record,
            int userId,
            LocalDateTime now) {
        if (record.expiresAt() == null || !record.expiresAt().isAfter(now)) {
            throw new BusinessCardImportResultGoneException(
                    "Business-card import reservation is no longer available");
        }
        if (record.requestFingerprint() == null
                && (record.createdByUserId() == null
                    || record.createdByUserId() != userId
                    || record.reservationSlot() == null
                    || record.submissionExpiresAt() == null
                    || !record.submissionExpiresAt().isAfter(now))) {
            throw new BusinessCardImportResultGoneException(
                    "Business-card import reservation expired before submission");
        }
    }

    private static byte[] fingerprint(byte[] image, ReviewedImport reviewed) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            updateDigest(digest, "connex-business-card-import-v1");
            updateDigest(digest, image);
            updateDigest(digest, reviewed.name());
            updateDigest(digest, reviewed.email());
            updateDigest(digest, reviewed.phone());
            updateDigest(digest, reviewed.title());
            updateDigest(digest, reviewed.contactCompanyId());
            switch (reviewed.companyAction()) {
                case BusinessCardCompanyAction.Existing existing -> {
                    updateDigest(digest, "existing");
                    updateDigest(digest, existing.companyId());
                }
                case BusinessCardCompanyAction.Create create -> {
                    updateDigest(digest, "create");
                    updateDigest(digest, create.companyName());
                }
                case BusinessCardCompanyAction.None ignored -> updateDigest(digest, "none");
            }
            return digest.digest();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static void updateDigest(MessageDigest digest, Integer value) {
        updateDigest(digest, value == null ? null : Integer.toString(value));
    }

    private static void updateDigest(MessageDigest digest, String value) {
        updateDigest(digest, value == null ? null : value.getBytes(StandardCharsets.UTF_8));
    }

    private static void updateDigest(MessageDigest digest, byte[] value) {
        int length = value == null ? -1 : value.length;
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(length).array());
        if (value != null) {
            digest.update(value);
        }
    }

    private record ReviewedImport(
            String name,
            String email,
            String phone,
            String title,
            Integer contactCompanyId,
            BusinessCardCompanyAction companyAction) {
    }
}
