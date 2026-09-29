package ooo.klae.connex.backend.sso;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import jakarta.annotation.PreDestroy;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.ClassicHttpRequest;
import org.apache.hc.core5.io.CloseMode;
import org.apache.hc.core5.util.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.AbstractBufferingClientHttpRequest;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import ooo.klae.connex.backend.ai.egress.PinnedHostDnsResolver;

/**
 * OIDC transport with per-request address pinning, no redirects or retries, a ten-second
 * deadline including DNS and body reads, and a one-MiB response ceiling.
 *
 * <p>Enterprise DNS and egress each use {@code max(4, availableProcessors)} workers and a queue
 * of four tasks per worker. A host may occupy {@code max(2, workers / 2)} tasks across those pools.
 * An organization may occupy at most two tasks across all enterprise stages and both pools, keyed
 * by its canonical organization ID. Running tasks retain capacity after timeout until they exit.
 * Registration resolution additionally admits two concurrent callers per organization. Consumer
 * social egress has its own four-worker pool, sixteen-task queue, and four permits per host, so
 * enterprise traffic cannot occupy its workers or shared-provider host permits. Each limiter
 * tracks at most 1,024 active keys, including waiters. Capacity failures remain distinct from policy.
 *
 * <p>The private-destination exemption is not global: {@link #createRequest} always applies the
 * strict policy, and only {@link #createEnterpriseRequest} and enterprise destination checks
 * honour {@code connex.sso.allow-private-issuer-hosts}.
 */
@Component
public class SsoHttpClient implements ClientHttpRequestFactory, AutoCloseable {

    static final int MAX_RESPONSE_BYTES = 1024 * 1024;
    static final int WORKERS = Math.max(4, Runtime.getRuntime().availableProcessors());
    static final int SLOTS_PER_DESTINATION = Math.max(2, WORKERS / 2);
    static final int SOCIAL_WORKERS = 4;
    private static final int MAX_REQUEST_BYTES = 64 * 1024;
    private static final int QUEUE_DEPTH_PER_WORKER = 4;
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
    private static final Logger log = LoggerFactory.getLogger(SsoHttpClient.class);

    private final SsoProperties properties;
    private final SsoUrlSafety.HostResolver resolver;
    private final Duration requestTimeout;
    private final ThreadPoolExecutor egressWorkers = pool("sso-http-", WORKERS);
    private final ThreadPoolExecutor resolutionWorkers = pool("sso-dns-", WORKERS);
    private final ThreadPoolExecutor socialWorkers = pool("sso-social-", SOCIAL_WORKERS);
    private final SsoTransportSlots destinationSlots = new SsoTransportSlots(SLOTS_PER_DESTINATION);
    private final SsoTransportSlots organizationSlots = new SsoTransportSlots(2);
    private final SsoTransportSlots socialSlots = new SsoTransportSlots(SOCIAL_WORKERS);

    @Autowired
    public SsoHttpClient(SsoProperties properties) {
        this(properties, InetAddress::getAllByName, REQUEST_TIMEOUT);
    }

    SsoHttpClient(SsoProperties properties, SsoUrlSafety.HostResolver resolver, Duration requestTimeout) {
        this.properties = properties;
        this.resolver = resolver;
        this.requestTimeout = requestTimeout;
    }

