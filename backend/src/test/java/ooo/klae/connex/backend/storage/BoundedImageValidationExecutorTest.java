package ooo.klae.connex.backend.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

import ooo.klae.connex.backend.exceptions.UnsupportedUploadMediaTypeException;

class BoundedImageValidationExecutorTest {

    @Test
    void timeoutCancelsTheParserAndFailsClosed() {
        ExecutorService executor = Executors.newSingleThreadExecutor(
            Thread.ofPlatform().daemon().name("bounded-image-test").factory());
        AtomicBoolean parserAborted = new AtomicBoolean();
        try (BoundedImageValidationExecutor validationExecutor =
                new BoundedImageValidationExecutor(executor, Duration.ofMillis(20))) {
            assertThrows(UnsupportedUploadMediaTypeException.class,
                () -> validationExecutor.validate(
                    cancellation -> awaitCancellation(cancellation, parserAborted),
                    UnsupportedUploadMediaTypeException::unsupported));
            assertTrue(parserAborted.get());
            assertEquals("ready", validationExecutor.validate(
                cancellation -> "ready",
                UnsupportedUploadMediaTypeException::unsupported));
        }
    }

    private static String awaitCancellation(
            BoundedImageValidationExecutor.Cancellation cancellation,
            AtomicBoolean parserAborted) {
        cancellation.register(() -> parserAborted.set(true));
        while (!parserAborted.get()) {
            Thread.onSpinWait();
        }
        return "cancelled";
    }
}
