package ooo.klae.connex.backend.ai.egress;

import java.io.BufferedReader;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.function.Consumer;

import ooo.klae.connex.backend.ai.provider.AiProviderException;
import ooo.klae.connex.backend.ai.provider.AiProviderIdleTimeoutException;

/** Bounded-transport SSE decoder supporting arbitrary chunks, CRLF, and multi-line data fields. */
public final class AiSseEventReader {
    private AiSseEventReader() {
    }

    /** Reads complete SSE data events until EOF. */
    public static void read(InputStream input, Consumer<String> eventConsumer) {
        read(input, eventConsumer, () -> {});
    }

    /** Reads complete SSE events and reports each successful raw transport read. */
    public static void read(
            InputStream input,
            Consumer<String> eventConsumer,
            Runnable transportActivity) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(eventConsumer, "eventConsumer");
        Objects.requireNonNull(transportActivity, "transportActivity");
        try {
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(
                            new ActivityInputStream(input, transportActivity),
                            StandardCharsets.UTF_8));
            StringBuilder data = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    dispatch(data, eventConsumer);
                    continue;
                }
                if (line.charAt(0) == ':') {
                    continue;
                }
                if (line.equals("data")) {
                    appendData(data, "");
                } else if (line.startsWith("data:")) {
                    String value = line.substring(5);
                    appendData(data, value.startsWith(" ") ? value.substring(1) : value);
                }
            }
            dispatch(data, eventConsumer);
        } catch (AiProviderException exception) {
            throw exception;
        } catch (IOException exception) {
            if (exception instanceof SocketTimeoutException) {
                throw new AiProviderIdleTimeoutException("AI provider stream became idle");
            }
            throw new AiProviderException("AI provider stream could not be decoded");
        }
    }

    private static void appendData(StringBuilder data, String value) {
        if (!data.isEmpty()) {
            data.append('\n');
        }
        data.append(value);
    }

    private static void dispatch(StringBuilder data, Consumer<String> eventConsumer) {
        if (data.isEmpty()) {
            return;
        }
        String event = data.toString();
        data.setLength(0);
        eventConsumer.accept(event);
    }

    private static final class ActivityInputStream extends FilterInputStream {
        private final Runnable transportActivity;

        private ActivityInputStream(InputStream input, Runnable transportActivity) {
            super(input);
            this.transportActivity = transportActivity;
        }

        @Override
        public int read() throws IOException {
            int value = super.read();
            if (value >= 0) {
                transportActivity.run();
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int count = super.read(buffer, offset, length);
            if (count > 0) {
                transportActivity.run();
            }
            return count;
        }
    }
}
