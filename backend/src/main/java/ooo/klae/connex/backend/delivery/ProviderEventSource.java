package ooo.klae.connex.backend.delivery;

/**
 * A delivery provider that emits delivery events (opens, bounces, complaints) back to Connex.
 * Declared for the channel/provider SPI; no implementation ships in this slice.
 */
public interface ProviderEventSource extends DeliveryProvider {
}
