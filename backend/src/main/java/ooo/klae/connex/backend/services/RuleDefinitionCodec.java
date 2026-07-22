package ooo.klae.connex.backend.services;

import java.nio.charset.StandardCharsets;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.ObjectMapper;

import ooo.klae.connex.backend.exceptions.BadRequestException;

/** Serializes and parses bounded persisted automation-rule definitions. */
@Component
@RequiredArgsConstructor
public class RuleDefinitionCodec {

    private static final int MAX_JSON_BYTES = 16384;

    private final ObjectMapper objectMapper;

    String serialize(Object value) {
        String json;
        try {
            json = objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new BadRequestException("Invalid rule configuration");
        }
        if (json.getBytes(StandardCharsets.UTF_8).length > MAX_JSON_BYTES) {
            throw new BadRequestException("Rule configuration is too large");
        }
        return json;
    }

    <T> T parse(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            throw new BadRequestException("Corrupt rule configuration");
        }
    }
}
