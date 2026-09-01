package ooo.klae.connex.backend.services;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;

import ooo.klae.connex.backend.beans.CustomFieldDefinition;
import ooo.klae.connex.backend.beans.Pipeline;
import ooo.klae.connex.backend.beans.RecordCreationTemplate;
import ooo.klae.connex.backend.beans.RecordCreationTemplateVersion;
import ooo.klae.connex.backend.beans.Stage;
import ooo.klae.connex.backend.beans.Tag;
import ooo.klae.connex.backend.dto.CustomFieldOption;
import ooo.klae.connex.backend.dto.recordcreation.CreationFieldOptionDto;
import ooo.klae.connex.backend.dto.recordcreation.LocalizedTextDto;
import ooo.klae.connex.backend.dto.recordcreation.RecordCreationContextDto;
import ooo.klae.connex.backend.dto.recordcreation.RecordCreationDefaultSpecDto;
import ooo.klae.connex.backend.dto.recordcreation.RecordCreationTemplateDefinitionDto;
import ooo.klae.connex.backend.dto.recordcreation.RecordCreationTemplateFieldDto;
import ooo.klae.connex.backend.dto.recordcreation.RecordCreationTemplateGroupDto;
import ooo.klae.connex.backend.dto.recordcreation.RecordCreationWarningDto;
import ooo.klae.connex.backend.dto.recordcreation.ResolvedCreationFieldDto;
import ooo.klae.connex.backend.dto.recordcreation.ResolvedCreationGroupDto;
import ooo.klae.connex.backend.dto.recordcreation.ResolvedCreationTemplateDto;
import ooo.klae.connex.backend.exceptions.RecordCreationTemplateException;
import ooo.klae.connex.backend.mappers.CompanyMapper;
import ooo.klae.connex.backend.mappers.CustomFieldDefinitionMapper;
import ooo.klae.connex.backend.mappers.PipelineMapper;
import ooo.klae.connex.backend.mappers.TagMapper;
import ooo.klae.connex.backend.recordcreation.RecordCreationDefaultKind;
import ooo.klae.connex.backend.recordcreation.RecordCreationDefaultOrigin;
import ooo.klae.connex.backend.recordcreation.RecordCreationFieldRegistry;
import ooo.klae.connex.backend.recordcreation.RecordCreationFieldRegistry.CustomTypeDefinition;
import ooo.klae.connex.backend.recordcreation.RecordCreationFieldRegistry.FieldDefinition;
import ooo.klae.connex.backend.recordcreation.RecordCreationFieldRegistry.SystemPreset;
import ooo.klae.connex.backend.recordcreation.RecordCreationFieldSource;
import ooo.klae.connex.backend.recordcreation.RecordCreationRecordType;
import ooo.klae.connex.backend.recordcreation.RecordCreationTemplateAvailability;

@Service
@RequiredArgsConstructor
public class RecordCreationTemplateResolver {

    private record ResolutionContext(
        int workspaceId,
        int actorId,
        RecordCreationRecordType recordType,
        Integer relatedCompanyId,
        Map<Integer, CustomFieldDefinition> customFields,
        Pipeline policyPipeline,
        Stage policyStage,
        List<Pipeline> pipelines,
        List<Stage> stages,
        List<Tag> tags
    ) {
    }

    private record DefaultValue(JsonNode value, RecordCreationDefaultOrigin origin) {
    }

    private static final Pattern CUSTOM_KEY = Pattern.compile("^custom:([1-9][0-9]{0,9})$");
    private static final List<String> LEAD_SOURCES = List.of(
        "REFERRAL", "EVENT", "WEB", "OUTBOUND", "BUSINESS_CARD", "IMPORT", "PARTNER", "OTHER");

    private final RecordCreationFieldRegistry fieldRegistry;
    private final RecordCreationTemplateValidator validator;
    private final CustomFieldDefinitionMapper customFieldMapper;
    private final CompanyMapper companyMapper;
    private final PipelineMapper pipelineMapper;
    private final TagMapper tagMapper;
    private final WorkspaceService workspaceService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public ResolvedCreationTemplateDto resolveSystem(
            RecordCreationRecordType recordType,
            RecordCreationContextDto context) {
        SystemPreset preset = fieldRegistry.systemPreset(recordType);
        return resolve(
            preset.id(),
            recordType,
            true,
            preset.version(),
            preset.name(),
            preset.description(),
            preset.definition(),
            context);
    }

    public ResolvedCreationTemplateDto resolveWorkspace(
            RecordCreationTemplate root,
            RecordCreationTemplateVersion version,
            RecordCreationContextDto context) {
        RecordCreationRecordType recordType = RecordCreationRecordType.valueOf(root.getRecordType());
        return resolve(
            "workspace:" + root.getId(),
            recordType,
            false,
            version.getVersionNumber(),
            new LocalizedTextDto(version.getNameEn(), version.getNameJa()),
            localized(version.getDescriptionEn(), version.getDescriptionJa()),
            validator.parseDefinition(version.getDefinitionJson()),
            context);
    }

