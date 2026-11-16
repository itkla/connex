package ooo.klae.connex.backend.support;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import ooo.klae.connex.backend.tenant.ControlCatalogConnection;

/** Counts only connections checked out within an explicitly observed disclosure request. */
public final class DisclosureConnectionTracker {
    private final ThreadLocal<Observation> current = new ThreadLocal<>();

    /** Installs an observation, or temporarily excludes diagnostic queries when it is null. */
    public Scope observe(Observation observation) {
        Observation previous = current.get();
        if (observation == null) {
            current.remove();
        } else {
            current.set(observation);
        }
        return () -> {
            if (previous == null) {
                current.remove();
            } else {
                current.set(previous);
            }
        };
    }

    /** Returns the observation belonging to the current request thread. */
    public Observation current() {
        return current.get();
    }

    /** Preserves routing interfaces and counts a return only after the delegate closes successfully. */
    public Connection track(Connection connection) {
        Observation observation = current.get();
        if (observation == null) {
            return connection;
        }
        observation.checkouts.incrementAndGet();
        observation.peak.accumulateAndGet(observation.active.incrementAndGet(), Math::max);
        AtomicBoolean closed = new AtomicBoolean();
        Class<?> contract = connection instanceof ControlCatalogConnection
            ? ControlCatalogConnection.class : Connection.class;
        return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {contract},
            (proxy, method, arguments) -> {
                if (method.getName().equals("equals")) {
                    return proxy == arguments[0];
                }
                if (method.getName().equals("hashCode")) {
                    return System.identityHashCode(proxy);
                }
                if (method.getName().equals("close")) {
                    if (closed.get()) {
                        return null;
                    }
                    connection.close();
                    if (closed.compareAndSet(false, true)) {
                        observation.returns.incrementAndGet();
                        observation.active.decrementAndGet();
                    }
                    return null;
                }
                try {
                    return method.invoke(connection, arguments);
                } catch (InvocationTargetException exception) {
                    throw exception.getCause();
                }
            });
    }

    /** One request's successful checkouts, returns and peak outstanding connection leases. */
    public static final class Observation {
        private final AtomicInteger checkouts = new AtomicInteger();
        private final AtomicInteger returns = new AtomicInteger();
        private final AtomicInteger active = new AtomicInteger();
        private final AtomicInteger peak = new AtomicInteger();

        public int checkouts() { return checkouts.get(); }
        public int returns() { return returns.get(); }
        public int active() { return active.get(); }
        public int peak() { return peak.get(); }
    }

    /** Restores the previous request observation on its owning thread. */
    @FunctionalInterface
    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }
}
