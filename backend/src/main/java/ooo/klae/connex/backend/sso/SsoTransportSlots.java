package ooo.klae.connex.backend.sso;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Bounds concurrent work per key and tracks at most 1,024 active keys. A user is registered under
 * the map lock before it can wait for a permit; only the last waiter or holder removes the entry.
 */
final class SsoTransportSlots {
    private static final int MAX_KEYS = 1024;

    private final ConcurrentHashMap<String, Slots> slots = new ConcurrentHashMap<>();
    private final Semaphore keys = new Semaphore(MAX_KEYS);
    private final Supplier<Semaphore> factory;

    SsoTransportSlots(int permits) {
        this(() -> new Semaphore(permits, true));
    }

    SsoTransportSlots(Supplier<Semaphore> factory) {
        this.factory = factory;
    }

    /** Acquires a permit within the remaining budget, retaining its entry throughout any wait. */
    Lease acquire(String key, long budgetNanos) throws IOException {
        Slots entry = slots.compute(key, (ignored, current) -> {
            if (current == null) {
                if (!keys.tryAcquire()) {
                    return null;
                }
                current = new Slots(factory.get(), new AtomicInteger());
            }
            current.users().incrementAndGet();
            return current;
        });
        if (entry == null) {
            throw new SsoTransportSaturatedException("OIDC transport has too many active destinations");
        }
        boolean acquired = false;
        try {
            acquired = entry.permits().tryAcquire(Math.max(0L, budgetNanos), TimeUnit.NANOSECONDS);
            if (!acquired) {
                throw new SsoTransportSaturatedException("OIDC transport is saturated");
            }
            return new Lease(key, entry);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SsoTransportException(SsoTransportException.Reason.INTERRUPTED, e);
        } finally {
            if (!acquired) {
                removeUser(key, entry);
            }
        }
    }

    private void removeUser(String key, Slots entry) {
        slots.computeIfPresent(key, (ignored, current) -> {
            if (current != entry) {
                throw new IllegalStateException("OIDC transport slot identity changed while in use");
            }
            if (entry.users().decrementAndGet() == 0) {
                keys.release();
                return null;
            }
            return current;
        });
    }

    private record Slots(Semaphore permits, AtomicInteger users) {
    }

    /** An idempotent release handle bound to the exact entry from which the permit was acquired. */
    final class Lease implements AutoCloseable {
        private final String key;
        private final Slots entry;
        private final AtomicBoolean released = new AtomicBoolean();

        private Lease(String key, Slots entry) {
            this.key = key;
            this.entry = entry;
        }

        @Override
        public void close() {
            if (released.compareAndSet(false, true)) {
                entry.permits().release();
                removeUser(key, entry);
            }
        }
    }
}
