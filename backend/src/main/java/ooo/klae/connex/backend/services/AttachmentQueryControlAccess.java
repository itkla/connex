package ooo.klae.connex.backend.services;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.stereotype.Component;
import lombok.RequiredArgsConstructor;
import tools.jackson.databind.ObjectMapper;
import ooo.klae.connex.backend.dto.AttachmentQueryUserFact;
import ooo.klae.connex.backend.exceptions.TenantScopeSnapshotException;
import ooo.klae.connex.backend.mappers.UserMapper;
import ooo.klae.connex.backend.tenant.TenantWorkScope;

/**
 * Loads only tenant-derived attachment user facts in batches of 1,000. Library targets deliberately
 * retain the legacy unrestricted global-name lookup (#2199), distinct from active-member direct-read
 * hydration. In single-database transactions unroutedRead retains the same default-catalog connection
 * and InnoDB read view. Routed transactional reads fail before suspension or another checkout (#2200).
 * LIKE facts use the source column's collation; JSON names have a binary collation solely for
 * projection and null classification, avoiding a dependency on the connection's default collation.
 */
@Component
@RequiredArgsConstructor
public class AttachmentQueryControlAccess {
    private static final int USER_BATCH_SIZE = 1_000;
    private final UserMapper userMapper;
    private final TenantWorkScope tenantWorkScope;
    private final ObjectMapper objectMapper;

    /** Returns complete JSON facts, never a truncated or partially successful candidate set. */
    public String userFactsJson(List<Integer> candidateIds, String query) {
        tenantWorkScope.requireScopePreparationAllowed("attachment user facts");
        List<Integer> ordered = List.copyOf(new TreeSet<>(Objects.requireNonNull(candidateIds)));
        if (ordered.isEmpty()) {
            return "[]";
        }
        List<AttachmentQueryUserFact> facts = tenantWorkScope.unroutedRead(() -> load(ordered, query));
        return objectMapper.writeValueAsString(facts);
    }

    private List<AttachmentQueryUserFact> load(List<Integer> ids, String query) {
        Map<Integer, AttachmentQueryUserFact> facts = new HashMap<>();
        for (int from = 0; from < ids.size(); from += USER_BATCH_SIZE) {
            List<Integer> batch = ids.subList(from, Math.min(ids.size(), from + USER_BATCH_SIZE));
            Set<Integer> allowed = Set.copyOf(batch);
            List<AttachmentQueryUserFact> rows = Objects.requireNonNull(
                userMapper.getAttachmentQueryFactsByIds(batch, query), "attachment user facts");
            for (AttachmentQueryUserFact row : rows) {
                if (row == null || !allowed.contains(row.id()) || row.displayName() == null
                        || facts.putIfAbsent(row.id(), row) != null) {
                    throw new TenantScopeSnapshotException("Attachment user facts escaped their candidate batch");
                }
            }
        }
        List<AttachmentQueryUserFact> ordered = new ArrayList<>();
        for (int id : ids) {
            if (facts.containsKey(id)) {
                ordered.add(facts.get(id));
            }
        }
        return List.copyOf(ordered);
    }
}