    /**
     * Validates an enterprise issuer and every endpoint discovered for it in one bounded task, so
     * a warm resolution costs a single unit of transport capacity.
     * @param orgId the canonical organization owning the registration
     * @param urls the issuer followed by its discovered destinations
     * @throws IllegalArgumentException when any destination is refused by policy
     * @throws UncheckedIOException when capacity, deadline, interruption, or transport failure prevents validation
     */
    public void requireSafeEnterpriseDestinations(int orgId, List<String> urls) {
        if (urls.isEmpty()) {
            throw new IllegalArgumentException("OIDC destination is not a permitted URL");
        }
        if (properties.isAllowPrivateIssuerHosts()) {
            for (String url : urls) {
                if (!SsoUrlSafety.isWellFormedHttpUrl(url)) {
                    throw new IllegalArgumentException("OIDC destination is not a permitted URL");
                }
            }
            return;
        }
        Cancellation cancellation = new Cancellation();
        try {
            bounded(resolutionWorkers, destinationSlots, orgId, destinationKey(urls.get(0)), cancellation, () -> {
                for (String url : urls) {
                    resolve(url, false);
                }
                return Boolean.TRUE;
            });
        } catch (SsoUrlSafety.RefusedDestinationException e) {
            throw new IllegalArgumentException("OIDC destination is not permitted", e);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Fetches enterprise discovery metadata without exposing the framework's unguarded client.
     * @param orgId the canonical organization owning the registration
     * @param uri the discovery document URI
     * @return the parsed metadata
     */
    public Map<String, Object> metadata(int orgId, URI uri) {
        RestTemplate rest = new RestTemplate((target, method) -> createEnterpriseRequest(orgId, target, method));
        return rest.exchange(uri, HttpMethod.GET, null,
                new ParameterizedTypeReference<Map<String, Object>>() { }).getBody();
    }

    @Override
    public ClientHttpRequest createRequest(URI uri, HttpMethod method) {
        return guardedRequest(uri, method, false, socialWorkers, socialSlots, null);
    }

    /**
     * Binds callback transport to the canonical organization encoded by an enterprise registration.
     * @param registrationId the server-resolved enterprise registration ID
     * @return a request factory sharing that organization's capacity across every provider stage
     */
    public ClientHttpRequestFactory forEnterpriseRegistration(String registrationId) {
        Integer orgId = DbClientRegistrationRepository.parseOrgId(registrationId);
        if (orgId == null) {
            throw new IllegalArgumentException("OIDC enterprise registration is invalid");
        }
        return (uri, method) -> createEnterpriseRequest(orgId, uri, method);
    }

    /**
     * Creates a request bound to an enterprise (per-organization) destination, where a deployment
     * may have opted its on-premises IdP out of the private-address restriction.
     * @param orgId the canonical organization owning the registration
     * @param uri the destination URI
     * @param method the HTTP method
     * @return the guarded request
     */
    public ClientHttpRequest createEnterpriseRequest(int orgId, URI uri, HttpMethod method) {
        return guardedRequest(uri, method, properties.isAllowPrivateIssuerHosts(), egressWorkers, destinationSlots, orgId);
    }

    private ClientHttpRequest guardedRequest(URI uri, HttpMethod method, boolean allowPrivate,
            ThreadPoolExecutor workers, SsoTransportSlots slots, Integer orgId) {
        return new AbstractBufferingClientHttpRequest() {
            @Override
            public HttpMethod getMethod() {
                return method;
            }

            @Override
            public URI getURI() {
                return uri;
            }

            @Override
            protected ClientHttpResponse executeInternal(HttpHeaders headers, byte[] body) throws IOException {
                if (body.length > MAX_REQUEST_BYTES) {
                    throw new IOException("OIDC request exceeded the size limit");
                }
                Cancellation cancellation = new Cancellation();
                return bounded(workers, slots, orgId, destinationKey(uri.toString()), cancellation,
                        () -> exchange(uri, method, headers, body, allowPrivate, cancellation));
            }
        };
    }

    private ClientHttpResponse exchange(URI uri, HttpMethod method, HttpHeaders headers, byte[] body,
            boolean allowPrivate, Cancellation cancellation) throws IOException {
        InetAddress[] addresses = resolve(uri.toString(), allowPrivate);
        try (CloseableHttpClient client = pinnedClient(uri.getHost(), addresses)) {
            cancellation.register(client);
            ClientHttpRequest request = new HttpComponentsClientHttpRequestFactory(client) {
                @Override
                protected ClassicHttpRequest createHttpUriRequest(HttpMethod requestMethod, URI requestUri) {
                    HttpUriRequestBase outbound = new HttpUriRequestBase(requestMethod.name(), requestUri);
                    cancellation.register(outbound);
                    return outbound;
                }
            }.createRequest(uri, method);
            request.getHeaders().putAll(headers);
            request.getBody().write(body);
            try (ClientHttpResponse response = request.execute()) {
                if (response.getStatusCode().is3xxRedirection()) {
                    throw new IOException("OIDC redirects are not permitted");
                }
                byte[] content = response.getBody().readNBytes(MAX_RESPONSE_BYTES + 1);
                if (content.length > MAX_RESPONSE_BYTES) {
                    throw new IOException("OIDC response exceeded the size limit");
                }
                return new BufferedResponse(response.getStatusCode(), response.getStatusText(),
                        HttpHeaders.copyOf(response.getHeaders()), content);
            }
        }
    }

    private InetAddress[] resolve(String url, boolean allowPrivate) throws IOException {
        return SsoUrlSafety.resolveFetchableHttpUrl(url, allowPrivate, resolver);
    }

    /** Builds an isolated request client that resolves only the admitted address set. */
    CloseableHttpClient pinnedClient(String host, InetAddress[] addresses) {
        var manager = PoolingHttpClientConnectionManagerBuilder.create()
                .setDnsResolver(new PinnedHostDnsResolver(unbracketed(host), addresses))
                .setDefaultConnectionConfig(ConnectionConfig.custom()
                        .setConnectTimeout(Timeout.ofSeconds(3))
                        .setSocketTimeout(Timeout.ofSeconds(5)).build())
                .setMaxConnPerRoute(1).setMaxConnTotal(1).build();
        return HttpClients.custom().setConnectionManager(manager)
                .setDefaultRequestConfig(RequestConfig.custom()
                        .setConnectionRequestTimeout(Timeout.ofSeconds(3))
                        .setResponseTimeout(Timeout.ofSeconds(5)).build())
                .disableRedirectHandling().disableAutomaticRetries().disableCookieManagement().build();
    }

    /**
     * Transfers both leases to the callable, which releases them after work stops and before its result
     * is published. Cancellation releases them in done() only if it claims ownership before the callable.
     */
    private <T> T bounded(ThreadPoolExecutor pool, SsoTransportSlots slots, Integer orgId, String destination,
            Cancellation cancellation, Callable<T> operation) throws IOException {
        long deadline = System.nanoTime() + requestTimeout.toNanos();
        List<SsoTransportSlots.Lease> leases = new ArrayList<>(2);
        try {
            if (orgId != null) {
                leases.add(organizationSlots.acquire(Integer.toString(orgId), 0));
            }
            leases.add(slots.acquire(destination, remaining(deadline)));
        } catch (IOException | RuntimeException e) {
            leases.forEach(SsoTransportSlots.Lease::close);
            throw e;
        }
        AtomicBoolean claimed = new AtomicBoolean();
        FutureTask<T> future = new FutureTask<>(() -> {
            if (!claimed.compareAndSet(false, true)) {
                throw new IOException("OIDC request expired before egress");
            }
            try {
                cancellation.requireLive();
                return operation.call();
            } finally {
                leases.forEach(SsoTransportSlots.Lease::close);
            }
        }) {
            @Override
            protected void done() {
                if (claimed.compareAndSet(false, true)) {
                    leases.forEach(SsoTransportSlots.Lease::close);
                }
            }
        };
        try {
            pool.execute(future);
        } catch (RejectedExecutionException e) {
            future.cancel(false);
            log.warn("OIDC transport queue is full");
            throw new SsoTransportSaturatedException("OIDC transport is saturated");
        }
        try {
            return future.get(remaining(deadline), TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            cancellation.abort();
            future.cancel(true);
            pool.remove(future);
            Thread.currentThread().interrupt();
            throw new SsoTransportException(SsoTransportException.Reason.INTERRUPTED, e);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof IOException io) {
                throw io;
            }
            throw new IOException("OIDC request failed", e.getCause());
        } catch (TimeoutException e) {
            cancellation.abort();
            future.cancel(true);
            pool.remove(future);
            throw new SsoTransportException(SsoTransportException.Reason.TIMEOUT, e);
        } finally {
            cancellation.abort();
        }
    }

    private static long remaining(long deadline) {
        return Math.max(0L, deadline - System.nanoTime());
    }

    private static String destinationKey(String url) {
        if (url == null) {
            return "";
        }
        try {
            String host = URI.create(url.trim()).getHost();
            return host == null ? url.trim() : unbracketed(host).toLowerCase(Locale.ROOT);
        } catch (RuntimeException e) {
            return url.trim();
        }
    }

    private static String unbracketed(String host) {
        return host != null && host.length() > 1 && host.charAt(0) == '['
                && host.charAt(host.length() - 1) == ']'
                ? host.substring(1, host.length() - 1) : host;
    }

    private static ThreadPoolExecutor pool(String namePrefix, int size) {
        ThreadPoolExecutor pool = new ThreadPoolExecutor(size, size, 30, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(size * QUEUE_DEPTH_PER_WORKER),
                Thread.ofPlatform().daemon().name(namePrefix, 0).factory());
        pool.allowCoreThreadTimeOut(true);
        return pool;
    }

    @Override
    @PreDestroy
    public void close() {
        egressWorkers.shutdownNow();
        resolutionWorkers.shutdownNow();
        socialWorkers.shutdownNow();
    }

    private record BufferedResponse(HttpStatusCode status, String statusText, HttpHeaders headers,
            byte[] content) implements ClientHttpResponse {
        @Override
        public HttpStatusCode getStatusCode() {
            return status;
        }

        @Override
        public String getStatusText() {
            return statusText;
        }

        @Override
        public HttpHeaders getHeaders() {
            return headers;
        }

        @Override
        public InputStream getBody() {
            return new ByteArrayInputStream(content);
        }

        @Override
        public void close() {
        }
    }

    private static final class Cancellation {
        private boolean aborted;
        private CloseableHttpClient client;
        private HttpUriRequestBase request;

        synchronized void requireLive() throws IOException {
            if (aborted) {
                throw new IOException("OIDC request expired before egress");
            }
        }

        synchronized void register(CloseableHttpClient value) throws IOException {
            if (aborted) {
                value.close(CloseMode.IMMEDIATE);
                throw new IOException("OIDC request expired before egress");
            }
            client = value;
        }

        synchronized void register(HttpUriRequestBase value) {
            request = value;
            if (aborted) {
                value.cancel();
            }
        }

        synchronized void abort() {
            aborted = true;
            if (request != null) {
                request.cancel();
            }
            if (client != null) {
                client.close(CloseMode.IMMEDIATE);
            }
        }
    }
}
