package ooo.klae.connex.backend.mail;

import java.util.List;

import javax.naming.NamingException;

/**
 * Bounded TXT-only DNS resolution seam for advisory mail diagnostics.
 * Workspace override administrators influence the sender domain, so implementations
 * must not resolve any other record type and callers must never expose record content.
 */
public interface DnsTxtResolver {

    /**
     * Resolves normalized TXT strings for one validated DNS name.
     *
     * @param queryName validated DNS name
     * @return normalized TXT strings
     * @throws NamingException when the resolver or response cannot be used safely
     */
    List<String> resolveTxt(String queryName) throws NamingException;
}
