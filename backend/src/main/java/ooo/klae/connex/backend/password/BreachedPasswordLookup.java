package ooo.klae.connex.backend.password;

/**
 * Looks up a locally computed SHA-1 digest without retaining credential material.
 */
public interface BreachedPasswordLookup {
    boolean isBreached(String sha1Hex);
}
