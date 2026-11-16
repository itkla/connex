package ooo.klae.connex.backend.storage;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayOutputStream;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import ooo.klae.connex.backend.exceptions.UnsupportedUploadMediaTypeException;

/** Direct boundary tests for the framing walk, independent of X.509 parser tolerance. */
class SignatureCertificateDerFramingTest {
    @Test
    void acceptsGeneratedDerCertificate() throws Exception {
        accepted(PackageFixtures.signerCertificate());
        accepted(PackageFixtures.signerCertificate(64 * 1024));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 127, 128, 255, 256})
    void acceptsMinimalLengthsAtDepth(int bodyLength) {
        accepted(sequence(sequence(PackageFixtures.derValue(0x04, new byte[bodyLength]))));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("nonMinimalLengths")
    void rejectsNonMinimalLengthsAtDepth(String name, byte[] nestedValue) {
        refused(sequence(sequence(nestedValue)));
    }

    private static Stream<Arguments> nonMinimalLengths() throws Exception {
        return Stream.of(
            Arguments.of("long-form-for-short-sequence", hex("3081020500")),
            Arguments.of("long-form-for-empty-primitive", hex("048100")),
            Arguments.of("leading-zero-long-length", PackageFixtures.concatenate(
                hex("04820080"), new byte[128])));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("indefiniteLengths")
    void rejectsIndefiniteLengthsAtDepth(String name, byte[] nestedValue) {
        refused(sequence(sequence(nestedValue)));
    }

    private static Stream<Arguments> indefiniteLengths() {
        return Stream.of(
            Arguments.of("sequence", hex("308005000000")),
            Arguments.of("context-specific", hex("a08005000000")),
            Arguments.of("nested-sequences", hex("30803080050000000000")));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 9, 10, 12, 14, 18, 19, 20, 21, 22,
        23, 24, 25, 26, 27, 28, 30})
    void rejectsConstructedPrimitiveUniversalTypes(int tag) {
        byte[] primitive = PackageFixtures.derValue(tag, new byte[0]);
        accepted(sequence(primitive));
        refused(sequence(PackageFixtures.derValue(tag | 0x20, primitive)));
    }

    @ParameterizedTest
    @ValueSource(ints = {8, 11, 16, 17, 29})
    void rejectsPrimitiveEncodingsOfConstructedUniversalTypes(int tag) {
        accepted(sequence(PackageFixtures.derValue(tag | 0x20, hex("0500"))));
        refused(sequence(PackageFixtures.derValue(tag, hex("0500"))));
    }

    @ParameterizedTest
    @ValueSource(strings = {"9f1f00", "bf8100020500", "9f87ffffff7f00", "1f1f00"})
    void acceptsMinimalHighTagForms(String value) {
        accepted(sequence(hex(value)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"9f1e00", "9f801f00", "9f81", "9f1f", "9f888080800000",
        "0000", "0f00", "1f2500"})
    void rejectsMalformedOrReservedTags(String value) {
        refused(sequence(hex(value)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"3003050000", "040500", "048201", "04847fffffff", "04850000000080"})
    void rejectsNestedTrailingBytesAndTruncatedValues(String value) {
        refused(sequence(hex(value)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "20", "300000", "30003000", "003000", "3100",
        "30800000", "308100", "30820000"})
    void rejectsInvalidOuterFraming(String value) {
        refused(hex(value));
    }

    @Test
    void enforcesDepthBoundIncludingRoot() {
        byte[] value = sequence(new byte[0]);
        for (int depth = 1; depth < 32; depth++) {
            value = sequence(value);
        }
        accepted(value);
        refused(sequence(value));
    }

    @Test
    void enforcesNodeBoundIncludingRoot() {
        ByteArrayOutputStream children = new ByteArrayOutputStream();
        for (int count = 1; count < 4096; count++) {
            children.writeBytes(hex("0500"));
        }
        accepted(sequence(children.toByteArray()));
        children.writeBytes(hex("0500"));
        refused(sequence(children.toByteArray()));
    }

    @Test
    void enforcesByteBound() {
        byte[] atBound = sequence(PackageFixtures.derValue(0x04, new byte[65_528]));
        assertEquals(64 * 1024, atBound.length);
        accepted(atBound);
        refused(sequence(PackageFixtures.derValue(0x04, new byte[65_529])));
    }

    @Test
    void checksDeadlineDuringWalk() {
        byte[] value = sequence(sequence(hex("0500")));
        accepted(value);
        AtomicInteger visited = new AtomicInteger();

        assertThrows(UnsupportedUploadMediaTypeException.class,
            () -> SignatureCertificateDerFraming.requireValid(value, () -> {
                if (visited.incrementAndGet() == 3) {
                    throw UnsupportedUploadMediaTypeException.unsupported();
                }
            }));
        assertEquals(3, visited.get());
    }

    private static byte[] hex(String value) {
        return HexFormat.of().parseHex(value);
    }

    private static byte[] sequence(byte[] body) {
        return PackageFixtures.derValue(0x30, body);
    }

    private static void accepted(byte[] value) {
        assertDoesNotThrow(() -> SignatureCertificateDerFraming.requireValid(value, () -> {}));
    }

    private static void refused(byte[] value) {
        assertThrows(UnsupportedUploadMediaTypeException.class,
            () -> SignatureCertificateDerFraming.requireValid(value, () -> {}));
    }
}
