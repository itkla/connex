package ooo.klae.connex.backend.delivery;

/**
 * A delivery provider that dispatches rendered messages to recipients itself.
 */
public interface MessageDispatcher extends DeliveryProvider {

    /**
     * Dispatches one rendered message synchronously and returns the provider's receipt. Implementations
     * must never throw for an ordinary provider rejection — they return a {@code REJECTED} receipt — and
     * may throw only for unexpected transport faults.
     * @param target the resolved provider configuration
     * @param request the recipient dispatch request
     * @return the dispatch receipt
     */
    DispatchReceipt dispatch(ResolvedDeliveryProvider target, DeliveryRequest request);
}
