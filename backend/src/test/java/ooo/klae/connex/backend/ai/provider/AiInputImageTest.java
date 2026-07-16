package ooo.klae.connex.backend.ai.provider;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AiInputImageTest {
    @Test
    void constructorAndAccessorDefensivelyCopyContent() {
        byte[] source = {(byte) 0xff, (byte) 0xd8, (byte) 0xff, 1};
        AiInputImage image = new AiInputImage("image/jpeg", source, 100, 50);

        source[3] = 9;
        byte[] firstRead = image.content();
        firstRead[3] = 8;

        assertArrayEquals(new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff, 1}, image.content());
        assertTrue(image.toString().contains("content=<redacted>"));
    }

    @Test
    void rejectsInvalidMediaSignatureAndBounds() {
        assertThrows(IllegalArgumentException.class,
                () -> new AiInputImage("image/png", new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff}, 1, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new AiInputImage("image/jpeg", new byte[] {1, 2, 3}, 1, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new AiInputImage("image/jpeg", new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff}, 0, 1));
    }
}
