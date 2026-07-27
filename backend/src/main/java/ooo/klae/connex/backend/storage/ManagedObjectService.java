package ooo.klae.connex.backend.storage;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.beans.Attachment;
import ooo.klae.connex.backend.dto.ActiveObjectReference;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.exceptions.ServiceUnavailableException;
import ooo.klae.connex.backend.storage.ImageUploadValidator.ValidatedImage;
import ooo.klae.connex.backend.storage.ObjectStorageProperties.LegacyMigrationMode;
import ooo.klae.connex.backend.storage.UploadPolicy.ValidatedUpload;

/**
 * Generates opaque app-relative managed URLs and maps them to private object keys.
 */
@Service
@RequiredArgsConstructor
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
public class ManagedObjectService implements ApplicationRunner {
    private static final String ATTACHMENT_URL_PREFIX = "/api/attachments/content/";
    private static final Pattern TOKEN = Pattern.compile(
        "^([0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12})(?:\\.([a-z0-9]{1,10}))?$"
    );

    private final ObjectStorage objectStorage;
    private final ObjectDeletionRetryQueue deletionRetryQueue;
    private final UploadPolicy uploadPolicy;
    private final ImageUploadValidator imageUploadValidator;
    private final ObjectStorageProperties properties;
    private final WorkspaceObjectStorageQuotaService quotaService;
    private final UserImageReplacementAdmissionService userImageAdmissionService;
    private final ManagedObjectWriteAdmissionService writeAdmissionService;
    private final ManagedObjectReadAdmissionService readAdmissionService;
    private final AtomicBoolean readinessRefreshInFlight = new AtomicBoolean();
    private final AtomicLong readinessGeneration = new AtomicLong();
    private volatile ReadinessSnapshot readinessSnapshot;

    public boolean isReady() {
        long now = System.nanoTime();
        ReadinessSnapshot snapshot = readinessSnapshot;
        long ttl = TimeUnit.MILLISECONDS.toNanos(properties.getReadinessCacheTtlMs());
        if (snapshot == null || now - snapshot.checkedAtNanos() >= ttl) {
            refreshReadiness();
        }
        return snapshot != null && snapshot.ready();
    }

    @Override
    public void run(ApplicationArguments args) {
        refreshReadiness();
    }

