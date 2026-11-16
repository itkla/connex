package ooo.klae.connex.backend.storage;

import java.util.Objects;

import ooo.klae.connex.backend.exceptions.UnsupportedUploadMediaTypeException;

/**
 * Bounds and checks DER tag/length framing before the BER-tolerant X.509 parser runs.
 *
 * <p>Every constructed value is walked to its exact boundary. Primitive contents, including
 * extension OCTET STRINGs and public-key BIT STRINGs, remain opaque here; the X.509 parser owns
 * certificate schema and primitive-value interpretation. This is not a complete ASN.1 schema or
 * semantic canonicalization check, and it does not establish certificate trust.
 */
final class SignatureCertificateDerFraming {
    private static final int MAX_BYTES = 64 * 1024;
    private static final int MAX_DEPTH = 32;
    private static final int MAX_NODES = 4096;

    private final byte[] content;
    private final Runnable deadlineCheck;
    private int nodes;

    private SignatureCertificateDerFraming(byte[] content, Runnable deadlineCheck) {
        this.content = Objects.requireNonNull(content);
        this.deadlineCheck = Objects.requireNonNull(deadlineCheck);
    }

    /**
     * Requires one bounded, constructed universal SEQUENCE with minimal tag and definite-length
     * encodings at every visited value, checking the shared inspection deadline at each node.
     *
     * <p>Universal primitive types cannot use constructed encodings. Reserved universal tags,
     * non-minimal high-tag forms, and tag numbers exceeding a signed integer are refused. The
     * depth and node limits include the root. X.509 parsing and its overlapping consumption and
     * encoded-byte checks follow this walk as defence in depth.
     */
    static void requireValid(byte[] content, Runnable deadlineCheck) {
        SignatureCertificateDerFraming framing =
            new SignatureCertificateDerFraming(content, deadlineCheck);
        if (content.length < 2 || content.length > MAX_BYTES || content[0] != 0x30
                || framing.walkValue(0, content.length, 1) != content.length) {
            throw UnsupportedUploadMediaTypeException.unsupported();
        }
    }

    private int walkValue(int offset, int end, int depth) {
        deadlineCheck.run();
        if (depth > MAX_DEPTH || ++nodes > MAX_NODES || end - offset < 2) {
            throw UnsupportedUploadMediaTypeException.unsupported();
        }
        int identifier = Byte.toUnsignedInt(content[offset++]);
        boolean constructed = (identifier & 0x20) != 0;
        int tagNumber = identifier & 0x1f;
        if (tagNumber == 0x1f) {
            if (offset >= end || (content[offset] & 0x7f) == 0) {
                throw UnsupportedUploadMediaTypeException.unsupported();
            }
            tagNumber = 0;
            int tagOctet;
            do {
                if (offset >= end || tagNumber > (Integer.MAX_VALUE >>> 7)) {
                    throw UnsupportedUploadMediaTypeException.unsupported();
                }
                tagOctet = Byte.toUnsignedInt(content[offset++]);
                tagNumber = (tagNumber << 7) | (tagOctet & 0x7f);
            } while ((tagOctet & 0x80) != 0);
            if (tagNumber < 0x1f) {
                throw UnsupportedUploadMediaTypeException.unsupported();
            }
        }
        if ((identifier & 0xc0) == 0) {
            if (tagNumber == 0 || tagNumber == 15 || tagNumber > 36) {
                throw UnsupportedUploadMediaTypeException.unsupported();
            }
            boolean constructedType = switch (tagNumber) {
                case 8, 11, 16, 17, 29 -> true;
                default -> false;
            };
            if (constructed != constructedType) {
                throw UnsupportedUploadMediaTypeException.unsupported();
            }
        }
        if (offset >= end) {
            throw UnsupportedUploadMediaTypeException.unsupported();
        }
        int firstLength = Byte.toUnsignedInt(content[offset++]);
        long bodyLength = firstLength;
        if (firstLength >= 0x80) {
            int lengthOctets = firstLength & 0x7f;
            if (lengthOctets == 0 || lengthOctets > 4 || lengthOctets > end - offset
                    || content[offset] == 0) {
                throw UnsupportedUploadMediaTypeException.unsupported();
            }
            bodyLength = 0;
            for (int index = 0; index < lengthOctets; index++) {
                bodyLength = (bodyLength << 8) | Byte.toUnsignedInt(content[offset++]);
            }
            if (bodyLength < 0x80) {
                throw UnsupportedUploadMediaTypeException.unsupported();
            }
        }
        if (bodyLength > end - offset) {
            throw UnsupportedUploadMediaTypeException.unsupported();
        }
        int valueEnd = offset + (int) bodyLength;
        if (constructed) {
            while (offset < valueEnd) {
                offset = walkValue(offset, valueEnd, depth + 1);
            }
        }
        return valueEnd;
    }
}
