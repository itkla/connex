package ooo.klae.connex.backend.ai.egress;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

class AiSseEventReaderTest {
    @Test
    void decodesCrlfCommentsAndMultilineData() {
        String stream = ": keepalive\r\ndata: first\r\ndata: second\r\n\r\n"
                + "event: ignored\ndata: [DONE]\n\n";
        List<String> events = new ArrayList<>();

        AiSseEventReader.read(
                new ByteArrayInputStream(stream.getBytes(StandardCharsets.UTF_8)), events::add);

        assertEquals(List.of("first\nsecond", "[DONE]"), events);
    }

    @Test
    void reportsRawByteTransportActivityBeforeLineCompletion() {
        String stream = ": keepalive\nretry: 1000\ndata: done\n\n";
        AtomicInteger activity = new AtomicInteger();

        AiSseEventReader.read(
                new OneByteInputStream(stream.getBytes(StandardCharsets.UTF_8)),
                ignored -> {}, activity::incrementAndGet);

        assertEquals(stream.length(), activity.get());
    }

    private static final class OneByteInputStream extends InputStream {
        private final byte[] content;
        private int offset;

        private OneByteInputStream(byte[] content) {
            this.content = content;
        }

        @Override
        public int read() {
            return offset < content.length ? content[offset++] & 0xff : -1;
        }

        @Override
        public int read(byte[] buffer, int targetOffset, int length) throws IOException {
            int value = read();
            if (value < 0) {
                return -1;
            }
            buffer[targetOffset] = (byte) value;
            return 1;
        }
    }
}
