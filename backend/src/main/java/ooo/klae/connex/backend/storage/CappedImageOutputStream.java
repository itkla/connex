package ooo.klae.connex.backend.storage;

import java.io.IOException;
import java.util.Arrays;

import javax.imageio.stream.ImageOutputStreamImpl;

/**
 * Seek-capable in-memory image output that refuses writes before crossing a byte ceiling.
 */
public final class CappedImageOutputStream extends ImageOutputStreamImpl {
    private static final int MAX_ARRAY_SIZE = Integer.MAX_VALUE - 8;
    private static final int INITIAL_CAPACITY = 8_192;

    private final int maximumBytes;
    private byte[] buffer;
    private int length;

    /**
     * Creates an output bounded to the supplied maximum encoded size.
     *
     * @param maximumBytes largest permitted stream length
     */
    public CappedImageOutputStream(long maximumBytes) {
        if (maximumBytes <= 0 || maximumBytes > MAX_ARRAY_SIZE) {
            throw new IllegalArgumentException("maximumBytes is outside byte-array bounds");
        }
        this.maximumBytes = (int) maximumBytes;
        this.buffer = new byte[Math.min(this.maximumBytes, INITIAL_CAPACITY)];
    }

    @Override
    public int read() throws IOException {
        checkClosed();
        bitOffset = 0;
        if (streamPos >= length) {
            return -1;
        }
        return Byte.toUnsignedInt(buffer[(int) streamPos++]);
    }

    @Override
    public int read(byte[] destination, int offset, int requested) throws IOException {
        checkClosed();
        Objects.requireBounds(destination, offset, requested);
        bitOffset = 0;
        if (requested == 0) {
            return 0;
        }
        if (streamPos >= length) {
            return -1;
        }
        int available = Math.min(requested, length - (int) streamPos);
        System.arraycopy(buffer, (int) streamPos, destination, offset, available);
        streamPos += available;
        return available;
    }

    @Override
    public void write(int value) throws IOException {
        flushBits();
        int end = requiredEnd(1);
        ensureCapacity(end);
        buffer[(int) streamPos] = (byte) value;
        streamPos = end;
        length = Math.max(length, end);
    }

    @Override
    public void write(byte[] source, int offset, int requested) throws IOException {
        Objects.requireBounds(source, offset, requested);
        flushBits();
        int end = requiredEnd(requested);
        ensureCapacity(end);
        System.arraycopy(source, offset, buffer, (int) streamPos, requested);
        streamPos = end;
        length = Math.max(length, end);
    }

    @Override
    public void seek(long position) throws IOException {
        if (position > maximumBytes) {
            throw new LimitExceededException(maximumBytes);
        }
        super.seek(position);
    }

    @Override
    public long length() {
        return length;
    }

    @Override
    public boolean isCached() {
        return true;
    }

    @Override
    public boolean isCachedMemory() {
        return true;
    }

    /**
     * Copies the completed, within-limit encoding into its immutable result array.
     *
     * @return exact encoded bytes
     * @throws IOException when the stream is closed
     */
    public byte[] toByteArray() throws IOException {
        checkClosed();
        return Arrays.copyOf(buffer, length);
    }

    private int requiredEnd(int requested) throws LimitExceededException {
        long end = streamPos + requested;
        if (requested < 0 || end < streamPos || end > maximumBytes) {
            throw new LimitExceededException(maximumBytes);
        }
        return (int) end;
    }

    private void ensureCapacity(int required) {
        if (required <= buffer.length) {
            return;
        }
        int grown = Math.max(required, buffer.length + Math.max(1, buffer.length / 2));
        buffer = Arrays.copyOf(buffer, Math.min(maximumBytes, grown));
    }

    private static final class Objects {
        private Objects() {
        }

        private static void requireBounds(byte[] value, int offset, int length) {
            java.util.Objects.requireNonNull(value, "value");
            if (offset < 0 || length < 0 || offset > value.length - length) {
                throw new IndexOutOfBoundsException("Invalid byte-array range");
            }
        }
    }

    /**
     * Signals that an image writer attempted to cross the configured encoded-size ceiling.
     */
    public static final class LimitExceededException extends IOException {
        private static final long serialVersionUID = 1L;

        private LimitExceededException(long maximumBytes) {
            super("Encoded image exceeds " + maximumBytes + " bytes");
        }
    }
}
