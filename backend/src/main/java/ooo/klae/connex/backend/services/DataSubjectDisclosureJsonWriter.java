package ooo.klae.connex.backend.services;

import java.io.IOException;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import org.springframework.stereotype.Component;

import tools.jackson.core.JsonGenerator;
import tools.jackson.core.StreamWriteFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectWriter;
import tools.jackson.databind.SerializationFeature;

import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.dto.DataSubjectDisclosureDto;
import ooo.klae.connex.backend.dto.DataSubjectDisclosureNotes.Observer;
import ooo.klae.connex.backend.exceptions.DataSubjectDisclosureStreamException;

/** Uses the same compact UTF-8 serialization for preflight and synchronous release. */
@Component
@RequiredArgsConstructor
public class DataSubjectDisclosureJsonWriter {
    private final ObjectMapper objectMapper;

    /** Counts and fingerprints a complete representation while discarding every payload byte. */
    public Fingerprint measure(DataSubjectDisclosureDto disclosure) {
        MeasuringOutput output = new MeasuringOutput(OutputStream.nullOutputStream());
        serialize(disclosure, output);
        return output.fingerprint();
    }

    /** Releases only after the independently committed audit, withholding final JSON completion. */
    public void write(DataSubjectDisclosureDto disclosure, Fingerprint expected, Output target) {
        try {
            MeasuringOutput output = new MeasuringOutput(target.open(expected));
            serialize(disclosure, output);
            if (!expected.equals(output.fingerprint())) {
                throw new IllegalStateException("Disclosure serialization changed between snapshot passes");
            }
            output.complete();
        } catch (IOException | RuntimeException failure) {
            throw new DataSubjectDisclosureStreamException(failure);
        }
    }

    private void serialize(DataSubjectDisclosureDto disclosure, MeasuringOutput output) {
        ObjectWriter writer = objectMapper.writer()
            .without(SerializationFeature.WRAP_EXCEPTIONS)
            .without(StreamWriteFeature.AUTO_CLOSE_CONTENT)
            .without(StreamWriteFeature.AUTO_CLOSE_TARGET)
            .withAttribute(Observer.class, output);
        try (JsonGenerator generator = writer.createGenerator(output)) {
            writer.writeValue(generator, disclosure);
        }
    }

    /** Version-one hashes cover compact UTF-8 JSON before HTTP content coding. */
    public record Fingerprint(long noteCount, String notesSha256, long byteLength, String sha256) {}

    /** Opens the request-thread response only after preflight and durable auditing succeed. */
    @FunctionalInterface
    public interface Output {
        OutputStream open(Fingerprint fingerprint) throws IOException;
    }

    /**
     * Holds one final byte until verification, so even a late mismatch leaves incomplete JSON.
     * Generator cleanup may flush partial bytes but cannot close JSON content or the servlet target.
     */
    private static final class MeasuringOutput extends OutputStream implements Observer {
        private final OutputStream target;
        private final MessageDigest payloadDigest = sha256();
        private final MessageDigest notesDigest = sha256();
        private long byteLength;
        private long noteCount;
        private int tail = -1;
        private boolean inNotes;
        private boolean notesComplete;

        private MeasuringOutput(OutputStream target) {
            this.target = target;
        }

        @Override
        public void write(int value) throws IOException {
            byte[] single = {(byte) value};
            write(single, 0, 1);
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            if (length == 0) {
                return;
            }
            payloadDigest.update(bytes, offset, length);
            if (inNotes) {
                notesDigest.update(bytes, offset, length);
            }
            byteLength = Math.addExact(byteLength, length);
            if (tail != -1) {
                target.write(tail);
            }
            target.write(bytes, offset, length - 1);
            tail = bytes[offset + length - 1] & 0xff;
        }

        @Override
        public void flush() throws IOException {
            target.flush();
        }

        @Override
        public void beginNotes() {
            if (inNotes || notesComplete) {
                throw new IllegalStateException("Disclosure must contain exactly one notes array");
            }
            notesDigest.update((byte) '[');
            inNotes = true;
        }

        @Override
        public void noteWritten() {
            noteCount = Math.incrementExact(noteCount);
        }

        @Override
        public void endNotes() {
            inNotes = false;
            notesComplete = true;
        }

        private Fingerprint fingerprint() {
            if (!notesComplete || inNotes || tail != '}') {
                throw new IllegalStateException("Disclosure serialization did not complete");
            }
            return new Fingerprint(noteCount, HexFormat.of().formatHex(notesDigest.digest()),
                byteLength, HexFormat.of().formatHex(payloadDigest.digest()));
        }

        private void complete() throws IOException {
            target.write(tail);
            tail = -1;
            target.flush();
        }

        private static MessageDigest sha256() {
            try {
                return MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException exception) {
                throw new IllegalStateException("SHA-256 is required", exception);
            }
        }
    }
}
