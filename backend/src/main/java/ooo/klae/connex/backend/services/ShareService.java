package ooo.klae.connex.backend.services;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.dto.ShareDto;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.services.ShareControlOperations.ShareAccess;
import ooo.klae.connex.backend.services.ShareControlOperations.ShareListControl;
import ooo.klae.connex.backend.services.ShareControlOperations.WorkspaceSnapshot;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

/**
 * Cross-workspace record sharing for companies, contacts, and pipelines. The
 * owning workspace shares a record it owns with another workspace the actor also
 * belongs to; the grantee gains read visibility. Requires the SHARE_MANAGE
 * permission in the owning workspace.
 */
@Service
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@RequiredArgsConstructor
public class ShareService {

    enum Type { COMPANY, PERSON, PIPELINE }

    private static final Comparator<ShareDto> SHARE_ORDER = Comparator
        .comparing(ShareDto::getWorkspaceName, String.CASE_INSENSITIVE_ORDER)
        .thenComparing(ShareDto::getWorkspaceName)
        .thenComparingInt(ShareDto::getWorkspaceId);

    private final ShareTenantOperations tenantOperations;
    private final ShareControlOperations controlOperations;
    private final TenantWorkScope tenantWorkScope;

    public List<ShareDto> listShares(String typeRaw, int entityId) {
        Type type = parseType(typeRaw);
        ShareListControl control = tenantWorkScope.unrouted(controlOperations::prepareList);
        return hydrate(tenantOperations.list(type, control.access().workspaceId(), entityId),
            control.snapshot().names());
    }

    public void share(String typeRaw, int entityId, int targetWorkspaceId, boolean canEdit) {
        Type type = parseType(typeRaw);
        ShareAccess access = tenantWorkScope.unrouted(controlOperations::requireAccess);
        tenantOperations.requireShareableOwned(type, access.workspaceId(), entityId);
        if (targetWorkspaceId == access.workspaceId()) {
            throw new BadRequestException("A record cannot be shared with its own workspace");
        }
        WorkspaceSnapshot snapshot = tenantWorkScope.unrouted(
            () -> controlOperations.prepareTarget(
                access.workspaceId(), access.orgId(), targetWorkspaceId, access.actorId()));
        tenantOperations.share(type, entityId, access.workspaceId(), targetWorkspaceId,
            snapshot.ids(), access.actorId(), canEdit);
        tenantWorkScope.unrouted(() -> {
            controlOperations.recordShare(type.name().toLowerCase(Locale.ROOT), entityId,
                access.workspaceId(), access.orgId(), targetWorkspaceId);
            return null;
        });
    }

    public void unshare(String typeRaw, int entityId, int targetWorkspaceId) {
        Type type = parseType(typeRaw);
        ShareAccess access = tenantWorkScope.unrouted(controlOperations::requireAccess);
        tenantOperations.unshare(type, entityId, access.workspaceId(), targetWorkspaceId);
        tenantWorkScope.unrouted(() -> {
            controlOperations.recordUnshare(type.name().toLowerCase(Locale.ROOT), entityId,
                access.workspaceId(), access.orgId(), targetWorkspaceId);
            return null;
        });
    }

    private static List<ShareDto> hydrate(List<ShareDto> rows, Map<Integer, String> workspaceNames) {
        List<ShareDto> hydrated = new ArrayList<>();
        for (ShareDto row : rows) {
            String workspaceName = workspaceNames.get(row.getWorkspaceId());
            if (workspaceName != null) {
                row.setWorkspaceName(workspaceName);
                hydrated.add(row);
            }
        }
        hydrated.sort(SHARE_ORDER);
        return List.copyOf(hydrated);
    }

    private static Type parseType(String raw) {
        if (raw == null) {
            throw new BadRequestException("Share type is required");
        }
        try {
            return Type.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("Unknown share type: " + raw);
        }
    }
}