    public ResolvedCreationTemplateDto resolvePreview(
            String id,
            RecordCreationRecordType recordType,
            LocalizedTextDto name,
            LocalizedTextDto description,
            RecordCreationTemplateDefinitionDto definition,
            RecordCreationContextDto context) {
        return resolve(id, recordType, false, 0, name, description, definition, context);
    }

    private ResolvedCreationTemplateDto resolve(
            String id,
            RecordCreationRecordType recordType,
            boolean system,
            int version,
            LocalizedTextDto name,
            LocalizedTextDto description,
            RecordCreationTemplateDefinitionDto definition,
            RecordCreationContextDto requestedContext) {
        ResolutionContext context = resolutionContext(recordType, requestedContext);
        List<RecordCreationWarningDto> warnings = new ArrayList<>();
        List<ResolvedCreationGroupDto> groups = new ArrayList<>();
        Set<String> configuredKeys = new HashSet<>();
        boolean unavailable = false;
        for (RecordCreationTemplateGroupDto group : definition.groups()) {
            List<ResolvedCreationFieldDto> fields = new ArrayList<>();
            for (RecordCreationTemplateFieldDto field : group.fields()) {
                configuredKeys.add(field.key());
                ResolvedCreationFieldDto resolved = resolveField(id, field, context, warnings);
                if (resolved == null) {
                    unavailable = true;
                } else {
                    fields.add(resolved);
                }
            }
            groups.add(new ResolvedCreationGroupDto(
                group.key(), group.label(), group.description(), List.copyOf(fields)));
        }
        List<ResolvedCreationFieldDto> required = missingRequired(id, configuredKeys, context, warnings);
        if (required.stream().anyMatch(java.util.Objects::isNull)) {
            unavailable = true;
            required = required.stream().filter(java.util.Objects::nonNull).toList();
        }
        if (!required.isEmpty()) {
            groups.addFirst(new ResolvedCreationGroupDto(
                "required",
                new LocalizedTextDto("Required", "必須項目"),
                null,
                List.copyOf(required)));
            required.forEach(field -> configuredKeys.add(field.key()));
        }
        List<ResolvedCreationFieldDto> trust = new ArrayList<>();
        for (FieldDefinition field : fieldRegistry.fields(recordType).values()) {
            if (field.protectedField() && !configuredKeys.contains(field.key())) {
                trust.add(resolveCoreField(
                    id,
                    new RecordCreationTemplateFieldDto(field.key(), false, null, null, null),
                    field,
                    context));
            }
        }
        if (!trust.isEmpty()) {
            groups.add(new ResolvedCreationGroupDto(
                "trust",
                new LocalizedTextDto("Trust", "信頼情報"),
                null,
                List.copyOf(trust)));
        }
        return new ResolvedCreationTemplateDto(
            id,
            recordType,
            system,
            version,
            name,
            description,
            unavailable
                ? RecordCreationTemplateAvailability.unavailable
                : RecordCreationTemplateAvailability.available,
            List.copyOf(groups),
            List.copyOf(warnings));
    }

    private ResolutionContext resolutionContext(
            RecordCreationRecordType recordType,
            RecordCreationContextDto requestedContext) {
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        int actorId = workspaceService.getCurrentUserId();
        Integer relatedCompanyId = requestedContext == null ? null : requestedContext.relatedCompanyId();
        if (relatedCompanyId != null) {
            if (recordType == RecordCreationRecordType.company) {
                throw RecordCreationTemplateException.of(
                    HttpStatus.BAD_REQUEST,
                    "TEMPLATE_DEFINITION_INVALID",
                    "Related company context is valid only for contacts and deals");
            }
            if (!companyMapper.exists(workspaceId, relatedCompanyId)) {
                throw RecordCreationTemplateException.of(
                    HttpStatus.NOT_FOUND,
                    "RELATED_RECORD_NOT_FOUND",
                    "Related company was not found");
            }
        }
        Map<Integer, CustomFieldDefinition> customFields = new LinkedHashMap<>();
        for (CustomFieldDefinition definition :
                customFieldMapper.getByEntityType(workspaceId, recordType.name())) {
            if (!definition.isArchived()) {
                customFields.put(definition.getId(), definition);
            }
        }
        List<Pipeline> pipelines = pipelineMapper.getAllPipelines(workspaceId).stream()
            .sorted(Comparator.comparingInt(Pipeline::getId))
            .toList();
        Pipeline policyPipeline = pipelines.isEmpty() ? null : pipelines.getFirst();
        List<Stage> stages = pipelineMapper.getAllStages(workspaceId).stream()
            .sorted(Comparator.comparingInt((Stage stage) ->
                stage.getPipeline() == null ? Integer.MAX_VALUE : stage.getPipeline().getId())
                .thenComparingInt(Stage::getPosition)
                .thenComparingInt(Stage::getId))
            .toList();
        Stage policyStage = policyPipeline == null ? null : stages.stream()
            .filter(stage -> stage.getPipeline() != null
                && stage.getPipeline().getId() == policyPipeline.getId())
            .findFirst()
            .orElse(null);
        List<Tag> tags = tagMapper.getAllTags(workspaceId).stream()
            .sorted(Comparator.comparingInt(Tag::getId))
            .toList();
        return new ResolutionContext(
            workspaceId,
            actorId,
            recordType,
            relatedCompanyId,
            java.util.Collections.unmodifiableMap(customFields),
            policyPipeline,
            policyStage,
            pipelines,
            stages,
            tags);
    }

