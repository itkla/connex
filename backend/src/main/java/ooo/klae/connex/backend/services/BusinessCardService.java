package ooo.klae.connex.backend.services;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import ooo.klae.connex.backend.beans.Attachment;
import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Person;
import ooo.klae.connex.backend.businesscard.BusinessCardBinaryStore;
import ooo.klae.connex.backend.businesscard.BusinessCardExtractor;
import ooo.klae.connex.backend.businesscard.BusinessCardImageValidator;
import ooo.klae.connex.backend.businesscard.BusinessCardImportRecord;
import ooo.klae.connex.backend.businesscard.BusinessCardOcrClient;
import ooo.klae.connex.backend.businesscard.BusinessCardProperties;
import ooo.klae.connex.backend.businesscard.BusinessCardRateLimiter;
import ooo.klae.connex.backend.businesscard.BusinessCardTextNormalizer;
import ooo.klae.connex.backend.businesscard.ValidatedBusinessCardImage;
import ooo.klae.connex.backend.dto.AttachmentDto;
import ooo.klae.connex.backend.dto.BusinessCardCompanyAction;
import ooo.klae.connex.backend.dto.BusinessCardContactRequest;
import ooo.klae.connex.backend.dto.BusinessCardImportResponse;
import ooo.klae.connex.backend.dto.BusinessCardScanResponse;
import ooo.klae.connex.backend.dto.BusinessCardScanResponse.CompanyCandidate;
import ooo.klae.connex.backend.dto.CompanyDto;
import ooo.klae.connex.backend.dto.PersonDto;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ConflictException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.exceptions.ServiceUnavailableException;
import ooo.klae.connex.backend.mappers.BusinessCardImportRequestMapper;
import ooo.klae.connex.backend.tenant.Permission;
import ooo.klae.connex.backend.tenant.RequirePermission;

/**
 * Orchestrates review-first business-card scanning and confirmed contact imports.
 */
@Service
public class BusinessCardService {
    private static final Logger log = LoggerFactory.getLogger(BusinessCardService.class);

    private final BusinessCardProperties properties;
    private final BusinessCardImageValidator imageValidator;
    private final BusinessCardOcrClient ocrClient;
    private final BusinessCardExtractor extractor;
    private final BusinessCardBinaryStore binaryStore;
    private final CompanyService companyService;
    private final PersonService personService;
    private final AttachmentService attachmentService;
    private final WorkspaceService workspaceService;
    private final AuthService authService;
    private final BusinessCardImportRequestMapper importRequestMapper;
    private final BusinessCardRateLimiter rateLimiter;
    private final Clock clock;

    public BusinessCardService(
            BusinessCardProperties properties,
            BusinessCardImageValidator imageValidator,
            BusinessCardOcrClient ocrClient,
            BusinessCardExtractor extractor,
            BusinessCardBinaryStore binaryStore,
            CompanyService companyService,
            PersonService personService,
            AttachmentService attachmentService,
            WorkspaceService workspaceService,
            AuthService authService,
            BusinessCardImportRequestMapper importRequestMapper,
            BusinessCardRateLimiter rateLimiter,
            Clock clock) {
        this.properties = properties;
        this.imageValidator = imageValidator;
        this.ocrClient = ocrClient;
        this.extractor = extractor;
        this.binaryStore = binaryStore;
        this.companyService = companyService;
        this.personService = personService;
        this.attachmentService = attachmentService;
        this.workspaceService = workspaceService;
        this.authService = authService;
        this.importRequestMapper = importRequestMapper;
        this.rateLimiter = rateLimiter;
        this.clock = clock;
    }

