package ooo.klae.connex.backend.config;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
    private static final Set<String> BODY_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");
    private static final int MAX_FORM_PARAMETERS = 1_000;

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
        if (request.getContentLengthLong() > limitBytes) {
            reject(response);
            return;
        }
        BufferedRequestWrapper bufferedRequest = null;
        try {
            boolean unknownLength = request.getContentLengthLong() < 0
                || request.getHeader("Transfer-Encoding") != null;
            if (unknownLength && usesContainerBodyParsing(request)) {
                reject(response);
                return;
            }
            HttpServletRequest boundedRequest;
            if (unknownLength) {
                bufferedRequest = new BufferedRequestWrapper(request, limitBytes);
                boundedRequest = bufferedRequest;
            } else {
                boundedRequest = new CountingRequestWrapper(request, limitBytes);
            }
            chain.doFilter(boundedRequest, response);
        } catch (MalformedFormBodyException ex) {
            if (!response.isCommitted()) {
                response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            }
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
        } finally {
            if (bufferedRequest != null) {
                bufferedRequest.close();
            }
        }
    }

    private static void reject(HttpServletResponse response) {
        response.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
    }

    private long limitFor(HttpServletRequest request) {
        String path = apiPath(request);
        long routeLimit;
        if (path.equals("/api/imports") || path.startsWith("/api/imports/")) {
            routeLimit = properties.getImportMaxBodyBytes();
        } else if (path.equals("/api/business-cards") || path.startsWith("/api/business-cards/")) {
            routeLimit = properties.getBusinessCardMaxBodyBytes();
        } else if (isUploadPath(path)) {
            routeLimit = properties.getUploadMaxBodyBytes();
        } else if (path.equals("/api/auth/webauthn") || path.startsWith("/api/auth/webauthn/")) {
            routeLimit = properties.getWebauthnMaxBodyBytes();
        } else {
            routeLimit = properties.getMaxBodyBytes();
        }
        return isFormUrlEncoded(request)
            ? Math.min(routeLimit, properties.getFormMaxBodyBytes())
            : routeLimit;
    }

    private static boolean isUploadPath(String path) {
        return path.equals("/api/attachments/upload")
            || path.equals("/api/users/me/profile-picture")
            || path.matches("^/api/persons/\\d+/profile-picture$")
            || path.matches("^/api/companies/\\d+/logo$");
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

    private static boolean usesContainerBodyParsing(HttpServletRequest request) {
        String contentType = request.getContentType();
        if (contentType == null) {
            return false;
        }
        String normalized = contentType.toLowerCase(Locale.ROOT);
        return normalized.startsWith("multipart/");
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

    private static final class BufferedRequestWrapper extends HttpServletRequestWrapper implements AutoCloseable {
        private final Path bodyPath;
        private final long bodyLength;
        private final Map<String, String[]> formParameters;
        private ServletInputStream inputStream;
        private BufferedReader reader;

        BufferedRequestWrapper(HttpServletRequest request, long limitBytes) throws IOException {
            super(request);
            BodyFile body = readBody(request.getInputStream(), limitBytes);
            bodyPath = body.path();
            bodyLength = body.length();
            try {
                formParameters = isFormUrlEncoded(request)
                    ? readFormParameters(request, bodyPath)
                    : null;
            } catch (IOException | RuntimeException exception) {
                Files.deleteIfExists(bodyPath);
                throw exception;
            }
        }

        @Override
        public int getContentLength() {
            return bodyLength > Integer.MAX_VALUE ? -1 : (int) bodyLength;
        }

        @Override
        public long getContentLengthLong() {
            return bodyLength;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            if (inputStream == null) {
                inputStream = new BufferedBodyServletInputStream(Files.newInputStream(bodyPath), bodyLength);
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

        @Override
        public String getParameter(String name) {
            if (formParameters == null) return super.getParameter(name);
            String[] values = formParameters.get(name);
            return values == null || values.length == 0 ? null : values[0];
        }

        @Override
        public Map<String, String[]> getParameterMap() {
            if (formParameters == null) return super.getParameterMap();
            Map<String, String[]> copy = new LinkedHashMap<>();
            formParameters.forEach((key, values) -> copy.put(key, values.clone()));
            return Collections.unmodifiableMap(copy);
        }

        @Override
        public Enumeration<String> getParameterNames() {
            if (formParameters == null) return super.getParameterNames();
            return Collections.enumeration(formParameters.keySet());
        }

        @Override
        public String[] getParameterValues(String name) {
            if (formParameters == null) return super.getParameterValues(name);
            String[] values = formParameters.get(name);
            return values == null ? null : values.clone();
        }

        @Override
        public void close() throws IOException {
            try {
                if (inputStream != null) {
                    inputStream.close();
                }
            } finally {
                Files.deleteIfExists(bodyPath);
            }
        }

        private static BodyFile readBody(ServletInputStream input, long limitBytes) throws IOException {
            Path path = Files.createTempFile("connex-request-body-", ".tmp");
            byte[] chunk = new byte[8192];
            long bytesRead = 0;
            try (OutputStream output = Files.newOutputStream(path)) {
                int count;
                while ((count = input.read(chunk)) != -1) {
                    if (count > limitBytes - bytesRead) {
                        throw new RequestBodyTooLargeException(limitBytes);
                    }
                    output.write(chunk, 0, count);
                    bytesRead += count;
                }
                return new BodyFile(path, bytesRead);
            } catch (IOException | RuntimeException exception) {
                Files.deleteIfExists(path);
                throw exception;
            }
        }

        private static Map<String, String[]> readFormParameters(HttpServletRequest request, Path bodyPath)
                throws IOException {
            Charset charset = formCharset(request);
            Map<String, List<String>> values = new LinkedHashMap<>();
            int parameterCount = addFormParameters(values, request.getQueryString(), charset, 0);
            addFormParameters(values, readFormBody(bodyPath, charset), charset, parameterCount);
            Map<String, String[]> parameters = new LinkedHashMap<>();
            values.forEach((key, entries) -> parameters.put(key, entries.toArray(String[]::new)));
            return Collections.unmodifiableMap(parameters);
        }

        private static int addFormParameters(
                Map<String, List<String>> values, String encoded, Charset charset, int initialCount) {
            if (encoded == null || encoded.isEmpty()) return initialCount;
            int count = initialCount;
            int start = 0;
            while (start <= encoded.length()) {
                if (++count > MAX_FORM_PARAMETERS) {
                    throw new MalformedFormBodyException();
                }
                int end = encoded.indexOf('&', start);
                if (end < 0) end = encoded.length();
                String pair = encoded.substring(start, end);
                int separator = pair.indexOf('=');
                String rawName = separator < 0 ? pair : pair.substring(0, separator);
                String rawValue = separator < 0 ? "" : pair.substring(separator + 1);
                String name = decodeFormValue(rawName, charset);
                String value = decodeFormValue(rawValue, charset);
                values.computeIfAbsent(name, ignored -> new ArrayList<>()).add(value);
                if (end == encoded.length()) break;
                start = end + 1;
            }
            return count;
        }

        private static Charset formCharset(HttpServletRequest request) {
            try {
                return request.getCharacterEncoding() == null
                    ? StandardCharsets.UTF_8
                    : Charset.forName(request.getCharacterEncoding());
            } catch (IllegalArgumentException exception) {
                throw new MalformedFormBodyException(exception);
            }
        }

        private static String readFormBody(Path bodyPath, Charset charset) throws IOException {
            try {
                return Files.readString(bodyPath, charset);
            } catch (CharacterCodingException exception) {
                throw new MalformedFormBodyException(exception);
            }
        }

        private static String decodeFormValue(String value, Charset charset) {
            try {
                return URLDecoder.decode(value, charset);
            } catch (IllegalArgumentException exception) {
                throw new MalformedFormBodyException(exception);
            }
        }
    }

    private static boolean isFormUrlEncoded(HttpServletRequest request) {
        String contentType = request.getContentType();
        return contentType != null
            && contentType.toLowerCase(Locale.ROOT).startsWith("application/x-www-form-urlencoded");
    }

    private static final class MalformedFormBodyException extends RuntimeException {
        MalformedFormBodyException() {
        }

        MalformedFormBodyException(Throwable cause) {
            super(cause);
        }
    }

    private record BodyFile(Path path, long length) {}

    private static final class BufferedBodyServletInputStream extends ServletInputStream {
        private final InputStream delegate;
        private ReadListener readListener;
        private boolean allDataRead;
        private long remaining;

        BufferedBodyServletInputStream(InputStream delegate, long length) {
            this.delegate = delegate;
            remaining = length;
        }

        @Override
        public int read() throws IOException {
            int value = delegate.read();
            if (value != -1) remaining--;
            notifyAllDataRead();
            return value;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int count = delegate.read(b, off, len);
            if (count > 0) remaining -= count;
            notifyAllDataRead();
            return count;
        }

        @Override
        public boolean isFinished() {
            return remaining == 0;
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void setReadListener(ReadListener readListener) {
            if (readListener == null) {
                throw new IllegalArgumentException("readListener is required");
            }
            this.readListener = readListener;
            try {
                if (isFinished()) {
                    notifyAllDataRead();
                } else {
                    readListener.onDataAvailable();
                    notifyAllDataRead();
                }
            } catch (IOException exception) {
                readListener.onError(exception);
            }
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }

        private void notifyAllDataRead() throws IOException {
            if (!allDataRead && readListener != null && isFinished()) {
                allDataRead = true;
                readListener.onAllDataRead();
            }
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
