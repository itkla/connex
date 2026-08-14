package ooo.klae.connex.backend.services;

import java.util.Objects;
import java.util.Set;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.beans.Attachment;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.AiChatMapper;
import ooo.klae.connex.backend.mappers.AttachmentMapper;
import ooo.klae.connex.backend.mappers.CompanyMapper;
import ooo.klae.connex.backend.mappers.DealMapper;
import ooo.klae.connex.backend.mappers.NoteMapper;
import ooo.klae.connex.backend.mappers.PersonMapper;
import ooo.klae.connex.backend.storage.ManagedObjectService;
import ooo.klae.connex.backend.storage.ManagedObjectService.StoredBinary;
import ooo.klae.connex.backend.storage.UploadPolicy.UploadPurpose;
import ooo.klae.connex.backend.storage.UploadSource;

/** Isolates attachment persistence in a proxied tenant transaction before normal label hydration. */
@Component
@RequiredArgsConstructor
public class AttachmentWriteOperations {
    private static final Set<String> AUDIT_FIELDS =
        Set.of("fileName", "entityType", "entityId", "url", "contentType", "size");

    private final AttachmentMapper attachmentMapper;
    private final AiChatMapper aiChatMapper;
    private final CompanyMapper companyMapper;
    private final PersonMapper personMapper;
    private final DealMapper dealMapper;
    private final NoteMapper noteMapper;
    private final AuditService auditService;
    private final ManagedObjectService managedObjectService;

    /** Persists an externally stored attachment after tenant target validation. */
    @Transactional
    public Attachment createExternal(int workspaceId, Attachment attachment) {
        requireTenantTarget(workspaceId, attachment.getEntityType(), attachment.getEntityId());
        return persist(workspaceId, attachment, false);
    }

    /** Persists a trusted managed-object reference after tenant target validation. */
    @Transactional
    public Attachment createManaged(int workspaceId, Attachment attachment) {
        requireTenantTarget(workspaceId, attachment.getEntityType(), attachment.getEntityId());
        return persist(workspaceId, attachment, true);
    }

    /** Stores and persists a managed attachment in one tenant transaction. */
    @Transactional
    public Attachment upload(
            int workspaceId, String entityType, int entityId, UploadSource source, User uploader) {
        requireTenantTarget(workspaceId, entityType, entityId);
        return storeAndPersist(
            workspaceId, entityType, entityId, source, uploader, UploadPurpose.ATTACHMENT);
    }

    /** Stores and persists a managed inline image in one tenant transaction. */
    @Transactional
    public Attachment uploadInlineImage(
            int workspaceId, String entityType, int entityId, UploadSource source, User uploader) {
        requireTenantTarget(workspaceId, entityType, entityId);
        return storeAndPersist(
            workspaceId, entityType, entityId, source, uploader, UploadPurpose.INLINE_IMAGE);
    }

    /** Stores a managed assistant attachment after the caller has locked and authorized its session. */
    @Transactional
    public Attachment uploadAssistantSession(
            int workspaceId, int sessionId, UploadSource source, User uploader) {
        if (!aiChatMapper.sessionExists(workspaceId, sessionId)) {
            throw new ResourceNotFoundException("Attachment target was not found");
        }
        return storeAndPersist(
            workspaceId,
            "ai_chat_session",
            sessionId,
            source,
            uploader,
            UploadPurpose.ASSISTANT_CONTEXT);
    }

    private Attachment storeAndPersist(
            int workspaceId,
            String entityType,
            int entityId,
            UploadSource source,
            User uploader,
            UploadPurpose purpose) {
        StoredBinary stored = managedObjectService.storeAttachment(workspaceId, purpose, source);
        Attachment attachment = new Attachment();
        attachment.setWorkspaceId(workspaceId);
        attachment.setEntityType(entityType);
        attachment.setEntityId(entityId);
        attachment.setFileName(stored.fileName());
        attachment.setUrl(stored.url());
        attachment.setContentType(stored.contentType());
        attachment.setSize(stored.size());
        attachment.setUploadedBy(uploader);
        return persist(workspaceId, attachment, true);
    }

    private Attachment persist(int workspaceId, Attachment attachment, boolean managed) {
        attachment.setWorkspaceId(workspaceId);
        validateUrl(attachment.getUrl());
        if (managed && attachmentMapper.countUrl(workspaceId, attachment.getUrl()) > 0) {
            throw new BadRequestException("That managed attachment reference is already in use");
        }
        if (!managed && attachmentMapper.countUrlInOtherWorkspaces(workspaceId, attachment.getUrl()) > 0) {
            throw new BadRequestException("That attachment url is already in use");
        }
        attachmentMapper.insert(attachment);
        auditService.record("attachment.create", "attachment", attachment.getId(), attachment.getFileName(),
            "Uploaded attachment " + attachment.getFileName(),
            auditService.diff(null, attachment, AUDIT_FIELDS));
        Attachment created = attachmentMapper.getCreatedById(workspaceId, attachment.getId());
        requirePersistedShape(workspaceId, attachment, created);
        return created;
    }

    private void requirePersistedShape(
            int workspaceId, Attachment expected, Attachment created) {
        Integer expectedUploaderId = expected.getUploadedBy() == null
            ? null
            : expected.getUploadedBy().getId();
        Integer createdUploaderId = created == null || created.getUploadedBy() == null
            ? null
            : created.getUploadedBy().getId();
        if (created == null
                || created.getWorkspaceId() != workspaceId
                || created.getId() != expected.getId()
                || !Objects.equals(created.getEntityType(), expected.getEntityType())
                || created.getEntityId() != expected.getEntityId()
                || !Objects.equals(created.getUrl(), expected.getUrl())
                || !Objects.equals(createdUploaderId, expectedUploaderId)) {
            throw new IllegalStateException("Created attachment could not be reloaded safely");
        }
    }

    private void requireTenantTarget(int workspaceId, String entityType, int entityId) {
        boolean exists = switch (entityType) {
            case "company" -> companyMapper.exists(workspaceId, entityId);
            case "person" -> personMapper.exists(workspaceId, entityId);
            case "deal" -> dealMapper.exists(workspaceId, entityId);
            case "note" -> noteMapper.exists(workspaceId, entityId);
            case "user" -> true;
            default -> throw new BadRequestException("Unsupported attachment entity type");
        };
        if (!exists) {
            throw new ResourceNotFoundException("Attachment target was not found");
        }
    }

    private void validateUrl(String url) {
        if (url == null || url.chars().anyMatch(c -> c < 0x20 || c == 0x7F)
                || url.startsWith("//") || url.startsWith("/\\")
                || !(url.startsWith("/")
                    || url.regionMatches(true, 0, "http://", 0, 7)
                    || url.regionMatches(true, 0, "https://", 0, 8))) {
            throw new BadRequestException(
                "Attachment url must be an app-relative path or an http(s) URL");
        }
    }
}
