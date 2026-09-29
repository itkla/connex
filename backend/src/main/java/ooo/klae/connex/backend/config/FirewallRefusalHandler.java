package ooo.klae.connex.backend.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.web.firewall.RequestRejectedException;
import org.springframework.security.web.firewall.RequestRejectedHandler;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import ooo.klae.connex.backend.exceptions.BadRequestException;

/**
 * Answers a firewall refusal on the browser plane without a container error dispatch.
 *
 * <p>Spring's {@code HttpStatusRequestRejectedHandler} refuses with {@code sendError}, which makes a
 * real servlet container ERROR-dispatch the request to {@code /error}. That dispatch re-enters the
 * security chain, where {@code /error} has no rule and so falls to
 * {@code anyRequest().authenticated()}: an anonymous caller receives the entry point's 401 in place
 * of the firewall's 400 (#1780). This handler writes the status and the body itself, the same way
 * every refusal inside the chain does, so the firewall's own status reaches the caller.
 *
 * <p>The refusal is deliberately uninformative. It carries the status and a fixed message, never the
 * rejection's reason and never any part of the request, because the caller that triggered the
 * firewall must learn nothing about which spelling was refused. Response headers are applied here
 * because the firewall runs ahead of the chain's header writers.
 *
 * <p>An already-committed response is left alone. This is the terminal handler for every
 * {@code RequestRejectedException} in the chain, not only the entry-time firewall check —
 * {@code RequestPathNormalizer} throws it from several filters as well — and on a committed response
 * the status is ignored while a write would append JSON to a body that has already gone out. No
 * reachable path was found; the guard is parity with {@code PublicApiErrorAdvice}, not a fix.
 */
public class FirewallRefusalHandler implements RequestRejectedHandler {

    private static final String REFUSAL_BODY = "{\"code\":\"" + BadRequestException.CODE
        + "\",\"message\":\"Request was rejected\"}";

    @Override
    public void handle(
            HttpServletRequest request,
            HttpServletResponse response,
            RequestRejectedException rejection) throws IOException {
        if (response.isCommitted()) {
            return;
        }
        SecurityResponseHeaders.apply(request, response);
        response.setStatus(HttpStatus.BAD_REQUEST.value());
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(REFUSAL_BODY);
    }
}
