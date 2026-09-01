package ooo.klae.connex.backend.services;

import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import tools.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;

import ooo.klae.connex.backend.beans.CustomFieldDefinition;
import ooo.klae.connex.backend.beans.Stage;
import ooo.klae.connex.backend.dto.CustomFieldOption;
import ooo.klae.connex.backend.dto.recordcreation.LocalizedTextDto;
import ooo.klae.connex.backend.dto.recordcreation.RecordCreationDefaultSpecDto;
import ooo.klae.connex.backend.dto.recordcreation.RecordCreationTemplateDefinitionDto;
import ooo.klae.connex.backend.dto.recordcreation.RecordCreationTemplateFieldDto;
import ooo.klae.connex.backend.dto.recordcreation.RecordCreationTemplateGroupDto;
import ooo.klae.connex.backend.exceptions.RecordCreationTemplateException;
import ooo.klae.connex.backend.mappers.CompanyMapper;
import ooo.klae.connex.backend.mappers.CustomFieldDefinitionMapper;
import ooo.klae.connex.backend.mappers.PersonMapper;
import ooo.klae.connex.backend.mappers.PipelineMapper;
import ooo.klae.connex.backend.mappers.TagMapper;
import ooo.klae.connex.backend.recordcreation.RecordCreationDefaultKind;
import ooo.klae.connex.backend.recordcreation.RecordCreationFieldRegistry;
import ooo.klae.connex.backend.recordcreation.RecordCreationFieldRegistry.CustomTypeDefinition;
import ooo.klae.connex.backend.recordcreation.RecordCreationFieldRegistry.FieldDefinition;
import ooo.klae.connex.backend.recordcreation.RecordCreationRecordType;

@Service
@RequiredArgsConstructor
public class RecordCreationTemplateValidator {

    public record ValidatedTemplate(
        LocalizedTextDto name,
        LocalizedTextDto description,
        RecordCreationTemplateDefinitionDto definition,
        String definitionJson,
        byte[] definitionHash
    ) {
        public ValidatedTemplate {
            definitionHash = Arrays.copyOf(definitionHash, definitionHash.length);
        }

        @Override
        public byte[] definitionHash() {
            return Arrays.copyOf(definitionHash, definitionHash.length);
        }
    }

    private static final Pattern GROUP_KEY = Pattern.compile("^[a-z][a-z0-9-]{0,47}$");
    private static final Pattern CUSTOM_KEY = Pattern.compile("^custom:([1-9][0-9]{0,9})$");
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final Set<String> LEAD_SOURCES = Set.of(
        "REFERRAL", "EVENT", "WEB", "OUTBOUND", "PARTNER", "OTHER");

    private final RecordCreationFieldRegistry fieldRegistry;
    private final CustomFieldDefinitionMapper customFieldMapper;
    private final TagMapper tagMapper;
    private final CompanyMapper companyMapper;
    private final PersonMapper personMapper;
    private final PipelineMapper pipelineMapper;
    private final WorkspaceService workspaceService;
    private final ObjectMapper objectMapper;

    public ValidatedTemplate validateAndCanonicalize(
            RecordCreationRecordType recordType,
            LocalizedTextDto name,
            LocalizedTextDto description,
            RecordCreationTemplateDefinitionDto definition) {
        if (recordType == null) {
            throw invalid("A record type is required");
        }
        LocalizedTextDto canonicalName = requiredLocalized(name, 128, "name");
        LocalizedTextDto canonicalDescription = optionalLocalized(description, 512, "description");
        RecordCreationTemplateDefinitionDto canonicalDefinition =
            canonicalDefinition(recordType, definition);
        String json;
        try {
            json = objectMapper.writeValueAsString(canonicalDefinition);
        } catch (Exception exception) {
            throw invalid("The template definition could not be serialized");
        }
        if (json.getBytes(StandardCharsets.UTF_8).length > 131_072) {
            throw invalid("The template definition exceeds 131072 UTF-8 bytes");
        }
        return new ValidatedTemplate(
            canonicalName,
            canonicalDescription,
            canonicalDefinition,
            json,
            sha256(json));
    }

    public RecordCreationTemplateDefinitionDto parseDefinition(String definitionJson) {
        try {
            return objectMapper.readValue(definitionJson, RecordCreationTemplateDefinitionDto.class);
        } catch (Exception exception) {
            throw invalid("The stored template definition is invalid");
        }
    }