    private ResolvedCreationFieldDto resolveField(
            String templateId,
            RecordCreationTemplateFieldDto field,
            ResolutionContext context,
            List<RecordCreationWarningDto> warnings) {
        FieldDefinition core = fieldRegistry.field(context.recordType(), field.key());
        if (core != null) {
            return resolveCoreField(templateId, field, core, context);
        }
        java.util.regex.Matcher matcher = CUSTOM_KEY.matcher(field.key());
        if (!matcher.matches()) {
            warnings.add(new RecordCreationWarningDto(
                "TEMPLATE_FIELD_UNAVAILABLE", templateId, field.key(), null));
            return null;
        }
        int customFieldId;
        try {
            customFieldId = Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException exception) {
            warnings.add(new RecordCreationWarningDto(
                "TEMPLATE_FIELD_UNAVAILABLE", templateId, field.key(), null));
            return null;
        }
        CustomFieldDefinition custom = context.customFields().get(customFieldId);
        CustomTypeDefinition type = custom == null ? null : fieldRegistry.customType(custom.getFieldType());
        if (custom == null || type == null) {
            warnings.add(new RecordCreationWarningDto(
                "CUSTOM_FIELD_UNAVAILABLE", templateId, field.key(), customFieldId));
            return null;
        }
        List<CreationFieldOptionDto> options = customOptions(custom);
        if (options == null) {
            warnings.add(new RecordCreationWarningDto(
                "CUSTOM_FIELD_UNAVAILABLE", templateId, field.key(), customFieldId));
            return null;
        }
        DefaultValue defaultValue = resolveDefault(field.key(), field.defaultSpec(), context);
        return new ResolvedCreationFieldDto(
            field.key(),
            RecordCreationFieldSource.custom,
            customFieldId,
            type.valueType(),
            fingerprint(custom),
            new LocalizedTextDto(custom.getLabel(), custom.getLabel()),
            field.helpText(),
            field.placeholder(),
            field.required() || custom.isRequired(),
            custom.isRequired(),
            false,
            defaultValue == null ? null : defaultValue.value(),
            defaultValue == null ? null : defaultValue.origin(),
            options);
    }

    private ResolvedCreationFieldDto resolveCoreField(
            String templateId,
            RecordCreationTemplateFieldDto field,
            FieldDefinition core,
            ResolutionContext context) {
        DefaultValue defaultValue = resolveDefault(field.key(), field.defaultSpec(), context);
        if ("company".equals(field.key()) && context.relatedCompanyId() != null) {
            defaultValue = new DefaultValue(
                objectMapper.valueToTree(context.relatedCompanyId()),
                RecordCreationDefaultOrigin.context);
        }
        if (context.recordType() == RecordCreationRecordType.deal && defaultValue == null) {
            if ("pipeline".equals(field.key()) && context.policyPipeline() != null) {
                defaultValue = new DefaultValue(
                    objectMapper.valueToTree(context.policyPipeline().getId()),
                    RecordCreationDefaultOrigin.policy);
            } else if ("stage".equals(field.key()) && context.policyStage() != null) {
                defaultValue = new DefaultValue(
                    objectMapper.valueToTree(context.policyStage().getId()),
                    RecordCreationDefaultOrigin.policy);
            } else if ("owner".equals(field.key())) {
                defaultValue = new DefaultValue(
                    objectMapper.valueToTree(context.actorId()),
                    RecordCreationDefaultOrigin.policy);
            }
        }
        return new ResolvedCreationFieldDto(
            field.key(),
            RecordCreationFieldSource.system,
            null,
            core.valueType(),
            fingerprint(core),
            core.label(),
            field.helpText(),
            field.placeholder(),
            field.required() || core.schemaRequired(),
            core.schemaRequired(),
            core.protectedField(),
            defaultValue == null ? null : defaultValue.value(),
            defaultValue == null ? null : defaultValue.origin(),
            coreOptions(field.key(), context));
    }

