package ooo.klae.connex.backend.ai.assistant;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.ai.assistant.AiSkillCatalog.SkillSpec;
import ooo.klae.connex.backend.dto.AiAssistantSkillDto;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.services.WorkspaceService;
import ooo.klae.connex.backend.tenant.Permission;
import ooo.klae.connex.backend.tenant.RequirePermission;

/**
 * The caller's usable half of the skill catalog, so contextual entry points are drawn from declared
 * server capability rather than a hardcoded client list.
 *
 * <p>Only skills this build can actually execute and whose declared permissions the asking member
 * holds are returned. Offering an entry point the server would then decline is worse than offering
 * none, so the same availability and permission gates the router applies to a live turn are applied
 * here, against the same effective permission set.
 *
 * <p>The catalog itself is static configuration rather than tenant data, but the permission
 * resolution is per workspace member: two members of the same workspace can see different
 * directories, and a member whose role loses a declared permission stops seeing that skill.
 *
 * <p>Listing is gated on {@link Permission#AI_USE} alone and deliberately not on
 * {@link ooo.klae.connex.backend.ai.AiFeatureGate}, matching how the session list is gated. Provider
 * readiness is a property of one workspace's configuration at one moment; refusing the directory
 * when a provider is unconfigured would leave the client unable to describe the surface at all,
 * whereas starting a turn stays fail-closed behind the full gate.
 */
@Service
@RequiredArgsConstructor
public class AiSkillDirectoryService {

    private final AiSkillCatalog skillCatalog;
    private final WorkspaceService workspaceService;

    /**
     * Lists the skills the calling member can run, in the catalog's own deterministic order.
     *
     * @param contextKind declared context kind to filter to, or null for the whole directory
     * @return runnable skills, empty when the member holds none
     * @throws BadRequestException when the context kind is not declared by the catalog
     */
    @Transactional(readOnly = true)
    @RequirePermission(Permission.AI_USE)
    public List<AiAssistantSkillDto> list(String contextKind) {
        String kind = requireDeclaredContext(contextKind);
        Set<Permission> held = workspaceService.permissionsFor(
                workspaceService.getCurrentWorkspaceId(), workspaceService.getCurrentUserId());
        List<AiAssistantSkillDto> entries = new ArrayList<>();
        for (SkillSpec spec : skillCatalog.skills()) {
            if (!spec.available() || !held.containsAll(spec.permissions())) {
                continue;
            }
            if (kind != null && !spec.contextKinds().contains(kind)) {
                continue;
            }
            entries.add(entry(spec));
        }
        return List.copyOf(entries);
    }

    private static AiAssistantSkillDto entry(SkillSpec spec) {
        return new AiAssistantSkillDto(
                spec.key(),
                spec.version(),
                spec.nameKey(),
                spec.descriptionKey(),
                List.copyOf(new TreeSet<>(spec.contextKinds())),
                spec.needsSubject(),
                spec.authority().name().toLowerCase(Locale.ROOT));
    }

    /**
     * Validates the filter against the vocabulary the catalog declares rather than a second copy of
     * it, so a kind introduced by a future skill is accepted the moment that skill is declared. An
     * absent parameter means the whole directory; an empty or unknown one is a client error, because
     * silently widening a filter the client believed it applied is how a surface ends up offering
     * entry points for a record it is not on.
     */
    private String requireDeclaredContext(String contextKind) {
        if (contextKind == null) {
            return null;
        }
        String kind = contextKind.trim().toLowerCase(Locale.ROOT);
        boolean declared = !kind.isEmpty() && skillCatalog.skills().stream()
                .anyMatch(spec -> spec.contextKinds().contains(kind));
        if (!declared) {
            throw new BadRequestException(
                    "Assistant skill context must be a declared skill context kind");
        }
        return kind;
    }
}
