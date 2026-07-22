package ooo.klae.connex.backend.dto;

import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.deser.std.StdDeserializer;

/** Strict scalar deserializers for workflow lifecycle request fields. */
public final class StrictWorkflowScalarDeserializer {

    private StrictWorkflowScalarDeserializer() {
    }

    /** Accepts only a JSON string token. */
    public static final class StringValue extends StdDeserializer<String> {

        public StringValue() {
            super(String.class);
        }

        @Override
        public String deserialize(JsonParser parser, DeserializationContext context)
                throws JacksonException {
            if (!parser.hasToken(JsonToken.VALUE_STRING)) {
                return context.reportInputMismatch(String.class, "Expected a JSON string");
            }
            return parser.getString();
        }
    }

    /** Accepts only an in-range JSON integer token. */
    public static final class IntegerValue extends StdDeserializer<Integer> {

        public IntegerValue() {
            super(Integer.class);
        }

        @Override
        public Integer deserialize(JsonParser parser, DeserializationContext context)
                throws JacksonException {
            if (!parser.hasToken(JsonToken.VALUE_NUMBER_INT)) {
                return context.reportInputMismatch(Integer.class, "Expected a JSON integer");
            }
            return parser.getIntValue();
        }
    }
}