    /**
     * Returns whether scanning and confirmed card retention are both ready for this instance.
     *
     * @return capability readiness
     */
    public boolean isAvailable() {
        return properties.isEnabled() && binaryStore.isReady() && ocrClient.isReady();
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
     * Extracts an editable draft from one validated image without persisting the image or OCR text.
     *
     * @param image uploaded card image
     * @return contact and company candidates
     */
    @RequirePermission(Permission.PERSON_CREATE)
    public BusinessCardScanResponse scan(MultipartFile image) {
        workspaceService.requirePermission(Permission.ATTACHMENT_CREATE);
        rateLimiter.requireScanAllowed();
        requireScanningReady();
        ValidatedBusinessCardImage validated = imageValidator.validate(image);
        BusinessCardScanResponse draft = extractor.extract(ocrClient.recognize(validated));
        return withCompanyMatch(draft);
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
        workspaceService.requirePermission(Permission.ATTACHMENT_CREATE);
        rateLimiter.requireImportAllowed();
        String requestId = canonicalIdempotencyKey(idempotencyKey);
        ValidatedBusinessCardImage validated = imageValidator.validate(image);
        ReviewedImport reviewed = normalizeRequest(contact, companyAction);
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        LocalDateTime cutoff = LocalDateTime.ofInstant(
                clock.instant().minus(properties.getIdempotencyRetention()), ZoneOffset.UTC);
        importRequestMapper.deleteExpired(
                workspaceId, cutoff, properties.getIdempotencyCleanupBatchSize());
        byte[] content = validated.content();
        byte[] requestFingerprint = fingerprint(content, reviewed);
        int claimed = importRequestMapper.claim(workspaceId, requestId, requestFingerprint);
        if (claimed == 0) {
            return replay(workspaceId, requestId, requestFingerprint);
        }
        if (claimed != 1) {
            throw new IllegalStateException("Business-card import idempotency claim was not unique");
        }
        requireBinaryStorageReady();
        Company company = resolveCompany(reviewed);
        Person person = personService.create(toPerson(reviewed, company));
        String fileName = "business-card." + validated.extension();
        BusinessCardBinaryStore.StoredBusinessCard stored = binaryStore.store(
                workspaceId, fileName, validated.contentType(), content);
        try {
            requireStored(stored, content.length);
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
        } catch (RuntimeException exception) {
            if (stored != null && stored.url() != null && !stored.url().isBlank()) {
                deleteStored(workspaceId, stored.url());
            }
            throw exception;
        }
    }

    /**
     * Returns a completed tenant-scoped import for response-loss reconciliation.
     *
     * @param idempotencyKey caller-generated UUID retained by the client
     * @return the completed import result
     */
    @Transactional
    @RequirePermission(Permission.PERSON_CREATE)
    public BusinessCardImportResponse importStatus(String idempotencyKey) {
        workspaceService.requirePermission(Permission.ATTACHMENT_CREATE);
        String requestId = canonicalIdempotencyKey(idempotencyKey);
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        BusinessCardImportRecord record = importRequestMapper.get(workspaceId, requestId);
        if (record == null) {
            throw new ResourceNotFoundException("Completed business-card import was not found");
        }
        if (record.personId() == null || record.attachmentId() == null) {
            throw new ConflictException("Business-card import is still in progress");
        }
        return completedResponse(record);
    }

    private BusinessCardImportResponse replay(
            int workspaceId,
            String requestId,
            byte[] requestFingerprint) {
        BusinessCardImportRecord record = importRequestMapper.get(workspaceId, requestId);
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
        Person person = personService.getPersonById(record.personId());
        Attachment attachment = attachmentService.getById(record.attachmentId());
        Company company = record.companyId() == null
            ? null
            : companyService.getCompanyById(record.companyId());
        return new BusinessCardImportResponse(
            PersonDto.from(person),
            AttachmentDto.from(attachment),
            CompanyDto.from(company));
    }

    private BusinessCardScanResponse withCompanyMatch(BusinessCardScanResponse draft) {
        CompanyCandidate candidate = draft.company();
        if (candidate == null || candidate.value() == null) {
            return draft;
        }
        List<Company> matches = companyService.findVisibleByNormalizedName(candidate.value());
        Integer matchedId = matches.size() == 1 ? matches.getFirst().getId() : null;
        List<String> warnings = new ArrayList<>(draft.warnings());
        if (matches.size() > 1) {
            warnings.add("company_match_ambiguous");
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

    private void requireScanningReady() {
        if (!properties.isEnabled() || !binaryStore.isReady() || !ocrClient.isReady()) {
            throw new ServiceUnavailableException("Business-card scanning is unavailable");
        }
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

    private void deleteStored(int workspaceId, String url) {
        try {
            binaryStore.delete(workspaceId, url);
        } catch (RuntimeException exception) {
            log.warn("Business-card binary rollback cleanup failed");
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

    private static String canonicalIdempotencyKey(String value) {
        if (value == null || value.length() != 36) {
            throw new BadRequestException("Idempotency-Key must be a UUID");
        }
        try {
            String canonical = UUID.fromString(value).toString();
            if (!canonical.equalsIgnoreCase(value)) {
                throw new BadRequestException("Idempotency-Key must be a UUID");
            }
            return canonical;
        } catch (IllegalArgumentException exception) {
            throw new BadRequestException("Idempotency-Key must be a UUID");
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
