package ooo.klae.connex.backend.recordcreation;

import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import ooo.klae.connex.backend.dto.recordcreation.LocalizedTextDto;
import ooo.klae.connex.backend.dto.recordcreation.RecordCreationDefaultSpecDto;
import ooo.klae.connex.backend.dto.recordcreation.RecordCreationTemplateDefinitionDto;
import ooo.klae.connex.backend.dto.recordcreation.RecordCreationTemplateFieldDto;
import ooo.klae.connex.backend.dto.recordcreation.RecordCreationTemplateGroupDto;

@Component
public class RecordCreationFieldRegistry {

    public record FieldDefinition(
        String key,
        RecordCreationFieldValueType valueType,
        boolean schemaRequired,
        boolean protectedField,
        Set<RecordCreationDefaultKind> defaultKinds,
        LocalizedTextDto label
    ) {
    }

    public record CustomTypeDefinition(
        RecordCreationFieldValueType valueType,
        Set<RecordCreationDefaultKind> defaultKinds
    ) {
    }

    public record SystemPreset(
        String id,
        RecordCreationRecordType recordType,
        int version,
        LocalizedTextDto name,
        LocalizedTextDto description,
        RecordCreationTemplateDefinitionDto definition
    ) {
    }

    private static final Set<RecordCreationDefaultKind> STRING =
        Set.of(RecordCreationDefaultKind.literal_string);
    private static final Set<RecordCreationDefaultKind> NUMBER =
        Set.of(RecordCreationDefaultKind.literal_number);
    private static final Set<RecordCreationDefaultKind> REFERENCE =
        Set.of(RecordCreationDefaultKind.literal_reference);
    private static final Set<RecordCreationDefaultKind> REFERENCE_OR_CONTEXT =
        Set.of(RecordCreationDefaultKind.literal_reference, RecordCreationDefaultKind.related_company);
    private static final Set<RecordCreationDefaultKind> DATE =
        Set.of(RecordCreationDefaultKind.literal_date, RecordCreationDefaultKind.current_date);
    private static final Set<RecordCreationDefaultKind> TAGS =
        Set.of(RecordCreationDefaultKind.literal_references);
    private static final Set<RecordCreationDefaultKind> OWNER =
        Set.of(RecordCreationDefaultKind.current_user);

    private final Map<RecordCreationRecordType, Map<String, FieldDefinition>> fields;
    private final Map<String, CustomTypeDefinition> customTypes;
    private final Map<RecordCreationRecordType, SystemPreset> presets;

    public RecordCreationFieldRegistry() {
        fields = buildFields();
        customTypes = Map.of(
            "text", new CustomTypeDefinition(RecordCreationFieldValueType.text, STRING),
            "textarea", new CustomTypeDefinition(RecordCreationFieldValueType.textarea, STRING),
            "number", new CustomTypeDefinition(RecordCreationFieldValueType.decimal, NUMBER),
            "date", new CustomTypeDefinition(RecordCreationFieldValueType.date, DATE),
            "boolean", new CustomTypeDefinition(
                RecordCreationFieldValueType.BOOLEAN,
                Set.of(RecordCreationDefaultKind.literal_boolean)),
            "select", new CustomTypeDefinition(RecordCreationFieldValueType.single_select, STRING),
            "url", new CustomTypeDefinition(RecordCreationFieldValueType.url, STRING)
        );
        presets = buildPresets();
    }

    public Map<String, FieldDefinition> fields(RecordCreationRecordType recordType) {
        return fields.get(recordType);
    }

    public FieldDefinition field(RecordCreationRecordType recordType, String key) {
        return fields(recordType).get(key);
    }

    public CustomTypeDefinition customType(String fieldType) {
        return customTypes.get(fieldType);
    }

    public SystemPreset systemPreset(RecordCreationRecordType recordType) {
        return presets.get(recordType);
    }

    public boolean isSystemId(String id) {
        return id != null && presets.values().stream().anyMatch(preset -> preset.id().equals(id));
    }

