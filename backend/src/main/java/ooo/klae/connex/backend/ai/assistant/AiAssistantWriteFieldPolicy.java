package ooo.klae.connex.backend.ai.assistant;

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
    /** Keys no assistant write tool may declare, whatever its tier. */
    public static final Set<String> NEVER_WRITABLE = Set.of(
            "person.suspendedAt",
            "person.provisionCeasedAt",
            "person.archivedAt",
            "company.archivedAt",
            "person.introExcluded",
            "person.lifecycleStage",
            "person.lifecycleChangedAt",
            "person.disqualifiedReason",
            "workspaceMember.roleId",
            "contactChannelConsent.status",
            "suppressionEntry.address");

    private AiAssistantWriteFieldPolicy() {
    }
}