    private RecordCreationTemplateDefinitionDto canonicalDefinition(
            RecordCreationRecordType recordType,
            RecordCreationTemplateDefinitionDto definition) {
        if (definition == null || definition.schemaVersion() != 1 || definition.groups() == null) {
            throw invalid("schemaVersion must be 1 and groups must be present");
        }
        if (definition.groups().size() < 1 || definition.groups().size() > 8) {
            throw invalid("A template must contain between 1 and 8 groups");
        }
        Set<String> groupKeys = new HashSet<>();
        Set<String> fieldKeys = new HashSet<>();
        List<RecordCreationTemplateGroupDto> groups = new ArrayList<>();
        int fieldCount = 0;
        Integer pipelineDefault = null;
        Integer stageDefault = null;
        for (RecordCreationTemplateGroupDto group : definition.groups()) {
            if (group == null || group.key() == null || !GROUP_KEY.matcher(group.key()).matches()) {
                throw invalid("Every group needs a valid key");
            }
            if (!groupKeys.add(group.key())) {
                throw invalid("Duplicate group key: " + group.key());
            }
            if (group.fields() == null || group.fields().size() < 1 || group.fields().size() > 20) {
                throw invalid("Every group must contain between 1 and 20 fields");
            }
            LocalizedTextDto label = requiredLocalized(group.label(), 80, "group label");
            LocalizedTextDto groupDescription =
                optionalLocalized(group.description(), 240, "group description");
            List<RecordCreationTemplateFieldDto> fields = new ArrayList<>();
            for (RecordCreationTemplateFieldDto field : group.fields()) {
                RecordCreationTemplateFieldDto canonical =
                    canonicalField(recordType, field, fieldKeys);
                fields.add(canonical);
                fieldCount++;
                if (fieldCount > 40) {
                    throw invalid("A template may contain at most 40 fields");
                }
                if ("pipeline".equals(canonical.key()) && canonical.defaultSpec() != null
                        && canonical.defaultSpec().kind() == RecordCreationDefaultKind.literal_reference) {
                    pipelineDefault = canonical.defaultSpec().referenceId();
                }
                if ("stage".equals(canonical.key()) && canonical.defaultSpec() != null
                        && canonical.defaultSpec().kind() == RecordCreationDefaultKind.literal_reference) {
                    stageDefault = canonical.defaultSpec().referenceId();
                }
            }
            groups.add(new RecordCreationTemplateGroupDto(
                group.key(), label, groupDescription, List.copyOf(fields)));
        }
        if (pipelineDefault != null && stageDefault != null) {
            Stage stage = pipelineMapper.getVisibleStageById(
                workspaceService.getCurrentWorkspaceId(), stageDefault);
            if (stage == null || stage.getPipeline() == null
                    || stage.getPipeline().getId() != pipelineDefault) {
                throw defaultForbidden("The default stage does not belong to the default pipeline");
            }
        }
        return new RecordCreationTemplateDefinitionDto(1, List.copyOf(groups));
    }

    private RecordCreationTemplateFieldDto canonicalField(
            RecordCreationRecordType recordType,
            RecordCreationTemplateFieldDto field,
            Set<String> fieldKeys) {
        if (field == null || field.key() == null || field.key().isBlank()) {
            throw invalid("Every field needs a key");
        }
        if (!fieldKeys.add(field.key())) {
            throw invalid("Duplicate field key: " + field.key());
        }
        FieldDefinition core = fieldRegistry.field(recordType, field.key());
        CustomFieldDefinition custom = null;
        CustomTypeDefinition customType = null;
        if (core == null) {
            java.util.regex.Matcher matcher = CUSTOM_KEY.matcher(field.key());
            if (!matcher.matches()) {
                throw invalid("Unknown field key: " + field.key());
            }
            int customFieldId;
            try {
                customFieldId = Integer.parseInt(matcher.group(1));
            } catch (NumberFormatException exception) {
                throw invalid("Invalid custom field key: " + field.key());
            }
            custom = customFieldMapper.getById(workspaceService.getCurrentWorkspaceId(), customFieldId);
            if (custom == null || custom.isArchived()
                    || !recordType.name().equals(custom.getEntityType())) {
                throw invalid("Custom field is unavailable: " + field.key());
            }
            customType = fieldRegistry.customType(custom.getFieldType());
            if (customType == null) {
                throw invalid("Custom field type is unsupported: " + field.key());
            }
        }
        if ("consentStatus".equals(field.key())
                && (field.required() || field.defaultSpec() != null)) {
            throw invalid("Consent status cannot be template-required or defaulted");
        }
        LocalizedTextDto helpText = optionalLocalized(field.helpText(), 240, "help text");
        LocalizedTextDto placeholder = optionalLocalized(field.placeholder(), 160, "placeholder");
        RecordCreationDefaultSpecDto defaultSpec = canonicalDefault(
            recordType,
            field.key(),
            core == null ? customType.defaultKinds() : core.defaultKinds(),
            custom,
            field.defaultSpec());
        return new RecordCreationTemplateFieldDto(
            field.key(), field.required(), helpText, placeholder, defaultSpec);
    }