    private static Map<RecordCreationRecordType, Map<String, FieldDefinition>> buildFields() {
        Map<RecordCreationRecordType, Map<String, FieldDefinition>> result =
            new EnumMap<>(RecordCreationRecordType.class);
        result.put(RecordCreationRecordType.person, linked(
            field("name", RecordCreationFieldValueType.text, true, true, STRING, "Name", "名前"),
            field("email", RecordCreationFieldValueType.email, false, false, STRING, "Email", "メール"),
            field("phone", RecordCreationFieldValueType.phone, false, false, STRING, "Phone", "電話"),
            field("title", RecordCreationFieldValueType.text, false, false, STRING, "Title", "役職"),
            field("company", RecordCreationFieldValueType.company_reference, false, false,
                REFERENCE_OR_CONTEXT, "Company", "会社"),
            field("owner", RecordCreationFieldValueType.user_reference, true, true, OWNER, "Owner", "担当者"),
            field("leadSource", RecordCreationFieldValueType.single_select, false, true,
                STRING, "Lead source", "獲得経路"),
            field("leadSourceDetail", RecordCreationFieldValueType.text, false, true,
                STRING, "Lead source details", "獲得経路の詳細"),
            field("referrerPerson", RecordCreationFieldValueType.person_reference, false, true,
                REFERENCE, "Referrer", "紹介者"),
            field("tags", RecordCreationFieldValueType.tag_references, false, false,
                TAGS, "Tags", "タグ"),
            field("consentStatus", RecordCreationFieldValueType.consent_disclosure, true, true,
                Set.of(), "Consent status", "同意状況")
        ));
        result.put(RecordCreationRecordType.company, linked(
            field("name", RecordCreationFieldValueType.text, true, true, STRING, "Name", "会社名"),
            field("website", RecordCreationFieldValueType.url, false, false, STRING, "Website", "ウェブサイト"),
            field("industry", RecordCreationFieldValueType.text, false, false, STRING, "Industry", "業種"),
            field("phone", RecordCreationFieldValueType.phone, false, false, STRING, "Phone", "電話"),
            field("address", RecordCreationFieldValueType.textarea, false, false, STRING, "Address", "住所"),
            field("owner", RecordCreationFieldValueType.user_reference, true, true, OWNER, "Owner", "担当者"),
            field("tags", RecordCreationFieldValueType.tag_references, false, false, TAGS, "Tags", "タグ")
        ));
        result.put(RecordCreationRecordType.deal, linked(
            field("name", RecordCreationFieldValueType.text, true, true, STRING, "Name", "案件名"),
            field("value", RecordCreationFieldValueType.decimal, true, true, NUMBER, "Value", "金額"),
            field("currency", RecordCreationFieldValueType.single_select, true, true,
                STRING, "Currency", "通貨"),
            field("pipeline", RecordCreationFieldValueType.pipeline_reference, true, true,
                REFERENCE, "Pipeline", "パイプライン"),
            field("stage", RecordCreationFieldValueType.stage_reference, true, true,
                REFERENCE, "Stage", "ステージ"),
            field("company", RecordCreationFieldValueType.company_reference, false, false,
                REFERENCE_OR_CONTEXT, "Company", "会社"),
            field("expectedCloseDate", RecordCreationFieldValueType.date, false, false,
                DATE, "Expected close date", "完了予定日"),
            field("owner", RecordCreationFieldValueType.user_reference, true, true, OWNER, "Owner", "担当者"),
            field("tags", RecordCreationFieldValueType.tag_references, false, false, TAGS, "Tags", "タグ")
        ));
        return java.util.Collections.unmodifiableMap(result);
    }

    private static Map<RecordCreationRecordType, SystemPreset> buildPresets() {
        Map<RecordCreationRecordType, SystemPreset> result =
            new EnumMap<>(RecordCreationRecordType.class);
        result.put(RecordCreationRecordType.person, new SystemPreset(
            "system:person:standard",
            RecordCreationRecordType.person,
            1,
            localized("Standard contact", "標準の連絡先"),
            localized("Essential contact details and trust information", "連絡先の基本情報と信頼情報"),
            definition(
                group("basics", "Basics", "基本情報", "name", "email", "phone", "title", "company"),
                group("trust", "Trust", "信頼情報", "owner", "leadSource", "leadSourceDetail",
                    "referrerPerson", "consentStatus", "tags")
            )
        ));
        result.put(RecordCreationRecordType.company, new SystemPreset(
            "system:company:standard",
            RecordCreationRecordType.company,
            1,
            localized("Standard company", "標準の会社"),
            localized("Essential company details and ownership", "会社の基本情報と担当者"),
            definition(
                group("basics", "Basics", "基本情報", "name", "website", "industry", "phone", "address"),
                group("trust", "Trust", "信頼情報", "owner", "tags")
            )
        ));
        result.put(RecordCreationRecordType.deal, new SystemPreset(
            "system:deal:standard",
            RecordCreationRecordType.deal,
            1,
            localized("Standard deal", "標準の案件"),
            localized("Essential deal details, pipeline, and ownership", "案件の基本情報、パイプライン、担当者"),
            definition(
                group("basics", "Basics", "基本情報", "name", "company", "value", "currency",
                    "expectedCloseDate"),
                group("trust", "Trust", "信頼情報", "pipeline", "stage", "owner", "tags")
            )
        ));
        return Map.copyOf(result);
    }

    private static FieldDefinition field(
            String key,
            RecordCreationFieldValueType type,
            boolean required,
            boolean protectedField,
            Set<RecordCreationDefaultKind> defaults,
            String en,
            String ja) {
        return new FieldDefinition(key, type, required, protectedField, defaults, localized(en, ja));
    }

    private static Map<String, FieldDefinition> linked(FieldDefinition... definitions) {
        Map<String, FieldDefinition> result = new LinkedHashMap<>();
        for (FieldDefinition definition : definitions) {
            result.put(definition.key(), definition);
        }
        return java.util.Collections.unmodifiableMap(result);
    }

    private static RecordCreationTemplateDefinitionDto definition(
            RecordCreationTemplateGroupDto... groups) {
        return new RecordCreationTemplateDefinitionDto(1, List.of(groups));
    }

    private static RecordCreationTemplateGroupDto group(
            String key,
            String en,
            String ja,
            String... fieldKeys) {
        List<RecordCreationTemplateFieldDto> fields = java.util.Arrays.stream(fieldKeys)
            .map(RecordCreationFieldRegistry::presetField)
            .toList();
        return new RecordCreationTemplateGroupDto(key, localized(en, ja), null, fields);
    }

    private static RecordCreationTemplateFieldDto presetField(String key) {
        RecordCreationDefaultSpecDto defaultSpec = switch (key) {
            case "owner" -> new RecordCreationDefaultSpecDto(
                RecordCreationDefaultKind.current_user, null, null, null, null, null, null);
            case "value" -> new RecordCreationDefaultSpecDto(
                RecordCreationDefaultKind.literal_number, null, BigDecimal.ZERO, null, null, null, null);
            case "currency" -> new RecordCreationDefaultSpecDto(
                RecordCreationDefaultKind.literal_string, "USD", null, null, null, null, null);
            default -> null;
        };
        return new RecordCreationTemplateFieldDto(key, false, null, null, defaultSpec);
    }

    private static LocalizedTextDto localized(String en, String ja) {
        return new LocalizedTextDto(en, ja);
    }
}
