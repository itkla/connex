package ooo.klae.connex.backend.observability;

import java.io.IOException;
import java.util.Enumeration;

import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Installs one injection-safe correlation identifier for every request and response.
 */
public class CorrelationIdFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String correlationId = inboundCorrelationId(request);
        response.setHeader(CorrelationIds.HEADER_NAME, correlationId);
        MDC.put(CorrelationIds.MDC_KEY, correlationId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(CorrelationIds.MDC_KEY);
        }
    }

    private static String inboundCorrelationId(HttpServletRequest request) {
        Enumeration<String> values = request.getHeaders(CorrelationIds.HEADER_NAME);
        if (values == null || !values.hasMoreElements()) {
            return CorrelationIds.generate();
        }
        String value = values.nextElement();
        if (values.hasMoreElements() || !CorrelationIds.isValid(value)) {
            return CorrelationIds.generate();
        }
        return value;
    }
}