    private List<ResolvedCreationFieldDto> missingRequired(
            String templateId,
            Set<String> configured,
            ResolutionContext context,
            List<RecordCreationWarningDto> warnings) {
        List<ResolvedCreationFieldDto> result = new ArrayList<>();
        for (FieldDefinition field : fieldRegistry.fields(context.recordType()).values()) {
            if (field.schemaRequired() && !configured.contains(field.key())) {
                result.add(resolveCoreField(
                    templateId,
                    new RecordCreationTemplateFieldDto(field.key(), false, null, null, null),
                    field,
                    context));
            }
        }
        for (CustomFieldDefinition custom : context.customFields().values()) {
            String key = "custom:" + custom.getId();
            if (custom.isRequired() && !configured.contains(key)) {
                ResolvedCreationFieldDto field = resolveField(
                    templateId,
                    new RecordCreationTemplateFieldDto(key, false, null, null, null),
                    context,
                    warnings);
                result.add(field);
            }
        }
        return result;
    }

    private DefaultValue resolveDefault(
            String fieldKey,
            RecordCreationDefaultSpecDto spec,
            ResolutionContext context) {
        if (spec == null) {
            return null;
        }
        JsonNode value = switch (spec.kind()) {
            case literal_string -> objectMapper.valueToTree(spec.stringValue());
            case literal_number -> objectMapper.valueToTree(spec.numberValue());
            case literal_boolean -> objectMapper.valueToTree(spec.booleanValue());
            case literal_date -> objectMapper.valueToTree(spec.dateValue().toString());
            case literal_reference -> objectMapper.valueToTree(spec.referenceId());
            case literal_references -> objectMapper.valueToTree(spec.referenceIds());
            case current_user -> objectMapper.valueToTree(context.actorId());
            case current_date -> objectMapper.valueToTree(LocalDate.now(clock).toString());
            case related_company -> context.relatedCompanyId() == null
                ? null
                : objectMapper.valueToTree(context.relatedCompanyId());
        };
        if (value == null) {
            return null;
        }
        RecordCreationDefaultOrigin origin =
            spec.kind() == RecordCreationDefaultKind.current_user && "owner".equals(fieldKey)
                ? RecordCreationDefaultOrigin.policy
                : RecordCreationDefaultOrigin.template;
        return new DefaultValue(value, origin);
    }

    private List<CreationFieldOptionDto> coreOptions(
            String fieldKey,
            ResolutionContext context) {
        if ("leadSource".equals(fieldKey)) {
            return LEAD_SOURCES.stream()
                .map(value -> new CreationFieldOptionDto(
                    value,
                    new LocalizedTextDto(value, value),
                    "BUSINESS_CARD".equals(value) || "IMPORT".equals(value)))
                .toList();
        }
        if ("pipeline".equals(fieldKey)) {
            return context.pipelines().stream()
                .map(pipeline -> option(pipeline.getId(), pipeline.getName()))
                .toList();
        }
        if ("stage".equals(fieldKey)) {
            return context.stages().stream()
                .map(stage -> option(stage.getId(), stage.getName()))
                .toList();
        }
        if ("tags".equals(fieldKey)) {
            return context.tags().stream()
                .map(tag -> option(tag.getId(), tag.getName()))
                .toList();
        }
        return List.of();
    }

    private List<CreationFieldOptionDto> customOptions(CustomFieldDefinition custom) {
        if (!"select".equals(custom.getFieldType())
                || custom.getOptionsJson() == null
                || custom.getOptionsJson().isBlank()) {
            return List.of();
        }
        try {
            return Arrays.stream(objectMapper.readValue(
                    custom.getOptionsJson(), CustomFieldOption[].class))
                .map(option -> new CreationFieldOptionDto(
                    option.getKey(),
                    new LocalizedTextDto(option.getLabel(), option.getLabel()),
                    false))
                .toList();
        } catch (Exception exception) {
            return null;
        }
    }

    private static CreationFieldOptionDto option(int id, String label) {
        return new CreationFieldOptionDto(
            Integer.toString(id),
            new LocalizedTextDto(label, label),
            false);
    }

    private static String fingerprint(FieldDefinition field) {
        return sha256(field.key() + "|" + field.valueType() + "|"
            + field.schemaRequired() + "|" + field.protectedField());
    }

    private static String fingerprint(CustomFieldDefinition field) {
        return sha256(field.getId() + "|" + field.getEntityType() + "|" + field.getFieldType()
            + "|" + field.isRequired() + "|" + field.getLabel() + "|" + field.getOptionsJson());
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is unavailable");
        }
    }

    private static LocalizedTextDto localized(String en, String ja) {
        return en == null && ja == null ? null : new LocalizedTextDto(en, ja);
    }
}
