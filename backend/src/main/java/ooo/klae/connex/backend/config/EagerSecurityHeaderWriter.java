package ooo.klae.connex.backend.config;

import org.springframework.security.config.ObjectPostProcessor;
import org.springframework.security.web.header.HeaderWriterFilter;

/**
 * Makes a chain write its security headers once, on the request thread, before the chain proceeds.
 *
 * <p>By default {@code HeaderWriterFilter} writes the headers twice. It wraps the response in an
 * {@code OnCommittedResponseWrapper} and writes them from a {@code finally} as the chain unwinds,
 * and the wrapper writes them again from its commit hook. A handler returning
 * {@code StreamingResponseBody} — every attachment, export and support-bundle download here —
 * commits the response from the async worker thread while the request thread is still unwinding, so
 * both writes run concurrently against one header map behind a non-volatile already-written flag.
 *
 * <p>In production that duplicates security headers at random. Under MockMvc, whose header map is a
 * {@code LinkedCaseInsensitiveMap} over a {@code HashMap} that checks its modification count, it
 * throws {@code ConcurrentModificationException} and fails the request outright (#1761). Both
 * upstream reports are dead ends: spring-framework#31543 was closed as invalid, and
 * spring-security#15510 has sat untriaged since August 2024.
 *
 * <p>The eager path writes the headers before {@code filterChain.doFilter} and installs no wrapper
 * and no commit hook, so no async worker can exist when they are written. There is no
 * {@code HeadersConfigurer} method for it, so it is applied as an object post-processor. It must be
 * a named type rather than a lambda: Spring Security resolves the post-processor's generic argument
 * to decide what it applies to, and a lambda erases it into every object the builder creates.
 */
final class EagerSecurityHeaderWriter implements ObjectPostProcessor<HeaderWriterFilter> {

    @Override
    public <O extends HeaderWriterFilter> O postProcess(O filter) {
        filter.setShouldWriteHeadersEagerly(true);
        return filter;
    }
}
