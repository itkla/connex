package ooo.klae.connex.backend.services;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.beans.Attachment;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.dto.UserDisplayNameDto;
import ooo.klae.connex.backend.exceptions.ResourceNotFoundException;
import ooo.klae.connex.backend.mappers.AttachmentMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

/** Resolves control-plane user labels after workspace-scoped attachment reads complete. */
@Service
@RequiredArgsConstructor
public class AttachmentReadService {
    private static final int USER_BATCH_SIZE = 1_000;

    private final AttachmentMapper attachmentMapper;
    private final UserMapper userMapper;
    private final TenantWorkScope tenantWorkScope;
    private final AuthService authService;

    /** Returns attachments for one tenant-owned entity with user labels hydrated separately. */
    public List<Attachment> getByEntity(
            int workspaceId, String entityType, int entityId) {
        return hydrate(workspaceId,
            attachmentMapper.getByEntity(workspaceId, entityType, entityId));
    }

    /** Returns all attachments visible to one workspace member with user labels hydrated separately. */
    public List<Attachment> getAll(int workspaceId, int currentUserId) {
        return hydrate(workspaceId, attachmentMapper.getAll(workspaceId, currentUserId));
    }

    /** Returns one workspace-owned attachment with user labels hydrated separately. */
    public Attachment getById(int workspaceId, int id) {
        return hydrate(workspaceId, attachmentMapper.getById(workspaceId, id));
    }

    /** Returns one workspace-owned attachment for an exact URL with user labels hydrated separately. */
    public Attachment getByUrl(int workspaceId, String url) {
        return hydrate(workspaceId, attachmentMapper.getByUrl(workspaceId, url));
    }

    Attachment hydrate(int workspaceId, Attachment attachment) {
        if (attachment == null) {
            return null;
        }
        hydrate(workspaceId, List.of(attachment));
        return attachment;
    }

