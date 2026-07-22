package ooo.klae.connex.backend.delivery;

/**
 * Static declaration of what an installed delivery provider can do, used to fail closed before a
 * capability that a provider does not support is invoked.
 * @param nativeDispatch whether the provider dispatches messages itself
 * @param audienceSync whether the provider syncs audiences to an external destination
 * @param webhooks whether the provider emits delivery events over webhooks
 * @param maxBatch the largest number of recipients the provider accepts in one dispatch batch
 */
public record DeliveryCapabilities(
        boolean nativeDispatch,
        boolean audienceSync,
        boolean webhooks,
        int maxBatch) {
}
