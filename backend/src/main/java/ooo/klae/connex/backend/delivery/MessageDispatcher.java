package ooo.klae.connex.backend.delivery;

/**
 * A delivery provider that dispatches rendered messages to recipients itself.
 */
public interface MessageDispatcher extends DeliveryProvider {

    /**
     * Dispatches one rendered message synchronously and returns the provider's receipt. Implementations
     * must never throw for an ordinary provider rejection — they return a {@code REJECTED} receipt —
     * and must return {@code AMBIGUOUS} whenever provider acceptance is unknown after egress begins.
     * Recoverable deliveries carry one absolute monotonic provider deadline which implementations
     * must enforce without deriving a new deadline from a duration.
     * @param target the resolved provider configuration
     * @param request the recipient dispatch request
     * @return the dispatch receipt
     */
    DispatchReceipt dispatch(ResolvedDeliveryProvider target, DeliveryRequest request);
}