    List<Attachment> hydrate(int workspaceId, List<Attachment> attachments) {
        Objects.requireNonNull(attachments, "attachments");
        validateWorkspace(workspaceId, attachments);
        if (attachments.isEmpty()) {
            return attachments;
        }
        Set<Integer> uploaderIds = uploaderIds(attachments);
        Set<Integer> targetIds = userTargetIds(attachments);
        if (uploaderIds.isEmpty() && targetIds.isEmpty()) {
            return attachments;
        }
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            hydrateFromCurrentPrincipal(attachments, uploaderIds, targetIds);
            return attachments;
        }
        Map<Integer, String> uploaderNames = uploaderIds.isEmpty()
            ? Map.of()
            : tenantWorkScope.unrouted(
                () -> loadDisplayNames(uploaderIds, userMapper::getDisplayNamesByIds));
        Map<Integer, String> targetNames = targetIds.isEmpty()
            ? Map.of()
            : tenantWorkScope.unrouted(() -> loadDisplayNames(targetIds, ids ->
                userMapper.getActiveWorkspaceMemberDisplayNamesByIds(workspaceId, ids)));
        applyDisplayNames(attachments, uploaderNames, targetNames);
        return attachments;
    }

    UserDisplayNameDto getActiveWorkspaceMemberLabel(int workspaceId, int userId) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                "User-target attachment validation must precede the tenant transaction");
        }
        List<UserDisplayNameDto> labels = Objects.requireNonNull(
            tenantWorkScope.unrouted(() -> userMapper
                .getActiveWorkspaceMemberDisplayNamesByIds(workspaceId, List.of(userId))),
            "active workspace member label result");
        if (labels.isEmpty()) {
            return null;
        }
        if (labels.size() != 1 || labels.getFirst() == null
                || labels.getFirst().id() != userId
                || labels.getFirst().displayName() == null) {
            throw new IllegalStateException(
                "Active attachment target lookup escaped its requested user");
        }
        return labels.getFirst();
    }

    Attachment hydrateKnown(
            int workspaceId,
            Attachment attachment,
            User knownUploader,
            UserDisplayNameDto knownTarget) {
        validateWorkspace(workspaceId, List.of(attachment));
        Map<Integer, String> uploaderNames = knownUploader == null
            || knownUploader.getDisplayName() == null
            ? Map.of()
            : Map.of(knownUploader.getId(), knownUploader.getDisplayName());
        Map<Integer, String> targetNames = knownTarget == null
            ? Map.of()
            : Map.of(knownTarget.id(), knownTarget.displayName());
        applyDisplayNames(List.of(attachment), uploaderNames, targetNames);
        return attachment;
    }

    private void validateWorkspace(int workspaceId, List<Attachment> attachments) {
        for (Attachment attachment : attachments) {
            if (attachment == null || attachment.getWorkspaceId() != workspaceId) {
                throw new IllegalStateException(
                    "Attachment hydration received a row outside its workspace");
            }
        }
    }

    private Set<Integer> uploaderIds(List<Attachment> attachments) {
        Set<Integer> uploaderIds = new TreeSet<>();
        for (Attachment attachment : attachments) {
            if (attachment.getUploadedBy() != null) {
                uploaderIds.add(attachment.getUploadedBy().getId());
            }
        }
        return uploaderIds;
    }

    private Set<Integer> userTargetIds(List<Attachment> attachments) {
        Set<Integer> targetIds = new TreeSet<>();
        for (Attachment attachment : attachments) {
            if ("user".equals(attachment.getEntityType())) {
                targetIds.add(attachment.getEntityId());
            }
        }
        return targetIds;
    }

    private Map<Integer, String> loadDisplayNames(
            Set<Integer> candidateIds,
            Function<List<Integer>, List<UserDisplayNameDto>> lookup) {
        List<Integer> orderedIds = new ArrayList<>(candidateIds);
        Map<Integer, String> displayNames = new HashMap<>();
        for (int from = 0; from < orderedIds.size(); from += USER_BATCH_SIZE) {
            int to = Math.min(orderedIds.size(), from + USER_BATCH_SIZE);
            List<Integer> batch = orderedIds.subList(from, to);
            Set<Integer> batchIds = Set.copyOf(batch);
            List<UserDisplayNameDto> labels = Objects.requireNonNull(
                lookup.apply(batch), "display-name lookup result");
            for (UserDisplayNameDto label : labels) {
                if (label == null || !batchIds.contains(label.id())) {
                    throw new IllegalStateException(
                        "Control-plane attachment label escaped its tenant-derived batch");
                }
                String displayName = Objects.requireNonNull(
                    label.displayName(), "display-name lookup value");
                String previous = displayNames.putIfAbsent(label.id(), displayName);
                if (previous != null && !previous.equals(displayName)) {
                    throw new IllegalStateException(
                        "Control-plane attachment labels disagreed for one user");
                }
            }
        }
        return displayNames;
    }

    private void hydrateFromCurrentPrincipal(
            List<Attachment> attachments, Set<Integer> uploaderIds, Set<Integer> targetIds) {
        User principal;
        try {
            principal = authService.getCurrentPrincipal();
        } catch (ResourceNotFoundException exception) {
            throw new IllegalStateException(
                "Attachment labels cannot be resolved inside an unauthenticated transaction",
                exception);
        }
        if (uploaderIds.stream().anyMatch(id -> id != principal.getId())
                || targetIds.stream().anyMatch(id -> id != principal.getId())) {
            throw new IllegalStateException(
                "Attachment labels for another user require a transaction-free read boundary");
        }
        Map<Integer, String> displayNames = Map.of(
            principal.getId(), Objects.requireNonNull(principal.getDisplayName()));
        applyDisplayNames(attachments, displayNames, displayNames);
    }

    private void applyDisplayNames(
            List<Attachment> attachments,
            Map<Integer, String> uploaderNames,
            Map<Integer, String> targetNames) {
        for (Attachment attachment : attachments) {
            User uploader = attachment.getUploadedBy();
            if (uploader != null) {
                uploader.setDisplayName(uploaderNames.get(uploader.getId()));
            }
            if ("user".equals(attachment.getEntityType())) {
                attachment.setEntityLabel(targetNames.get(attachment.getEntityId()));
            }
        }
    }
}