    private RecordCreationDefaultSpecDto canonicalDefault(
            RecordCreationRecordType recordType,
            String fieldKey,
            Set<RecordCreationDefaultKind> allowedKinds,
            CustomFieldDefinition custom,
            RecordCreationDefaultSpecDto spec) {
        if (spec == null) {
            return null;
        }
        if (spec.kind() == null || !allowedKinds.contains(spec.kind())) {
            throw defaultForbidden("The default kind is not allowed for " + fieldKey);
        }
        requirePayloadShape(spec);
        int workspaceId = workspaceService.getCurrentWorkspaceId();
        return switch (spec.kind()) {
            case literal_string -> {
                String value = normalized(spec.stringValue());
                if (value == null || value.isEmpty()) {
                    throw defaultForbidden("A string default cannot be empty");
                }
                validateStringDefault(recordType, fieldKey, custom, value);
                yield new RecordCreationDefaultSpecDto(
                    spec.kind(), value, null, null, null, null, null);
            }
            case literal_number -> {
                validateNumberDefault(fieldKey, spec.numberValue());
                yield spec;
            }
            case literal_boolean, literal_date -> spec;
            case literal_reference -> {
                validateReference(workspaceId, fieldKey, spec.referenceId());
                yield spec;
            }
            case literal_references -> {
                List<Integer> ids = spec.referenceIds().stream().sorted().distinct().toList();
                if (ids.isEmpty() || ids.size() > 20 || ids.stream().anyMatch(id -> id == null || id <= 0)) {
                    throw defaultForbidden("Tag defaults must contain 1 through 20 positive IDs");
                }
                for (Integer id : ids) {
                    if (!tagMapper.exists(workspaceId, id)) {
                        throw defaultForbidden("A default tag is unavailable");
                    }
                }
                yield new RecordCreationDefaultSpecDto(
                    spec.kind(), null, null, null, null, null, ids);
            }
            case current_user, current_date, related_company -> spec;
        };
    }

    private void requirePayloadShape(RecordCreationDefaultSpecDto spec) {
        int payloads = 0;
        if (spec.stringValue() != null) payloads++;
        if (spec.numberValue() != null) payloads++;
        if (spec.booleanValue() != null) payloads++;
        if (spec.dateValue() != null) payloads++;
        if (spec.referenceId() != null) payloads++;
        if (spec.referenceIds() != null) payloads++;
        boolean literal = switch (spec.kind()) {
            case literal_string, literal_number, literal_boolean, literal_date,
                literal_reference, literal_references -> true;
            default -> false;
        };
        if ((literal && payloads != 1) || (!literal && payloads != 0)) {
            throw defaultForbidden("The default payload does not match its kind");
        }
        boolean matching = switch (spec.kind()) {
            case literal_string -> spec.stringValue() != null;
            case literal_number -> spec.numberValue() != null;
            case literal_boolean -> spec.booleanValue() != null;
            case literal_date -> spec.dateValue() != null;
            case literal_reference -> spec.referenceId() != null;
            case literal_references -> spec.referenceIds() != null;
            default -> payloads == 0;
        };
        if (!matching) {
            throw defaultForbidden("The default payload does not match its kind");
        }
    }

