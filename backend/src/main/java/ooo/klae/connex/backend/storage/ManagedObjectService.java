package ooo.klae.connex.backend.storage;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.beans.Attachment;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.exceptions.ServiceUnavailableException;
import ooo.klae.connex.backend.storage.ImageUploadValidator.ValidatedImage;
import ooo.klae.connex.backend.storage.UploadPolicy.ValidatedUpload;

/**
 * Generates opaque app-relative managed URLs and maps them to private object keys.
 */
@Service
@RequiredArgsConstructor
public class ManagedObjectService {
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
    private final Object readinessMonitor = new Object();
    private volatile ReadinessSnapshot readinessSnapshot;

    public boolean isReady() {
        long now = System.nanoTime();
        ReadinessSnapshot snapshot = readinessSnapshot;
        long ttl = TimeUnit.MILLISECONDS.toNanos(properties.getReadinessCacheTtlMs());
        if (snapshot != null && now - snapshot.checkedAtNanos() < ttl) {
            return snapshot.ready();
        }
        synchronized (readinessMonitor) {
            snapshot = readinessSnapshot;
            now = System.nanoTime();
            if (snapshot != null && now - snapshot.checkedAtNanos() < ttl) {
                return snapshot.ready();
            }
            boolean ready;
            try {
                ready = objectStorage.isReady();
            } catch (RuntimeException exception) {
                ready = false;
            }
            readinessSnapshot = new ReadinessSnapshot(ready, now);
            return ready;
        }
    }

    @Transactional
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

    @Transactional
    public StoredBinary storeAttachment(
            int workspaceId,
            String fileName,
            String contentType,
            byte[] bytes) {
        return storeAttachmentInternal(workspaceId, UploadSource.from(fileName, contentType, bytes));
    }

    @Transactional
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

    @Transactional
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

    public StoredImage storeUserImage(int userId, UploadSource source) {
        ValidatedImage image = imageUploadValidator.validate(source);
        byte[] content = image.content();
        UploadSource validatedSource = UploadSource.from(source.fileName(), image.contentType(), content);
        String token = token(image.extension());
        String key = userImageKey(userId, token);
        String url = userImageUrl(userId, token);
        store(key, validatedSource, image.contentType());
        return new StoredImage(url, content.length, image.contentType());
    }

    public ManagedContent openAttachment(int workspaceId, Attachment attachment) {
        String token = requireManagedToken(attachment.getUrl(), ATTACHMENT_URL_PREFIX);
        StoredObject object = get(attachmentKey(workspaceId, token));
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
        StoredObject object = get(personImageKey(ownerWorkspaceId, personId, token));
        return new ManagedContent(object, imageContentType(token), "contact-picture." + extension(token));
    }

    public ManagedContent openCompanyImage(
            int ownerWorkspaceId,
            int companyId,
            String persistedUrl,
            String requestedToken) {
        String token = requireRequestedToken(persistedUrl, companyImageUrl(companyId, requestedToken), requestedToken);
        StoredObject object = get(companyImageKey(ownerWorkspaceId, companyId, token));
        return new ManagedContent(object, imageContentType(token), "company-logo." + extension(token));
    }

    public ManagedContent openUserImage(int userId, String persistedUrl, String requestedToken) {
        String token = requireRequestedToken(persistedUrl, userImageUrl(userId, requestedToken), requestedToken);
        StoredObject object = get(userImageKey(userId, token));
        return new ManagedContent(object, imageContentType(token), "profile-picture." + extension(token));
    }

    public void compensateAttachmentOnRollback(int workspaceId, String url) {
        managedAttachmentKey(workspaceId, url)
            .ifPresent(key -> deleteTenantOnRollback(workspaceId, key));
    }

    public void compensatePersonImageOnRollback(int workspaceId, int personId, String url) {
        managedPersonImageKey(workspaceId, personId, url)
            .ifPresent(key -> deleteTenantOnRollback(workspaceId, key));
    }

    public void compensateCompanyImageOnRollback(int workspaceId, int companyId, String url) {
        managedCompanyImageKey(workspaceId, companyId, url)
            .ifPresent(key -> deleteTenantOnRollback(workspaceId, key));
    }

    public void compensateUserImageOnRollback(int userId, String url) {
        managedUserImageKey(userId, url).ifPresent(this::deleteUserOnRollback);
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
            .ifPresent(key -> deleteTenantAfterCompletion(workspaceId, key));
    }

    private void store(String key, UploadSource source, String contentType) {
        try {
            objectStorage.put(key, source, contentType, sha256(source));
        } catch (ObjectStorageException exception) {
            throw new ServiceUnavailableException("Private object storage is unavailable");
        }
    }

    private void storeTenant(int workspaceId, String key, UploadSource source, String contentType) {
        quotaService.reserve(workspaceId, key, source.contentLength());
        store(key, source, contentType);
    }

    private StoredObject get(String key) {
        try {
            return objectStorage.get(key);
        } catch (ObjectStorageNotFoundException exception) {
            throw new ResourceNotFoundException("Stored file was not found");
        } catch (ObjectStorageException exception) {
            throw new ServiceUnavailableException("Private object storage is unavailable");
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
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            deletionRetryQueue.enqueueAndProcessTenant(workspaceId, key);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != TransactionSynchronization.STATUS_COMMITTED) {
                    deletionRetryQueue.enqueueAndProcessTenant(workspaceId, key);
                }
            }
        });
    }

    private void deleteUserOnRollback(String key) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            deletionRetryQueue.enqueueAndProcessUser(key);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != TransactionSynchronization.STATUS_COMMITTED) {
                    deletionRetryQueue.enqueueAndProcessUser(key);
                }
            }
        });
    }

    private void deleteTenantAfterCommit(int workspaceId, String key) {
        deletionRetryQueue.enqueueTenantInCurrentTransaction(workspaceId, key);
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            deletionRetryQueue.processTenant(workspaceId, key);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                deletionRetryQueue.processTenant(workspaceId, key);
            }
        });
    }

    private void deleteUserAfterCommit(String key) {
        deletionRetryQueue.enqueueUserInCurrentTransaction(key);
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            deletionRetryQueue.processUser(key);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                deletionRetryQueue.processUser(key);
            }
        });
    }

    private void deleteTenantAfterCompletion(int workspaceId, String key) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            deletionRetryQueue.enqueueAndProcessTenant(workspaceId, key);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                deletionRetryQueue.enqueueAndProcessTenant(workspaceId, key);
            }
        });
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
        String id = UUID.randomUUID().toString();
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

    private record ReadinessSnapshot(boolean ready, long checkedAtNanos) {}

}
