package ooo.klae.connex.backend.webauthn;

import java.io.IOException;

import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Rejects oversized bodies on the passkey ceremony endpoints before Spring MVC buffers the
 * {@code @RequestBody} into heap. A genuine attestation or assertion payload is a few kilobytes;
 * anything materially larger is abuse. Runs ahead of the dispatcher so a declared oversized
 * {@code Content-Length} is refused with 413 without reading the stream. These endpoints are
 * unauthenticated ({@code /authenticate/**}) or session-authenticated, so this caps a cheap
 * pre-auth memory-pressure vector.
 */
@Component
public class WebAuthnRequestSizeFilter extends OncePerRequestFilter {

    private static final long MAX_BODY_BYTES = 64L * 1024L;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/auth/webauthn/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getContentLengthLong() > MAX_BODY_BYTES) {
            response.sendError(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
            return;
        }
        chain.doFilter(request, response);
    }
}