    private void validateStringDefault(
            RecordCreationRecordType recordType,
            String fieldKey,
            CustomFieldDefinition custom,
            String value) {
        int max = switch (fieldKey) {
            case "phone" -> 64;
            case "title" -> 128;
            case "address" -> 2_000;
            default -> 255;
        };
        if (codePoints(value) > max) {
            throw defaultForbidden("The string default is too long for " + fieldKey);
        }
        if ("email".equals(fieldKey) && !EMAIL.matcher(value).matches()) {
            throw defaultForbidden("The email default is invalid");
        }
        if (("website".equals(fieldKey) || custom != null && "url".equals(custom.getFieldType()))
                && !validHttpUrl(value)) {
            throw defaultForbidden("The URL default is invalid");
        }
        if ("leadSource".equals(fieldKey) && !LEAD_SOURCES.contains(value)) {
            throw defaultForbidden("The lead source default is not allowed for interactive creation");
        }
        if ("currency".equals(fieldKey) && !value.matches("^[A-Z]{3}$")) {
            throw defaultForbidden("The currency default must be a three-letter code");
        }
        if (custom != null && "select".equals(custom.getFieldType())
                && !customOptionExists(custom, value)) {
            throw defaultForbidden("The custom select default is unavailable");
        }
    }

    private void validateNumberDefault(String fieldKey, BigDecimal value) {
        if (value.scale() > 4 || value.precision() > 20
                || "value".equals(fieldKey) && value.signum() < 0) {
            throw defaultForbidden("The numeric default is out of range");
        }
    }

    private void validateReference(int workspaceId, String fieldKey, Integer id) {
        if (id == null || id <= 0) {
            throw defaultForbidden("A reference default must use a positive ID");
        }
        boolean exists = switch (fieldKey) {
            case "company" -> companyMapper.exists(workspaceId, id);
            case "referrerPerson" -> personMapper.exists(workspaceId, id);
            case "pipeline" -> pipelineMapper.pipelineExists(workspaceId, id);
            case "stage" -> pipelineMapper.getVisibleStageById(workspaceId, id) != null;
            default -> false;
        };
        if (!exists) {
            throw defaultForbidden("The reference default is unavailable");
        }
    }

    private boolean customOptionExists(CustomFieldDefinition custom, String value) {
        if (custom.getOptionsJson() == null || custom.getOptionsJson().isBlank()) {
            return false;
        }
        try {
            CustomFieldOption[] options =
                objectMapper.readValue(custom.getOptionsJson(), CustomFieldOption[].class);
            return Arrays.stream(options).anyMatch(option -> value.equals(option.getKey()));
        } catch (Exception exception) {
            return false;
        }
    }

    private static LocalizedTextDto requiredLocalized(
            LocalizedTextDto value,
            int max,
            String field) {
        if (value == null) {
            throw invalid(field + " requires English and Japanese text");
        }
        String en = normalized(value.en());
        String ja = normalized(value.ja());
        if (en == null || en.isEmpty() || ja == null || ja.isEmpty()) {
            throw invalid(field + " requires English and Japanese text");
        }
        if (codePoints(en) > max || codePoints(ja) > max) {
            throw invalid(field + " exceeds " + max + " characters");
        }
        return new LocalizedTextDto(en, ja);
    }

    private static LocalizedTextDto optionalLocalized(
            LocalizedTextDto value,
            int max,
            String field) {
        if (value == null) {
            return null;
        }
        String en = normalized(value.en());
        String ja = normalized(value.ja());
        boolean enEmpty = en == null || en.isEmpty();
        boolean jaEmpty = ja == null || ja.isEmpty();
        if (enEmpty && jaEmpty) {
            return null;
        }
        if (enEmpty || jaEmpty) {
            throw invalid(field + " must be present in both English and Japanese");
        }
        if (codePoints(en) > max || codePoints(ja) > max) {
            throw invalid(field + " exceeds " + max + " characters");
        }
        return new LocalizedTextDto(en, ja);
    }

    private static String normalized(String value) {
        return value == null ? null : Normalizer.normalize(value, Normalizer.Form.NFC).strip();
    }

    private static int codePoints(String value) {
        return value.codePointCount(0, value.length());
    }

    private static boolean validHttpUrl(String value) {
        try {
            URI uri = URI.create(value);
            return uri.getHost() != null
                && ("http".equalsIgnoreCase(uri.getScheme())
                    || "https".equalsIgnoreCase(uri.getScheme()));
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is unavailable");
        }
    }

    private static RecordCreationTemplateException invalid(String message) {
        return RecordCreationTemplateException.of(
            HttpStatus.BAD_REQUEST, "TEMPLATE_DEFINITION_INVALID", message);
    }

    private static RecordCreationTemplateException defaultForbidden(String message) {
        return RecordCreationTemplateException.of(
            HttpStatus.BAD_REQUEST, "TEMPLATE_DEFAULT_FORBIDDEN", message);
    }
}
