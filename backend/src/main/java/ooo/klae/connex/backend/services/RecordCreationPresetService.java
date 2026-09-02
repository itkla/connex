package ooo.klae.connex.backend.services;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

import ooo.klae.connex.backend.beans.RecordCreationTemplate;
import ooo.klae.connex.backend.beans.RecordCreationTemplateSet;
import ooo.klae.connex.backend.beans.RecordCreationTemplateVersion;
import ooo.klae.connex.backend.dto.recordcreation.RecordCreationContextDto;
import ooo.klae.connex.backend.dto.recordcreation.RecordCreationPresetCatalogDto;
import ooo.klae.connex.backend.dto.recordcreation.RecordCreationWarningDto;
import ooo.klae.connex.backend.dto.recordcreation.ResolvedCreationTemplateDto;
import ooo.klae.connex.backend.exceptions.RecordCreationTemplateException;
import ooo.klae.connex.backend.mappers.RecordCreationTemplateMapper;
import ooo.klae.connex.backend.recordcreation.RecordCreationEntryPoint;
import ooo.klae.connex.backend.recordcreation.RecordCreationFieldRegistry;
import ooo.klae.connex.backend.recordcreation.RecordCreationRecordType;
import ooo.klae.connex.backend.recordcreation.RecordCreationTemplateAvailability;
import ooo.klae.connex.backend.recordcreation.RecordCreationTemplateStatus;
import ooo.klae.connex.backend.tenant.Permission;
import ooo.klae.connex.backend.tenant.RequirePermission;

@Service
@RequiredArgsConstructor
public class RecordCreationPresetService {

    record ResolutionSnapshot(
        int setRevision,
        String selectedTemplateId,
        List<RecordCreationTemplate> roots,
        Map<Integer, RecordCreationTemplateVersion> versions,
        Map<Integer, ResolvedCreationTemplateDto> resolved,
        ResolvedCreationTemplateDto system,
        List<ResolvedCreationTemplateDto> runtimeTemplates,
        List<RecordCreationWarningDto> warnings,
        boolean partial
    ) {
    }

    private final RecordCreationTemplateMapper templateMapper;
    private final RecordCreationTemplateResolver resolver;
    private final RecordCreationFieldRegistry fieldRegistry;
    private final WorkspaceService workspaceService;

    @RequirePermission(Permission.PERSON_CREATE)
    public RecordCreationPresetCatalogDto persons(
            RecordCreationEntryPoint entryPoint,
            Integer relatedCompanyId) {
        return catalog(
            RecordCreationRecordType.person,
            entryPoint,
            new RecordCreationContextDto(relatedCompanyId));
    }

    @RequirePermission(Permission.COMPANY_CREATE)
    public RecordCreationPresetCatalogDto companies(RecordCreationEntryPoint entryPoint) {
        return catalog(
            RecordCreationRecordType.company,
            entryPoint,
            new RecordCreationContextDto(null));
    }

    @RequirePermission(Permission.DEAL_CREATE)
    public RecordCreationPresetCatalogDto deals(
            RecordCreationEntryPoint entryPoint,
            Integer relatedCompanyId) {
        return catalog(
            RecordCreationRecordType.deal,
            entryPoint,
            new RecordCreationContextDto(relatedCompanyId));
    }

    RecordCreationPresetCatalogDto catalog(
            RecordCreationRecordType recordType,
            RecordCreationEntryPoint entryPoint,
            RecordCreationContextDto context) {
        Objects.requireNonNull(entryPoint, "entryPoint");
        ResolutionSnapshot snapshot = snapshot(recordType, context, false, null);
        return new RecordCreationPresetCatalogDto(
            recordType,
            entryPoint,
            snapshot.setRevision(),
            snapshot.selectedTemplateId(),
            snapshot.runtimeTemplates(),
            snapshot.partial(),
            snapshot.warnings());
    }

