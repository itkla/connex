package ooo.klae.connex.backend.ai.masking;

import java.util.Objects;

/** Text and its source, retained independently of provider serialization for the egress boundary. */
public record OutboundSegment(String text, Origin origin) {
    /** Static vocabulary is never member data; provider text cannot authorize member exposure. */
    public enum Origin {
        SERVER_STATIC,
        TENANT_DATA,
        PROVIDER_AUTHORED
    }

    public OutboundSegment {
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(origin, "origin");
    }

    @Override
    public String toString() {
        return "OutboundSegment[origin=" + origin + ", text=<redacted>]";
    }
}
