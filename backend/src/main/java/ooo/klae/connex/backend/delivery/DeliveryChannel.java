package ooo.klae.connex.backend.delivery;

/**
 * Outbound delivery channels. {@link #EMAIL} and {@link #SMS} are dispatchable; {@link #LINE} and
 * {@link #WHATSAPP} are declared for the data model and future providers but carry no implementation
 * yet. Each dispatchable channel defines the person field it addresses and the canonical form of that
 * address in {@link ChannelAddressNormalizer}.
 */
public enum DeliveryChannel {
    EMAIL,
    SMS,
    LINE,
    WHATSAPP;

    /**
     * Returns the lower-case wire token persisted in {@code channel} columns.
     * @return the persisted channel token
     */
    public String token() {
        return name().toLowerCase();
    }

    /**
     * Resolves a persisted channel token to its enum constant.
     * @param token the persisted channel token
     * @return the matching channel
     * @throws DeliveryProviderException when the token is unknown
     */
    public static DeliveryChannel fromToken(String token) {
        if (token == null || token.isBlank()) {
            throw new DeliveryProviderException("Delivery channel is required");
        }
        for (DeliveryChannel channel : values()) {
            if (channel.token().equals(token.trim().toLowerCase())) {
                return channel;
            }
        }
        throw new DeliveryProviderException("Unknown delivery channel " + token);
    }
}
