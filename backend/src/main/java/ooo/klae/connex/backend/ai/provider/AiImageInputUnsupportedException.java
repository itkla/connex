package ooo.klae.connex.backend.ai.provider;

/** Signals that the configured provider target cannot receive embedded image input. */
public class AiImageInputUnsupportedException extends AiProviderException {

    public AiImageInputUnsupportedException() {
        super("Configured AI model does not support image input");
    }
}