    private void refreshReadiness() {
        if (properties.getLegacyMigration().getMode() != LegacyMigrationMode.OFF
                || !readinessRefreshInFlight.compareAndSet(false, true)) {
            return;
        }
        long generation = readinessGeneration.get();
        Thread.startVirtualThread(() -> {
            boolean ready;
            try {
                ready = objectStorage.isReady();
            } catch (RuntimeException exception) {
                ready = false;
            }
            try {
                if (readinessGeneration.get() == generation) {
                    readinessSnapshot = new ReadinessSnapshot(ready, System.nanoTime());
                }
            } finally {
                readinessRefreshInFlight.set(false);
            }
        });
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public StoredBinary storeAttachment(int workspaceId, UploadSource source) {
        return storeAttachmentInternal(workspaceId, source);
    }

    private StoredBinary storeAttachmentInternal(int workspaceId, UploadSource source) {
        ValidatedUpload upload = uploadPolicy.validateGeneric(source);
        String token = token(upload.extension());
        String key = attachmentKey(workspaceId, token);
        String url = ATTACHMENT_URL_PREFIX + token;
        storeTenant(workspaceId, key, source, upload.contentType());
        return new StoredBinary(url, upload.fileName(), upload.contentType(), source.contentLength());
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public StoredBinary storeAttachment(
            int workspaceId,
            String fileName,
            String contentType,
            byte[] bytes) {
        return storeAttachmentInternal(workspaceId, UploadSource.from(fileName, contentType, bytes));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public StoredImage storePersonImage(int workspaceId, int personId, UploadSource source) {
        ValidatedImage image = imageUploadValidator.validate(source);
        byte[] content = image.content();
        UploadSource validatedSource = UploadSource.from(source.fileName(), image.contentType(), content);
        String token = token(image.extension());
        String key = personImageKey(workspaceId, personId, token);
        String url = personImageUrl(personId, token);
        storeTenant(workspaceId, key, validatedSource, image.contentType());
        return new StoredImage(url, content.length, image.contentType());
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public StoredImage storeCompanyImage(int workspaceId, int companyId, UploadSource source) {
        ValidatedImage image = imageUploadValidator.validate(source);
        byte[] content = image.content();
        UploadSource validatedSource = UploadSource.from(source.fileName(), image.contentType(), content);
        String token = token(image.extension());
        String key = companyImageKey(workspaceId, companyId, token);
        String url = companyImageUrl(companyId, token);
        storeTenant(workspaceId, key, validatedSource, image.contentType());
        return new StoredImage(url, content.length, image.contentType());
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public StoredImage storeUserImage(int userId, UploadSource source) {
        userImageAdmissionService.requireAllowed(userId);
        ValidatedImage image = imageUploadValidator.validate(source);
        byte[] content = image.content();
        UploadSource validatedSource = UploadSource.from(source.fileName(), image.contentType(), content);
        String token = token(image.extension());
        String key = userImageKey(userId, token);
        String url = userImageUrl(userId, token);
        storeUser(key, validatedSource, image.contentType());
        return new StoredImage(url, content.length, image.contentType());
    }

    StoredBinary storeMigratedAttachment(
            int workspaceId,
            int attachmentId,
            String legacyUrl,
            UploadSource source) {
        ValidatedUpload upload = uploadPolicy.validateGeneric(source);
        String objectToken = migrationToken(
            "attachment", workspaceId, attachmentId, legacyUrl, upload.extension());
        String key = attachmentKey(workspaceId, objectToken);
        String url = ATTACHMENT_URL_PREFIX + objectToken;
        storeTenantDeterministic(workspaceId, key, source, upload.contentType());
        return new StoredBinary(
            url, upload.fileName(), upload.contentType(), source.contentLength());
    }

    StoredMigratedImage storeMigratedPersonImage(
            int workspaceId,
            int personId,
            String legacyUrl,
            UploadSource source) {
        ValidatedImage image = imageUploadValidator.validate(source);
        byte[] content = image.content();
        String objectToken = migrationToken(
            "person-image", workspaceId, personId, legacyUrl, image.extension());
        String key = personImageKey(workspaceId, personId, objectToken);
        storeTenantDeterministic(
            workspaceId,
            key,
            UploadSource.from(source.fileName(), image.contentType(), content),
            image.contentType());
        return new StoredMigratedImage(
            personImageUrl(personId, objectToken),
            content.length,
            image.contentType(),
            content);
    }

    StoredMigratedImage storeMigratedCompanyImage(
            int workspaceId,
            int companyId,
            String legacyUrl,
            UploadSource source) {
        ValidatedImage image = imageUploadValidator.validate(source);
        byte[] content = image.content();
        String objectToken = migrationToken(
            "company-image", workspaceId, companyId, legacyUrl, image.extension());
        String key = companyImageKey(workspaceId, companyId, objectToken);
        storeTenantDeterministic(
            workspaceId,
            key,
            UploadSource.from(source.fileName(), image.contentType(), content),
            image.contentType());
        return new StoredMigratedImage(
            companyImageUrl(companyId, objectToken),
            content.length,
            image.contentType(),
            content);
    }

    StoredMigratedImage storeMigratedUserImage(
            int userId,
            String legacyUrl,
            UploadSource source) {
        ValidatedImage image = imageUploadValidator.validate(source);
        byte[] content = image.content();
        String objectToken = migrationToken(
            "user-image", 0, userId, legacyUrl, image.extension());
        String key = userImageKey(userId, objectToken);
        storeUserDeterministic(
            key,
            UploadSource.from(source.fileName(), image.contentType(), content),
            image.contentType());
        return new StoredMigratedImage(
            userImageUrl(userId, objectToken),
            content.length,
            image.contentType(),
            content);
    }

    void validateMigratedAttachmentTarget(
            int workspaceId,
            int attachmentId,
            String legacyUrl,
            String extension,
            byte[] expectedContent) {
        String objectToken = migrationToken(
            "attachment", workspaceId, attachmentId, legacyUrl, extension);
        verifyContentIfPresent(attachmentKey(workspaceId, objectToken), expectedContent);
    }

    void validateMigratedPersonImageTarget(
            int workspaceId,
            int personId,
            String legacyUrl,
            String extension,
            byte[] expectedContent) {
        String objectToken = migrationToken(
            "person-image", workspaceId, personId, legacyUrl, extension);
        verifyContentIfPresent(
            personImageKey(workspaceId, personId, objectToken), expectedContent);
    }

    void validateMigratedCompanyImageTarget(
            int workspaceId,
            int companyId,
            String legacyUrl,
            String extension,
            byte[] expectedContent) {
        String objectToken = migrationToken(
            "company-image", workspaceId, companyId, legacyUrl, extension);
        verifyContentIfPresent(
            companyImageKey(workspaceId, companyId, objectToken), expectedContent);
    }

    void validateMigratedUserImageTarget(
            int userId,
            String legacyUrl,
            String extension,
            byte[] expectedContent) {
        String objectToken = migrationToken(
            "user-image", 0, userId, legacyUrl, extension);
        verifyContentIfPresent(userImageKey(userId, objectToken), expectedContent);
    }

    public ManagedContent openAttachment(int workspaceId, Attachment attachment) {
        String token = requireManagedToken(attachment.getUrl(), ATTACHMENT_URL_PREFIX);
        StoredObject object = getForResponse(attachmentKey(workspaceId, token));
        return new ManagedContent(
            object,
            uploadPolicy.safeResponseContentType(attachment.getContentType()),
            uploadPolicy.safeResponseFileName(attachment.getFileName())
        );
    }

    public ManagedContent openPersonImage(
            int ownerWorkspaceId,
            int personId,
            String persistedUrl,
            String requestedToken) {
        String token = requireRequestedToken(persistedUrl, personImageUrl(personId, requestedToken), requestedToken);
        StoredObject object = getForResponse(personImageKey(ownerWorkspaceId, personId, token));
        return new ManagedContent(object, imageContentType(token), "contact-picture." + extension(token));
    }

    public ManagedContent openCompanyImage(
            int ownerWorkspaceId,
            int companyId,
            String persistedUrl,
            String requestedToken) {
        String token = requireRequestedToken(persistedUrl, companyImageUrl(companyId, requestedToken), requestedToken);
        StoredObject object = getForResponse(companyImageKey(ownerWorkspaceId, companyId, token));
        return new ManagedContent(object, imageContentType(token), "company-logo." + extension(token));
    }

    public ManagedContent openUserImage(int userId, String persistedUrl, String requestedToken) {
        String token = requireRequestedToken(persistedUrl, userImageUrl(userId, requestedToken), requestedToken);
        StoredObject object = getForResponse(userImageKey(userId, token));
        return new ManagedContent(object, imageContentType(token), "profile-picture." + extension(token));
    }

    /**
     * Opens one registry-enumerated active tenant object for an already
     * authorized export actor. The database reference, canonical object key,
     * owning workspace, and quota ledger length must all agree before storage
     * access.
     */
    public ManagedTenantObject openTenantExportObject(
            int workspaceId,
            int actorId,
            ActiveObjectReference reference,
            Duration timeout) {
        Objects.requireNonNull(reference, "reference");
        if (!reference.usagePresent() || reference.usageSizeBytes() < 0) {
            throw new IllegalStateException("Active managed object is missing its usage ledger");
        }
        String expectedKey = switch (reference.kind()) {
            case "attachment" -> managedAttachmentKey(workspaceId, reference.persistedUrl())
                .orElseThrow(() -> new IllegalStateException("Attachment object reference is invalid"));
            case "person_image" -> managedPersonImageKey(
                    workspaceId,
                    positive(reference.ownerId()),
                    reference.persistedUrl())
                .orElseThrow(() -> new IllegalStateException("Person image object reference is invalid"));
            case "company_image" -> managedCompanyImageKey(
                    workspaceId,
                    positive(reference.ownerId()),
                    reference.persistedUrl())
                .orElseThrow(() -> new IllegalStateException("Company image object reference is invalid"));
            default -> throw new IllegalStateException("Active managed object category is invalid");
        };
        if (!expectedKey.equals(reference.objectKey())) {
            throw new IllegalStateException("Active managed object key does not match its metadata");
        }
        StoredObject object = readAdmissionService.admit(
            actorId,
            timeout,
            () -> get(expectedKey));
        if (object.contentLength() != reference.usageSizeBytes()) {
            try {
                object.close();
            } catch (IOException exception) {
                throw new ServiceUnavailableException(
                    "Managed export object could not be closed", exception);
            }
            throw new IllegalStateException("Active managed object length does not match its usage ledger");
        }
        return new ManagedTenantObject(expectedKey, object, reference.usageSizeBytes());
    }

    void verifyAttachment(int workspaceId, String url, byte[] expectedContent) {
        String key = managedAttachmentKey(workspaceId, url)
            .orElseThrow(() -> new IllegalStateException("Migrated attachment reference is invalid"));
        verifyContent(key, expectedContent);
    }

    void verifyPersonImage(int workspaceId, int personId, String url, byte[] expectedContent) {
        String key = managedPersonImageKey(workspaceId, personId, url)
            .orElseThrow(() -> new IllegalStateException("Migrated contact image reference is invalid"));
        verifyContent(key, expectedContent);
    }

    void verifyCompanyImage(int workspaceId, int companyId, String url, byte[] expectedContent) {
        String key = managedCompanyImageKey(workspaceId, companyId, url)
            .orElseThrow(() -> new IllegalStateException("Migrated company image reference is invalid"));
        verifyContent(key, expectedContent);
    }

    void verifyUserImage(int userId, String url, byte[] expectedContent) {
        String key = managedUserImageKey(userId, url)
            .orElseThrow(() -> new IllegalStateException("Migrated user image reference is invalid"));
        verifyContent(key, expectedContent);
    }

    public void deleteAttachmentAfterCommit(int workspaceId, String url) {
        managedAttachmentKey(workspaceId, url)
            .ifPresent(key -> deleteTenantAfterCommit(workspaceId, key));
    }

    public void deletePersonImageAfterCommit(int workspaceId, int personId, String url) {
        managedPersonImageKey(workspaceId, personId, url)
            .ifPresent(key -> deleteTenantAfterCommit(workspaceId, key));
    }

    public void deleteCompanyImageAfterCommit(int workspaceId, int companyId, String url) {
        managedCompanyImageKey(workspaceId, companyId, url)
            .ifPresent(key -> deleteTenantAfterCommit(workspaceId, key));
    }

    public void deleteUserImageAfterCommit(int userId, String url) {
        managedUserImageKey(userId, url).ifPresent(this::deleteUserAfterCommit);
    }

    public void deleteAttachment(int workspaceId, String url) {
        managedAttachmentKey(workspaceId, url)
            .ifPresent(key -> deleteTenantOnRollback(workspaceId, key));
    }

    private void storeTenant(int workspaceId, String key, UploadSource source, String contentType) {
        byte[] checksum = sha256(source);
        requireTransactionSynchronization();
        writeAdmissionService.admit(() -> {
            deletionRetryQueue.requireTenantWriteAllowed(workspaceId);
            ObjectDeletionTombstone tombstone = deletionRetryQueue.prepareTenantWrite(
                workspaceId, key);
            deletionRetryQueue.lockTenantInCurrentTransaction(workspaceId, tombstone);
            quotaService.reserve(workspaceId, key, source.contentLength());
            put(key, source, contentType, checksum);
            deletionRetryQueue.cancelTenantInCurrentTransaction(workspaceId, tombstone);
            return null;
        });
    }

    private void storeUser(String key, UploadSource source, String contentType) {
        byte[] checksum = sha256(source);
        requireTransactionSynchronization();
        writeAdmissionService.admit(() -> {
            ObjectDeletionTombstone tombstone = deletionRetryQueue.prepareUserWrite(key);
            deletionRetryQueue.lockUserInCurrentTransaction(tombstone);
            put(key, source, contentType, checksum);
            deletionRetryQueue.cancelUserInCurrentTransaction(tombstone);
            return null;
        });
    }

    private void storeTenantDeterministic(
            int workspaceId,
            String key,
            UploadSource source,
            String contentType) {
        byte[] checksum = sha256(source);
        requireTransactionSynchronization();
        writeAdmissionService.admit(() -> {
            deletionRetryQueue.requireTenantWriteAllowed(workspaceId);
            ObjectDeletionTombstone tombstone = deletionRetryQueue.prepareTenantWrite(
                workspaceId, key);
            deletionRetryQueue.lockTenantInCurrentTransaction(workspaceId, tombstone);
            quotaService.reserve(workspaceId, key, source.contentLength());
            if (!verifyChecksumIfPresent(key, source.contentLength(), checksum)) {
                put(key, source, contentType, checksum);
            }
            deletionRetryQueue.cancelTenantInCurrentTransaction(workspaceId, tombstone);
            return null;
        });
    }

    private void storeUserDeterministic(
            String key,
            UploadSource source,
            String contentType) {
        byte[] checksum = sha256(source);
        requireTransactionSynchronization();
        writeAdmissionService.admit(() -> {
            ObjectDeletionTombstone tombstone = deletionRetryQueue.prepareUserWrite(key);
            deletionRetryQueue.lockUserInCurrentTransaction(tombstone);
            if (!verifyChecksumIfPresent(key, source.contentLength(), checksum)) {
                put(key, source, contentType, checksum);
            }
            deletionRetryQueue.cancelUserInCurrentTransaction(tombstone);
            return null;
        });
    }

    private void put(
            String key,
            UploadSource source,
            String contentType,
            byte[] checksum) {
        try {
            objectStorage.put(key, source, contentType, checksum);
        } catch (ObjectStorageException exception) {
            markUnavailable();
            throw new ServiceUnavailableException("Private object storage is unavailable");
        }
    }

    private boolean verifyChecksumIfPresent(String key, long expectedLength, byte[] expectedHash) {
        try {
            StoredObject stored = objectStorage.get(key);
            if (stored == null) {
                return false;
            }
            try (stored;
                    DigestInputStream input = new DigestInputStream(
                        stored.inputStream(), sha256Digest())) {
                MessageDigest actualDigest = input.getMessageDigest();
                long copied = input.transferTo(OutputStream.nullOutputStream());
                if (stored.contentLength() != expectedLength
                        || copied != expectedLength
                        || !MessageDigest.isEqual(expectedHash, actualDigest.digest())) {
                    throw new IllegalStateException(
                        "Existing migration target does not match its legacy source");
                }
                return true;
            }
        } catch (ObjectStorageNotFoundException exception) {
            return false;
        } catch (ObjectStorageException exception) {
            markUnavailable();
            throw new ServiceUnavailableException("Migration target could not be checked");
        } catch (IOException exception) {
            throw new ServiceUnavailableException("Migration target could not be checked");
        }
    }

    private StoredObject get(String key) {
        StoredObject stored;
        try {
            stored = objectStorage.get(key);
        } catch (ObjectStorageNotFoundException exception) {
            throw new ResourceNotFoundException("Stored file was not found");
        } catch (ObjectStorageException exception) {
            markUnavailable();
            throw new ServiceUnavailableException("Private object storage is unavailable");
        }
        if (stored == null) {
            throw new ResourceNotFoundException("Stored file was not found");
        }
        return stored;
    }

    private StoredObject getForResponse(String key) {
        return readAdmissionService.admit(() -> get(key));
    }

    private void markUnavailable() {
        readinessGeneration.incrementAndGet();
        readinessSnapshot = new ReadinessSnapshot(false, 0);
    }

    private void verifyContent(String key, byte[] expectedContent) {
        byte[] expectedHash = sha256Digest().digest(expectedContent);
        try (StoredObject stored = objectStorage.get(key);
                DigestInputStream input = new DigestInputStream(
                    stored.inputStream(), sha256Digest())) {
            MessageDigest actualDigest = input.getMessageDigest();
            long copied = input.transferTo(OutputStream.nullOutputStream());
            if (stored.contentLength() != expectedContent.length
                    || copied != expectedContent.length
                    || !MessageDigest.isEqual(expectedHash, actualDigest.digest())) {
                throw new IllegalStateException("Migrated object integrity verification failed");
            }
        } catch (ObjectStorageException exception) {
            markUnavailable();
            throw new ServiceUnavailableException("Migrated object could not be verified");
        } catch (IOException exception) {
            throw new ServiceUnavailableException("Migrated object could not be verified");
        }
    }

    private void verifyContentIfPresent(String key, byte[] expectedContent) {
        try (StoredObject stored = objectStorage.get(key);
                DigestInputStream input = new DigestInputStream(
                    stored.inputStream(), sha256Digest())) {
            MessageDigest actualDigest = input.getMessageDigest();
            long copied = input.transferTo(OutputStream.nullOutputStream());
            byte[] expectedHash = sha256Digest().digest(expectedContent);
            if (stored.contentLength() != expectedContent.length
                    || copied != expectedContent.length
                    || !MessageDigest.isEqual(expectedHash, actualDigest.digest())) {
                throw new IllegalStateException(
                    "Existing migration target does not match its legacy source");
            }
        } catch (ObjectStorageNotFoundException exception) {
            return;
        } catch (ObjectStorageException exception) {
            markUnavailable();
            throw new ServiceUnavailableException("Migration target could not be checked");
        } catch (IOException exception) {
            throw new ServiceUnavailableException("Migration target could not be checked");
        }
    }

    private static byte[] sha256(UploadSource source) {
        MessageDigest digest = sha256Digest();
        try (InputStream input = new DigestInputStream(source.openStream(), digest)) {
            long read = input.transferTo(OutputStream.nullOutputStream());
            if (read != source.contentLength()) {
                throw new BadRequestException("Uploaded file length is invalid");
            }
            return digest.digest();
        } catch (IOException exception) {
            throw new ServiceUnavailableException("Uploaded file could not be read");
        }
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private Optional<String> managedAttachmentKey(int workspaceId, String url) {
        return managedToken(url, ATTACHMENT_URL_PREFIX).map(token -> attachmentKey(workspaceId, token));
    }

    private Optional<String> managedPersonImageKey(int workspaceId, int personId, String url) {
        return managedToken(url, personImagePrefix(personId))
            .map(token -> personImageKey(workspaceId, personId, token));
    }

    private Optional<String> managedCompanyImageKey(int workspaceId, int companyId, String url) {
        return managedToken(url, companyImagePrefix(companyId))
            .map(token -> companyImageKey(workspaceId, companyId, token));
    }

    private Optional<String> managedUserImageKey(int userId, String url) {
        return managedToken(url, userImagePrefix(userId)).map(token -> userImageKey(userId, token));
    }

    private static Optional<String> managedToken(String url, String prefix) {
        if (url == null || !url.startsWith(prefix)) {
            return Optional.empty();
        }
        String token = url.substring(prefix.length());
        return isValidToken(token) && url.equals(prefix + token) ? Optional.of(token) : Optional.empty();
    }

    private static String requireManagedToken(String url, String prefix) {
        return managedToken(url, prefix)
            .orElseThrow(() -> new ResourceNotFoundException("Managed file reference was not found"));
    }

    private static String requireRequestedToken(String persistedUrl, String expectedUrl, String requestedToken) {
        if (!isValidToken(requestedToken) || !Objects.equals(persistedUrl, expectedUrl)) {
            throw new ResourceNotFoundException("Managed image was not found");
        }
        return requestedToken;
    }

    private void deleteTenantOnRollback(int workspaceId, String key) {
        deletionRetryQueue.enqueueRollbackTombstoneTenant(workspaceId, key);
    }

    private static void requireTransactionSynchronization() {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException(
                "Managed object writes require an active metadata transaction");
        }
    }

    private void deleteTenantAfterCommit(int workspaceId, String key) {
        deletionRetryQueue.enqueueTenantInCurrentTransaction(workspaceId, key);
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            deletionRetryQueue.processTenant(workspaceId, key);
        }
    }

    private void deleteUserAfterCommit(String key) {
        deletionRetryQueue.enqueueUserInCurrentTransaction(key);
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            deletionRetryQueue.processUser(key);
        }
    }

    private static String attachmentKey(int workspaceId, String token) {
        return "workspaces/" + positive(workspaceId) + "/attachments/" + requireToken(token);
    }

    private static String personImageKey(int workspaceId, int personId, String token) {
        return "workspaces/" + positive(workspaceId) + "/person-images/" + positive(personId) + "/" + requireToken(token);
    }

    private static String companyImageKey(int workspaceId, int companyId, String token) {
        return "workspaces/" + positive(workspaceId) + "/company-images/" + positive(companyId) + "/" + requireToken(token);
    }

    private static String userImageKey(int userId, String token) {
        return "users/" + positive(userId) + "/profile-images/" + requireToken(token);
    }

    private static String personImagePrefix(int personId) {
        return "/api/persons/" + positive(personId) + "/profile-picture/";
    }

    private static String personImageUrl(int personId, String token) {
        return personImagePrefix(personId) + requireToken(token);
    }

    private static String companyImagePrefix(int companyId) {
        return "/api/companies/" + positive(companyId) + "/logo/";
    }

    private static String companyImageUrl(int companyId, String token) {
        return companyImagePrefix(companyId) + requireToken(token);
    }

    private static String userImagePrefix(int userId) {
        return "/api/users/" + positive(userId) + "/profile-picture/";
    }

    private static String userImageUrl(int userId, String token) {
        return userImagePrefix(userId) + requireToken(token);
    }

    private static String token(String extension) {
        return token(UUID.randomUUID().toString(), extension);
    }

    private static String migrationToken(
            String type,
            int workspaceId,
            int ownerId,
            String legacyUrl,
            String extension) {
        String identity = "connex-legacy-upload-v1\u0000" + type + "\u0000"
            + workspaceId + "\u0000" + ownerId + "\u0000" + legacyUrl;
        byte[] digest = sha256Digest().digest(identity.getBytes(StandardCharsets.UTF_8));
        digest[6] = (byte) ((digest[6] & 0x0f) | 0x40);
        digest[8] = (byte) ((digest[8] & 0x3f) | 0x80);
        ByteBuffer buffer = ByteBuffer.wrap(digest);
        String id = new UUID(buffer.getLong(), buffer.getLong()).toString();
        return token(id, extension);
    }

    private static String token(String id, String extension) {
        return extension == null || extension.isBlank()
            ? id
            : id + "." + extension.toLowerCase(Locale.ROOT);
    }

    private static String requireToken(String token) {
        if (!isValidToken(token)) {
            throw new BadRequestException("Managed object token is invalid");
        }
        return token;
    }

    private static boolean isValidToken(String token) {
        return token != null && TOKEN.matcher(token).matches();
    }

    private static int positive(int value) {
        if (value <= 0) {
            throw new BadRequestException("Managed object owner id is invalid");
        }
        return value;
    }

    private static String extension(String token) {
        Matcher matcher = TOKEN.matcher(token);
        if (!matcher.matches() || matcher.group(2) == null) {
            throw new ResourceNotFoundException("Managed image type was not found");
        }
        return matcher.group(2);
    }

    private static String imageContentType(String token) {
        return switch (extension(token)) {
            case "jpg" -> "image/jpeg";
            case "png" -> "image/png";
            case "webp" -> "image/webp";
            default -> throw new ResourceNotFoundException("Managed image type was not found");
        };
    }

    /**
     * Public managed attachment reference safe to persist in a tenant-scoped record.
     *
     * @param url opaque authenticated app-relative content URL
     * @param fileName safe display file name
     * @param contentType normalized media type
     * @param size stored byte length
     */
    public record StoredBinary(String url, String fileName, String contentType, long size) {}

    /**
     * Public managed image reference safe to persist in its owning record.
     *
     * @param url opaque authenticated app-relative content URL
     * @param size stored byte length
     * @param contentType detected image media type
     */
    public record StoredImage(String url, long size, String contentType) {}

    record StoredMigratedImage(String url, long size, String contentType, byte[] content) {
        StoredMigratedImage {
            content = Objects.requireNonNull(content, "content").clone();
        }

        @Override
        public byte[] content() {
            return content.clone();
        }
    }

    /**
     * Authorized managed object response metadata and open stream.
     *
     * @param object private stored object
     * @param contentType trusted response media type
     * @param fileName safe response file name
     */
    public record ManagedContent(StoredObject object, String contentType, String fileName) implements AutoCloseable {
        public ManagedContent {
            Objects.requireNonNull(object, "object");
            Objects.requireNonNull(contentType, "contentType");
            Objects.requireNonNull(fileName, "fileName");
        }

        public InputStream inputStream() {
            return object.inputStream();
        }

        public long contentLength() {
            return object.contentLength();
        }

        @Override
        public void close() throws IOException {
            object.close();
        }
    }

    /**
     * Validated tenant export object with its canonical ZIP-relative key and
     * expected streamed length.
     */
    public record ManagedTenantObject(
            String objectKey,
            StoredObject object,
            long expectedLength) implements AutoCloseable {

        public ManagedTenantObject {
            Objects.requireNonNull(objectKey, "objectKey");
            Objects.requireNonNull(object, "object");
            if (expectedLength < 0) {
                throw new IllegalArgumentException("Managed object length is invalid");
            }
        }

        public InputStream inputStream() {
            return object.inputStream();
        }

        @Override
        public void close() throws IOException {
            object.close();
        }
    }

    private record ReadinessSnapshot(boolean ready, long checkedAtNanos) {}

}