    ResolutionSnapshot snapshot(
            RecordCreationRecordType recordType,
            RecordCreationContextDto context,
            boolean includeArchived,
            Integer excludedRootId) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        RecordCreationTemplateSet set = templateMapper.getSet(workspaceId, recordType.name());
        int setRevision = set == null ? 0 : set.getRevision();
        List<RecordCreationTemplate> roots = templateMapper.listRoots(
            workspaceId, recordType.name(), includeArchived).stream()
            .sorted(Comparator.comparingInt(RecordCreationTemplate::getPosition)
                .thenComparingInt(RecordCreationTemplate::getId))
            .toList();
        Map<Integer, RecordCreationTemplateVersion> versions = new LinkedHashMap<>();
        Map<Integer, ResolvedCreationTemplateDto> resolved = new LinkedHashMap<>();
        for (RecordCreationTemplate root : roots) {
            RecordCreationTemplateVersion version =
                templateMapper.getCurrentVersion(workspaceId, root.getId());
            versions.put(root.getId(), version);
            if (version != null) {
                resolved.put(root.getId(), resolver.resolveWorkspace(root, version, context));
            }
        }
        ResolvedCreationTemplateDto system = resolver.resolveSystem(recordType, context);
        String selected = selected(set, roots, resolved, system, excludedRootId);
        List<ResolvedCreationTemplateDto> runtimeTemplates = new ArrayList<>();
        List<RecordCreationWarningDto> warnings = new ArrayList<>();
        boolean partial = false;
        for (RecordCreationTemplate root : roots) {
            if (Objects.equals(excludedRootId, root.getId())
                    || !RecordCreationTemplateStatus.enabled.name().equals(root.getStatus())) {
                continue;
            }
            ResolvedCreationTemplateDto template = resolved.get(root.getId());
            if (template == null) {
                warnings.add(new RecordCreationWarningDto(
                    "TEMPLATE_UNAVAILABLE", wireId(root), null, null));
                partial = true;
                continue;
            }
            runtimeTemplates.add(template);
            warnings.addAll(template.warnings());
            partial |= template.availability() != RecordCreationTemplateAvailability.available;
        }
        runtimeTemplates.add(system);
        warnings.addAll(system.warnings());
        partial |= system.availability() != RecordCreationTemplateAvailability.available;
        partial |= !warnings.isEmpty();
        return new ResolutionSnapshot(
            setRevision,
            selected,
            List.copyOf(roots),
            java.util.Collections.unmodifiableMap(new LinkedHashMap<>(versions)),
            Map.copyOf(resolved),
            system,
            List.copyOf(runtimeTemplates),
            List.copyOf(warnings),
            partial);
    }

    String selectedTemplateId(
            RecordCreationRecordType recordType,
            RecordCreationContextDto context,
            Integer excludedRootId) {
        return snapshot(recordType, context, false, excludedRootId).selectedTemplateId();
    }

    private String selected(
            RecordCreationTemplateSet set,
            List<RecordCreationTemplate> roots,
            Map<Integer, ResolvedCreationTemplateDto> resolved,
            ResolvedCreationTemplateDto system,
            Integer excludedRootId) {
        if (set != null && set.getDefaultTemplateId() != null
                && !Objects.equals(excludedRootId, set.getDefaultTemplateId())) {
            RecordCreationTemplate explicitRoot = roots.stream()
                .filter(root -> root.getId() == set.getDefaultTemplateId())
                .findFirst()
                .orElse(null);
            ResolvedCreationTemplateDto explicit = resolved.get(set.getDefaultTemplateId());
            if (explicitRoot != null
                    && RecordCreationTemplateStatus.enabled.name().equals(explicitRoot.getStatus())
                    && explicit != null
                    && explicit.availability() == RecordCreationTemplateAvailability.available) {
                return explicit.id();
            }
        }
        for (RecordCreationTemplate root : roots) {
            if (Objects.equals(excludedRootId, root.getId())
                    || !RecordCreationTemplateStatus.enabled.name().equals(root.getStatus())) {
                continue;
            }
            ResolvedCreationTemplateDto template = resolved.get(root.getId());
            if (template != null
                    && template.availability() == RecordCreationTemplateAvailability.available) {
                return template.id();
            }
        }
        if (system.availability() == RecordCreationTemplateAvailability.available) {
            return fieldRegistry.systemPreset(system.recordType()).id();
        }
        throw RecordCreationTemplateException.of(
            HttpStatus.SERVICE_UNAVAILABLE,
            "TEMPLATE_CATALOG_UNAVAILABLE",
            "The template catalog could not be loaded safely");
    }

    private static String wireId(RecordCreationTemplate root) {
        return "workspace:" + root.getId();
    }
}
