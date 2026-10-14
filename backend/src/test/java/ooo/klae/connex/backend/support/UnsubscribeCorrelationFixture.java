package ooo.klae.connex.backend.support;

import ooo.klae.connex.backend.util.OneTimeTokenDigest;

/** Deterministic synthetic bearers whose overlapping copies survive a single digest replacement. */
public record UnsubscribeCorrelationFixture(String first, String second) {
    /** Finds a self-overlapping first bearer and a second bearer sharing its boundary character. */
    public static UnsubscribeCorrelationFixture create() {
        for (int seed = 0; seed < 100_000; seed++) {
            String first = OneTimeTokenDigest.sha256("correlation-first-" + seed);
            char boundary = first.charAt(63);
            if (first.charAt(0) != boundary || OneTimeTokenDigest.sha256(first).charAt(63) != boundary) {
                continue;
            }
            for (int other = 0; other < 100_000; other++) {
                String second = OneTimeTokenDigest.sha256("correlation-second-" + other);
                if (second.charAt(0) == boundary) {
                    return new UnsubscribeCorrelationFixture(first, second);
                }
            }
        }
        throw new AssertionError("No deterministic overlapping bearer fixture found");
    }

    /** Returns copies overlapping by one character, which digest replacement can recreate. */
    public String identifier() {
        return first + second.substring(1);
    }

    /** Returns the owning bearer overlapping itself for the historical migration fixture. */
    public String selfOverlappingIdentifier() {
        return first + first.substring(1);
    }
}
