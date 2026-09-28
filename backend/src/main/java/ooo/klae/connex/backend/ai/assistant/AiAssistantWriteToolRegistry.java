package ooo.klae.connex.backend.ai.assistant;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import ooo.klae.connex.backend.ai.assistant.AiAssistantWriteTool.Lock;
import ooo.klae.connex.backend.ai.assistant.AiAssistantWriteTool.TargetLock;
import ooo.klae.connex.backend.tenant.Permission;

/**
 * Indexes the discovered {@link AiAssistantWriteTool} beans in catalog order and refuses to start
 * on any declaration that could make a tool fail at runtime instead.
 *
 * <p>The index follows {@link AiAssistantToolCatalog#writeToolNames()}, never the order Spring
 * discovered the beans in. Construction refuses a duplicate name, a name the catalog does not
 * declare as a write, a tier that disagrees with the catalog, an accepted kind outside the record
 * kinds or disagreeing with the executor's handle check, a kind with no required permission or no
 * lock, a shared person lock on a non-person target, a malformed or never-writable declared field,
 * and a catalog write tool that has neither a bean nor a place on {@link #LEGACY_TOOLS}.
 */
@Component
public class AiAssistantWriteToolRegistry {
    /**
     * Write tools still served by the framework's own per-tool arms while they move onto the SPI.
     *
     * <p>It only ever shrinks: a tool that gains a bean must leave it in the same change, which
     * construction enforces, and {@code AiAssistantWriteToolSpiArchTest} refuses any name added to
     * it.
     */
    static final Set<String> LEGACY_TOOLS = Set.of("assign_owner");

    private static final Set<String> RECORD_KINDS = Set.of("person", "company", "deal");
    private static final Pattern FIELD_KEY = Pattern.compile("[a-z][A-Za-z]*\\.[a-z][A-Za-z]*");

    private final Map<String, AiAssistantWriteTool> tools;

    /**
     * @param catalog the closed tool declaration
     * @param discovered every write-tool bean in the context
     */
    public AiAssistantWriteToolRegistry(
            AiAssistantToolCatalog catalog, List<AiAssistantWriteTool> discovered) {
        Map<String, AiAssistantWriteTool> byName = new LinkedHashMap<>();
        for (AiAssistantWriteTool tool : discovered) {
            String name = tool.name();
            if (byName.putIfAbsent(name, tool) != null) {
                throw refused(name, "is registered twice");
            }
            requireDeclared(catalog, tool);
        }
        Map<String, AiAssistantWriteTool> ordered = new LinkedHashMap<>();
        for (String name : AiAssistantToolCatalog.writeToolNames()) {
            AiAssistantWriteTool tool = byName.get(name);
            if (tool != null) {
                ordered.put(name, tool);
            } else if (!LEGACY_TOOLS.contains(name)) {
                throw refused(name, "is declared in the catalog but has no write-tool bean");
            }
        }
        this.tools = Collections.unmodifiableMap(ordered);
    }

    /**
     * @param name a tool key
     * @return the tool's declared implementation, or empty while it is still a legacy arm
     */
    public Optional<AiAssistantWriteTool> find(String name) {
        return Optional.ofNullable(name == null ? null : tools.get(name));
    }

    /** @return every registered tool, in catalog order */
    public List<AiAssistantWriteTool> tools() {
        return List.copyOf(tools.values());
    }

    private static void requireDeclared(AiAssistantToolCatalog catalog, AiAssistantWriteTool tool) {
        String name = tool.name();
        if (!catalog.isWrite(name)) {
            throw refused(name, "is not a declared assistant write tool");
        }
        if (tool.tier() != catalog.tier(name)) {
            throw refused(name, "declares tier " + tool.tier()
                    + " but the catalog declares " + catalog.tier(name));
        }
        if (LEGACY_TOOLS.contains(name)) {
            throw refused(name, "has a write-tool bean and must leave the legacy ledger");
        }
        if (tool.requestType() == null) {
            throw refused(name, "declares no request type");
        }
        Set<String> kinds = tool.acceptedTargetKinds();
        if (kinds == null || kinds.isEmpty() || !RECORD_KINDS.containsAll(kinds)) {
            throw refused(name, "must accept a non-empty subset of " + RECORD_KINDS);
        }
        if (!kinds.equals(AiAssistantToolExecutor.handleKinds(name))) {
            throw refused(name, "accepts " + kinds + " but the executor's handle check accepts "
                    + AiAssistantToolExecutor.handleKinds(name));
        }
        for (String kind : kinds) {
            Set<Permission> permissions = tool.requiredPermissions(kind);
            if (permissions == null || permissions.isEmpty()) {
                throw refused(name, "requires no permission for " + kind);
            }
            Lock lock = tool.lock(kind);
            if (lock == null) {
                throw refused(name, "declares no lock for " + kind);
            }
            if (lock.target() == TargetLock.PERSON_SHARE && !"person".equals(kind)) {
                throw refused(name, "declares a shared person lock for " + kind);
            }
        }
        Set<String> fields = tool.declaredWritableFields();
        if (fields == null) {
            throw refused(name, "declares no writable fields");
        }
        for (String field : fields) {
            if (!FIELD_KEY.matcher(field).matches()) {
                throw refused(name, "declares malformed writable field " + field);
            }
        }
        Set<String> forbidden = new HashSet<>(fields);
        forbidden.retainAll(AiAssistantWriteFieldPolicy.NEVER_WRITABLE);
        if (!forbidden.isEmpty()) {
            throw refused(name, "declares never-writable fields " + forbidden);
        }
    }

    private static IllegalStateException refused(String name, String reason) {
        return new IllegalStateException("Assistant write tool " + name + " " + reason);
    }
}
