package ooo.klae.connex.backend.webauthn;

import org.springframework.security.web.webauthn.jackson.WebauthnJacksonModule;
import org.springframework.stereotype.Component;

import ooo.klae.connex.backend.config.RequestBodySizeProperties;
import ooo.klae.connex.backend.exceptions.BadRequestException;
import ooo.klae.connex.backend.exceptions.RequestBodyTooLargeException;

import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.exc.StreamConstraintsException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.json.JsonMapper;

/**
 * Dedicated Jackson 3 mapper for WebAuthn ceremony payloads, equipped with Spring Security's
 * {@code WebauthnJacksonModule}. Kept separate from the shared MVC mapper (which lacks the module)
 * so the relying-party option and credential types (de)serialize in the browser-compatible,
 * base64url shape. Thread-safe; built once.
 */
@Component
public class WebAuthnJsonMapper {

    private final JsonMapper mapper;

    public WebAuthnJsonMapper(RequestBodySizeProperties properties) {
        this.mapper = JsonMapper.builder(JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder()
                .maxDocumentLength(properties.getWebauthnMaxBodyBytes())
                .build())
            .build())
            .addModule(new WebauthnJacksonModule())
            .build();
    }

    /**
     * Serializes a relying-party option object to the JSON the browser's WebAuthn client consumes.
     * @param value the option object
     * @return its JSON representation
     */
    public String write(Object value) {
        return mapper.writeValueAsString(value);
    }

    /**
     * Parses a client ceremony response, rejecting malformed payloads as a 400.
     * @param json the raw request body
     * @param type the target WebAuthn type
     * @param <T> the target type
     * @return the parsed value
     */
    public <T> T read(String json, TypeReference<T> type) {
        try {
            return mapper.readValue(json, type);
        } catch (StreamConstraintsException ex) {
            throw new RequestBodyTooLargeException(mapper.tokenStreamFactory().streamReadConstraints().getMaxDocumentLength());
        } catch (JacksonException ex) {
            throw new BadRequestException("Malformed WebAuthn payload");
        }
    }
}
