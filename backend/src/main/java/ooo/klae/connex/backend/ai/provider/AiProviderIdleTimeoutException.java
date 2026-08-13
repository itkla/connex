package ooo.klae.connex.backend.ai.provider;

/** Provider stream exceeded the configured interval without a decodable network event. */
public class AiProviderIdleTimeoutException extends AiProviderException {
    public AiProviderIdleTimeoutException(String message) {
        super(message);
    }
}
