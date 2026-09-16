package ooo.klae.connex.backend.ai.masking;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;

class OutboundLeakScanTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void topLevelJsonNullIsSkippedButTheStringNullIsScreened() {
        MaskingContext context = new MaskingContext();
        MaskingEngine.maskField(EntityKind.PERSON, "null", context);

        assertDoesNotThrow(() -> OutboundLeakScan.assertNoLeak("null", context, objectMapper));
        assertDoesNotThrow(() -> OutboundLeakScan.assertNoLeakStrict("null", context, objectMapper));
        assertThrows(MaskingLeakException.class,
                () -> OutboundLeakScan.assertNoLeakStrict("\"null\"", context, objectMapper));
    }

    @Test
    void nestedNullScalarsAreSkippedWhileSurroundingTextAndKeysAreScreened() {
        MaskingContext context = new MaskingContext();
        MaskingEngine.maskField(EntityKind.PERSON, "Ann Smith", context);

        assertDoesNotThrow(() -> OutboundLeakScan.assertNoLeakStrict(
                "{\"before\":\"safe\",\"nested\":[null,{\"optional\":null}],\"after\":\"safe\"}",
                context, objectMapper));
        for (String payload : List.of(
                "{\"before\":\"Ann Smith\",\"nested\":[null,{\"optional\":null}],\"after\":\"safe\"}",
                "{\"before\":\"safe\",\"nested\":[null,{\"optional\":null}],\"after\":\"Ann Smith\"}",
                "{\"nested\":[null,{\"message\":\"Ann Smith\",\"optional\":null}]}",
                "{\"nested\":[null,{\"Ann Smith\":null}]}")) {
            assertThrows(MaskingLeakException.class,
                    () -> OutboundLeakScan.assertNoLeakStrict(payload, context, objectMapper));
        }
    }

    @Test
    void duplicateKeyNullsDoNotHideEarlierOrLaterText() {
        MaskingContext context = new MaskingContext();
        MaskingEngine.maskField(EntityKind.PERSON, "Ann Smith", context);

        assertDoesNotThrow(() -> OutboundLeakScan.assertNoLeakStrict(
                "{\"message\":null,\"message\":\"safe\",\"message\":null}", context, objectMapper));
        for (String payload : List.of(
                "{\"message\":\"Ann Smith\",\"message\":null}",
                "{\"message\":null,\"message\":\"Ann Smith\"}",
                "{\"Ann Smith\":null,\"Ann Smith\":\"safe\"}")) {
            assertThrows(MaskingLeakException.class,
                    () -> OutboundLeakScan.assertNoLeakStrict(payload, context, objectMapper));
        }
    }
}
