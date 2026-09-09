package ooo.klae.connex.backend.mappers;

import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import ooo.klae.connex.backend.beans.Attachment;

/** Tenant-scoped scan decisions and durable execution state on attachment metadata. */
public interface AttachmentScanMapper {
    Attachment getById(@Param("workspaceId") int workspaceId, @Param("id") int id);
    Attachment lockById(@Param("workspaceId") int workspaceId, @Param("id") int id);
    boolean isReadable(@Param("workspaceId") int workspaceId, @Param("url") String url,
        @Param("allowDisabledProof") boolean allowDisabledProof);
    int quarantine(@Param("workspaceId") int workspaceId, @Param("id") int id);
    int enqueue(@Param("workspaceId") int workspaceId, @Param("id") int id);
    /** Seeks one workspace identifier in the scheduler's pinned catalog without reading its corpus. */
    Integer nextWorkspaceId(@Param("afterId") int afterId, @Param("throughId") int throughId);
    /** Captures the catalog cycle ceiling so new arrivals cannot delay wraparound indefinitely. */
    Integer lastWorkspaceId();
    List<Integer> findDue(@Param("workspaceId") int workspaceId, @Param("limit") int limit);
    boolean isClaimable(@Param("workspaceId") int workspaceId, @Param("url") String url);
    int claim(@Param("workspaceId") int workspaceId, @Param("url") String url,
        @Param("owner") String owner);
    int decide(@Param("workspaceId") int workspaceId, @Param("url") String url,
        @Param("owner") String owner, @Param("state") String state,
        @Param("version") String version, @Param("signature") String signature,
        @Param("expiresAt") LocalDateTime expiresAt);
    int retry(@Param("workspaceId") int workspaceId, @Param("url") String url,
        @Param("owner") String owner);
    int recordLegacyClean(@Param("workspaceId") int workspaceId, @Param("id") int id,
        @Param("report") ooo.klae.connex.backend.storage.malware.MalwareScanReport report,
        @Param("expiresAt") LocalDateTime expiresAt);
}
