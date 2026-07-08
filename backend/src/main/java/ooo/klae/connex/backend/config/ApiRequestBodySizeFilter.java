package ooo.klae.connex.backend.config;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Set;

import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.exceptions.RequestBodyTooLargeException;

/**
 * Bounds API request bodies before Spring MVC or Jackson materializes them.
 */
@RequiredArgsConstructor
public class ApiRequestBodySizeFilter extends OncePerRequestFilter {
    private static final Set<String> BODY_METHODS = Set.of("POST", "PUT", "PATCH");

    private final RequestBodySizeProperties properties;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !apiPath(request).startsWith("/api/")
            || !BODY_METHODS.contains(request.getMethod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long limitBytes = limitFor(request);
        if (request.getHeader("Transfer-Encoding") != null) {
            reject(response);
            return;
        }
        if (request.getContentLengthLong() > limitBytes) {
            reject(response);
            return;
        }
        try {
            chain.doFilter(new CountingRequestWrapper(request, limitBytes), response);
        } catch (RequestBodyTooLargeException ex) {
            if (!response.isCommitted()) {
                reject(response);
            }
        } catch (ServletException ex) {
            if (hasRequestBodyTooLargeCause(ex) && !response.isCommitted()) {
                reject(response);
                return;
            }
            throw ex;
        }
    }

    private static void reject(HttpServletResponse response) {
        response.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
    }

    private long limitFor(HttpServletRequest request) {
        if (apiPath(request).startsWith("/api/auth/webauthn/")) {
            return properties.getWebauthnMaxBodyBytes();
        }
        return properties.getMaxBodyBytes();
    }

    private static String apiPath(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isBlank() && uri.startsWith(contextPath)) {
            uri = uri.substring(contextPath.length());
        }
        if (uri.startsWith("/api/")) {
            return uri;
        }
        String servletPath = request.getServletPath();
        String pathInfo = request.getPathInfo();
        String path = (servletPath == null ? "" : servletPath) + (pathInfo == null ? "" : pathInfo);
        return path.isBlank() ? uri : path;
    }

    private static boolean hasRequestBodyTooLargeCause(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof RequestBodyTooLargeException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static final class CountingRequestWrapper extends HttpServletRequestWrapper {
        private final long limitBytes;
        private ServletInputStream inputStream;
        private BufferedReader reader;

        CountingRequestWrapper(HttpServletRequest request, long limitBytes) {
            super(request);
            this.limitBytes = limitBytes;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            if (inputStream == null) {
                inputStream = new CountingServletInputStream(super.getInputStream(), limitBytes);
            }
            return inputStream;
        }

        @Override
        public BufferedReader getReader() throws IOException {
            if (reader == null) {
                Charset charset = getCharacterEncoding() == null
                    ? StandardCharsets.UTF_8
                    : Charset.forName(getCharacterEncoding());
                reader = new BufferedReader(new InputStreamReader(getInputStream(), charset));
            }
            return reader;
        }
    }

    private static final class CountingServletInputStream extends ServletInputStream {
        private final ServletInputStream delegate;
        private final long limitBytes;
        private long bytesRead;

        CountingServletInputStream(ServletInputStream delegate, long limitBytes) {
            this.delegate = delegate;
            this.limitBytes = limitBytes;
        }

        @Override
        public int read() throws IOException {
            int value = delegate.read();
            if (value != -1) {
                increment(1);
            }
            return value;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int count = delegate.read(b, off, len);
            if (count > 0) {
                increment(count);
            }
            return count;
        }

        @Override
        public boolean isFinished() {
            return delegate.isFinished();
        }

        @Override
        public boolean isReady() {
            return delegate.isReady();
        }

        @Override
        public void setReadListener(ReadListener readListener) {
            delegate.setReadListener(readListener);
        }

        private void increment(int count) throws RequestBodyTooLargeException {
            bytesRead += count;
            if (bytesRead > limitBytes) {
                throw new RequestBodyTooLargeException(limitBytes);
            }
        }
    }
}
