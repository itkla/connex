package ooo.klae.connex.backend.storage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class CappedImageOutputStreamTest {
    @Test
    void supportsWriterBackpatchingWithoutDuplicatingTheBuffer() throws Exception {
        try (CappedImageOutputStream output = new CappedImageOutputStream(8)) {
            output.write(new byte[] {1, 2, 3, 4});
            output.seek(1);
            output.write(new byte[] {8, 9});

            assertEquals(4, output.length());
            assertArrayEquals(new byte[] {1, 8, 9, 4}, output.toByteArray());
        }
    }

    @Test
    void refusesCrossingTheLimitBeforeGrowingOrCopyingOversizedOutput() throws Exception {
        try (CappedImageOutputStream output = new CappedImageOutputStream(4)) {
            output.write(new byte[] {1, 2, 3, 4});

            assertThrows(CappedImageOutputStream.LimitExceededException.class,
                () -> output.write(5));
            assertThrows(CappedImageOutputStream.LimitExceededException.class,
                () -> output.seek(5));
            assertEquals(4, output.length());
            assertArrayEquals(new byte[] {1, 2, 3, 4}, output.toByteArray());
        }
    }
}
