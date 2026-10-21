package ooo.klae.connex.backend.support;

import java.util.ArrayList;
import ooo.klae.connex.backend.dto.AttachmentQueryUserFact;
import ooo.klae.connex.backend.mappers.AttachmentMapper;
import ooo.klae.connex.backend.mappers.UserMapper;
import tools.jackson.databind.json.JsonMapper;

/** Supplies complete facts to direct mapper tests on their existing default-catalog transaction. */
public final class AttachmentQueryFacts {
    private AttachmentQueryFacts() { }

    /** Preserves the direct mapper fixture's snapshot while adapting only its parameter signature. */
    public static String json(AttachmentMapper attachments, UserMapper users, int workspaceId,
            int currentUserId, String query) {
        var ids = attachments.getQueryUserIds(workspaceId, currentUserId, true, true);
        var facts = new ArrayList<AttachmentQueryUserFact>();
        for (int from = 0; from < ids.size(); from += 1_000) {
            facts.addAll(users.getAttachmentQueryFactsByIds(ids.subList(from, Math.min(ids.size(), from + 1_000)), query));
        }
        return JsonMapper.builder().build().writeValueAsString(facts);
    }
}
