package ooo.klae.connex.backend.ai.assistant;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import ooo.klae.connex.backend.ai.masking.EntityKind;
import ooo.klae.connex.backend.ai.masking.MaskingContext;
import ooo.klae.connex.backend.ai.masking.MaskingEngine;

/** Raw tenant-local tool data plus structured labels that must be tokenized before prompt re-entry. */
public record AiAssistantToolResult(
        Map<String, Object> data,
        List<Identifier> identifiers) {

    public AiAssistantToolResult {
        data = Collections.unmodifiableMap(new LinkedHashMap<>(data));
        identifiers = List.copyOf(identifiers);
    }

    /** Structured record display identifier in a tool result. */
    public record Identifier(String kind, String value) {
        void seed(MaskingContext context) {
            java.util.Objects.requireNonNull(context, "context");
            if (value == null || value.isBlank()) {
                return;
            }
            EntityKind entityKind = switch (kind) {
                case "person" -> EntityKind.PERSON;
                case "company" -> EntityKind.COMPANY;
                case "deal" -> EntityKind.DEAL;
                default -> null;
            };
            if (entityKind != null) {
                MaskingEngine.maskField(entityKind, value, context);
            }
        }
    }
}
