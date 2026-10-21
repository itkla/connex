package ooo.klae.connex.backend.services;

/**
 * Read-only notification operation reserved for NotificationMapper conversion (#485 L9b).
 * This invocation must never share Supplier with mutations: CodeQL merges callbacks through
 * their functional interface. Implementations perform no DML, locks, audit, events or outbound I/O.
 *
 * @param <T> the read result
 */
@FunctionalInterface
public interface NotificationRecipientRead<T> {
    /** Reads tenant data with one captured recipient scope on the enclosing transaction. */
    T read(NotificationRecipientScope scope);
}
