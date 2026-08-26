package ooo.klae.connex.backend.dto;

/**
 * One canonical address a delivery channel reaches a contact at, used to match address-only
 * suppressions that carry no contact link.
 *
 * @param channel the delivery channel token
 * @param address the canonical address for that channel
 */
public record ChannelAddressRef(String channel, String address) {
}
