package ooo.klae.connex.backend.ai.assistant;

import java.util.Map;
import java.util.Set;

/**
 * The {@code kind.field} keys no assistant write tool may ever declare.
 *
 * <p>Processing restriction and archive state, the relationship engines' exclusion flag, the
 * owner-only lifecycle columns, and anything that grants authority or records consent are the
 * member's own decisions and the workspace's legal record; an assistant proposes none of them.
 * {@link AiAssistantWriteToolRegistry} refuses to start when a tool's
 * {@link AiAssistantWriteTool#declaredWritableFields()} intersects this set.
 *
 * <p>This binds a tool's declaration, not its effect. A tool's {@code apply} calls domain-service
 * methods, and a method that also moves one of these columns passes this check; the architecture
 * test's mutator denylist is the partial behavioural guard until writes go through a single
 * allow-listed field path.
 */
public final class AiAssistantWriteFieldPolicy {
    /** The closed record-edit allowlist; provenance and contact channels are never edited. */
    public static final Map<String, Set<String>> EDITABLE_FIELDS = Map.of(
            "person", Set.of("title"),
            "company", Set.of("website", "industry", "address"),
            "deal", Set.of("value", "expectedCloseDate"));

    /** Existing immediate-write prose retains its historical redaction-marker behavior. */
    public static final Map<String, Set<String>> MARKER_TOLERANT_PROSE = Map.of(
            "create_note", Set.of("content", "title"),
            "create_activity", Set.of("subject", "notes"),
            "create_task", Set.of("description"));

    /** Keys no assistant write tool may declare, whatever its tier. */
    public static final Set<String> NEVER_WRITABLE = Set.of(
            "person.suspendedAt",
            "person.provisionCeasedAt",
            "person.archivedAt",
            "company.archivedAt",
            "person.introExcluded",
            "person.riskExcluded",
            "person.qualificationNotes",
            "person.leadSource",
            "person.leadSourceDetail",
            "person.referrerPersonId",
            "person.imageUrl",
            "person.firstRespondedAt",
            "person.firstResponseBreachedAt",
            "company.logoUrl",
            "deal.won",
            "deal.closedAt",
            "deal.actualValue",
            "deal.valueSource",
            "deal.position",
            "person.lifecycleStage",
            "person.lifecycleChangedAt",
            "person.disqualifiedReason",
            "workspaceMember.roleId",
            "contactChannelConsent.status",
            "suppressionEntry.address");

    private AiAssistantWriteFieldPolicy() {
    }
}
