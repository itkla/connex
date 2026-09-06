package ooo.klae.connex.backend.delivery.provider.smtp;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

import jakarta.annotation.PreDestroy;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import ooo.klae.connex.backend.delivery.DeliveryCapabilities;
import ooo.klae.connex.backend.delivery.DeliveryChannel;
import ooo.klae.connex.backend.delivery.DeliveryRequest;
import ooo.klae.connex.backend.delivery.DispatchReceipt;
import ooo.klae.connex.backend.delivery.MessageDispatcher;
import ooo.klae.connex.backend.delivery.ResolvedDeliveryProvider;
import ooo.klae.connex.backend.mail.JavaMailSenderFactory;
import ooo.klae.connex.backend.mail.JavaMailSenderFactory.DeadlineBoundSender;
import ooo.klae.connex.backend.mail.ResolvedMailConfig;
import ooo.klae.connex.backend.mail.SmtpDestinationGuard;

/**
 * The built-in email dispatcher. It uses the exact mail transport resolved before the delivery
 * claim, pins guard-approved public workspace destinations through {@link SmtpDestinationGuard},
 * and retains the same deadline-bounded DNS path for explicitly allowed internal relays and
 * trusted instance defaults.
 * It sends synchronously — never {@code @Async} — so a send outcome is bounded and classified at
 * dispatch time before it is recorded against the delivery row.
 */
@Service
public class SmtpDeliveryProvider implements MessageDispatcher {

    /** The stable id this provider registers under. */
    public static final String PROVIDER_ID = "smtp";

    private static final DeliveryCapabilities CAPABILITIES =
            new DeliveryCapabilities(true, false, false, false, 1);
    private static final int DETAIL_LIMIT = 512;
    private static final int MAX_CONCURRENT_RESOLUTIONS = 2;
    private static final int RESOLVER_THREADS = MAX_CONCURRENT_RESOLUTIONS + 1;
    private static final long RESOLVER_ADMISSION_TIMEOUT_NANOS = TimeUnit.MILLISECONDS.toNanos(50);

    private final JavaMailSenderFactory javaMailSenderFactory;
    private final SmtpDestinationGuard smtpDestinationGuard;
    private final LongSupplier nanoTimeSource;
    private final HostnameResolver hostnameResolver;
    private final ScheduledThreadPoolExecutor deadlineExecutor = deadlineExecutor();
    private final ExecutorService resolverExecutor = resolverExecutor();
    private final Semaphore resolverSlots = new Semaphore(MAX_CONCURRENT_RESOLUTIONS, true);

    /**
     * Builds the production SMTP provider.
     * @param javaMailSenderFactory deadline-bound sender factory
     * @param smtpDestinationGuard SMTP egress policy and DNS guard
     */
    @Autowired
    public SmtpDeliveryProvider(
            JavaMailSenderFactory javaMailSenderFactory,
            SmtpDestinationGuard smtpDestinationGuard) {
        this(javaMailSenderFactory, smtpDestinationGuard, System::nanoTime);
    }

    SmtpDeliveryProvider(
            JavaMailSenderFactory javaMailSenderFactory,
            SmtpDestinationGuard smtpDestinationGuard,
            LongSupplier nanoTimeSource) {
        this(javaMailSenderFactory, smtpDestinationGuard, nanoTimeSource, InetAddress::getByName);
    }

    SmtpDeliveryProvider(
            JavaMailSenderFactory javaMailSenderFactory,
            SmtpDestinationGuard smtpDestinationGuard,
            LongSupplier nanoTimeSource,
            HostnameResolver hostnameResolver) {
        this.javaMailSenderFactory = javaMailSenderFactory;
        this.smtpDestinationGuard = smtpDestinationGuard;
        this.nanoTimeSource = nanoTimeSource;
        this.hostnameResolver = hostnameResolver;
    }

    @Override
    public String providerId() {
        return PROVIDER_ID;
    }

    @Override
    public Set<DeliveryChannel> channels() {
        return Set.of(DeliveryChannel.EMAIL);
    }

    @Override
    public DeliveryCapabilities capabilities() {
        return CAPABILITIES;
    }

    @Override
    public DispatchReceipt dispatch(ResolvedDeliveryProvider target, DeliveryRequest request) {
        Long deadlineNanos = request.providerDeadlineNanos();
        if (deadlineNanos == null) {
            return DispatchReceipt.rejected("SMTP request has no provider deadline");
        }
        if (expired(deadlineNanos)) {
            return DispatchReceipt.rejected("SMTP deadline expired before egress");
        }
        Cancellation cancellation = new Cancellation();
        ScheduledFuture<?> deadlineTask;
        try {
            deadlineTask = deadlineExecutor.schedule(
                    cancellation::abort,
                    remainingNanos(deadlineNanos),
                    TimeUnit.NANOSECONDS);
        } catch (RejectedExecutionException exception) {
            return DispatchReceipt.rejected("SMTP deadline enforcement is unavailable");
        }
        AtomicBoolean egressStarted = new AtomicBoolean();
        try {
            ResolvedMailConfig config = target.mailConfig();
            if (config == null || !config.usable()) {
                return DispatchReceipt.rejected("No usable mail transport is configured");
            }
            ResolvedDestination destination = resolve(config, deadlineNanos, cancellation);
            if (expired(deadlineNanos)) {
                return DispatchReceipt.rejected("SMTP deadline expired before egress");
            }
            DeadlineBoundSender deadlineBound =
                    javaMailSenderFactory.deadlineBoundForConfig(
                            config, destination.address(), destination.pinned(),
                            remainingNanos(deadlineNanos));
            cancellation.register(deadlineBound.abort());
            JavaMailSender sender = deadlineBound.sender();
            MimeMessage mime = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(
                    mime, request.content().bodyText() != null, "UTF-8");
            helper.setTo(request.address());
            helper.setSubject(request.content().subject());
            if (request.content().bodyText() != null) {
                helper.setText(request.content().bodyText(), request.content().bodyHtml());
            } else {
                helper.setText(request.content().bodyHtml(), true);
            }
            if (config.fromName() != null && !config.fromName().isBlank()) {
                helper.setFrom(new InternetAddress(config.fromAddress(), config.fromName(), "UTF-8"));
            } else {
                helper.setFrom(config.fromAddress());
            }
            if (request.dedupeKey() != null) {
                mime.setHeader("Idempotency-Key", request.dedupeKey());
                mime.setHeader("Message-ID", messageId(request.dedupeKey()));
            }
            if (expired(deadlineNanos)) {
                return DispatchReceipt.rejected("SMTP deadline expired before egress");
            }
            deadlineBound.connectAndSend(mime, () -> egressStarted.set(true));
            if (cancellation.triggered() || expired(deadlineNanos)) {
                return DispatchReceipt.ambiguous(
                        "SMTP request exceeded its hard deadline after egress began");
            }
            return DispatchReceipt.sent(null, "smtp accepted");
        } catch (Exception exception) {
            if (JavaMailSenderFactory.isDeadlineBeforeTransport(exception)) {
                return DispatchReceipt.rejected(
                        "SMTP deadline expired before transport creation");
            }
            if (egressStarted.get()) {
                return DispatchReceipt.ambiguous(cancellation.triggered() || expired(deadlineNanos)
                        ? "SMTP request exceeded its hard deadline after egress began"
                        : bounded(exception.getMessage()));
            }
            return DispatchReceipt.rejected(bounded(exception.getMessage()));
        } finally {
            deadlineTask.cancel(false);
            cancellation.clearResolution();
        }
    }

    @PreDestroy
    void shutdown() {
        deadlineExecutor.shutdownNow();
        resolverExecutor.shutdownNow();
    }

    /**
     * Resolves the destination address for one send on a bounded resolver.
     *
     * <p>Admission control covers workspace-supplied hosts only. Those are the operator-editable
     * destinations the guard exists for, and rejecting one when the resolver is busy is preferable
     * to queueing attacker-influenced lookups. A trusted instance default never had a resolver gate
     * before this path existed, so it is admitted without a permit and stays bounded by the send's
     * own absolute deadline instead of turning a cold resolver into a definitive rejection.
     *
     * <p>The permit count bounds how many resolver threads workspace lookups can hold, and the pool
     * carries one thread more than that, so an exempt instance-default lookup always has a thread to
     * run on rather than queueing behind saturated workspace lookups.
     *
     * <p>{@link InetAddress#getByName} is not interruptible, so cancelling a started lookup does not
     * return its permit until the platform resolver gives up. That bound is accepted: at most
     * {@code MAX_CONCURRENT_RESOLUTIONS} workspace lookups can be in flight, every waiting caller is
     * bounded by its own deadline, and both outcomes are not-sent and retryable.
     */
    private ResolvedDestination resolve(
            ResolvedMailConfig config, long deadlineNanos, Cancellation cancellation) {
        boolean admissionControlled = config.workspaceSupplied();
        if (admissionControlled) {
            acquireResolverSlot(deadlineNanos);
        }
        AtomicBoolean taskStarted = new AtomicBoolean();
        Future<ResolvedDestination> resolution;
        try {
            resolution = resolverExecutor.submit(() -> {
                if (!taskStarted.compareAndSet(false, true)) {
                    throw new IllegalStateException("SMTP deadline expired during host resolution");
                }
                try {
                    remainingNanos(deadlineNanos);
                    if (config.workspaceSupplied()) {
                        InetAddress approved = smtpDestinationGuard.resolveForSend(config);
                        if (approved != null) {
                            return new ResolvedDestination(approved, true);
                        }
                        if (!smtpDestinationGuard.allowsInternalHosts()) {
                            throw new IllegalStateException(
                                    "SMTP destination guard returned no approved address");
                        }
                    }
                    return new ResolvedDestination(
                            hostnameResolver.resolve(config.host()), false);
                } finally {
                    releaseResolverSlot(admissionControlled);
                }
            });
            cancellation.registerResolution(resolution);
        } catch (RejectedExecutionException exception) {
            releaseResolverSlot(admissionControlled);
            throw new IllegalStateException("SMTP resolver is unavailable", exception);
        }
        long remaining;
        try {
            remaining = remainingNanos(deadlineNanos);
        } catch (RuntimeException exception) {
            cancelResolution(resolution, taskStarted, admissionControlled);
            throw exception;
        }
        try {
            return resolution.get(remaining, TimeUnit.NANOSECONDS);
        } catch (CancellationException exception) {
            cancelResolution(resolution, taskStarted, admissionControlled);
            throw new IllegalStateException("SMTP deadline expired during host resolution", exception);
        } catch (TimeoutException exception) {
            cancelResolution(resolution, taskStarted, admissionControlled);
            throw new IllegalStateException("SMTP deadline expired during host resolution", exception);
        } catch (InterruptedException exception) {
            cancelResolution(resolution, taskStarted, admissionControlled);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("SMTP host resolution was interrupted", exception);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException("SMTP host resolution failed", cause);
        } finally {
            cancellation.clearResolution();
        }
    }

    private void acquireResolverSlot(long deadlineNanos) {
        boolean acquired;
        try {
            acquired = resolverSlots.tryAcquire(
                    Math.min(remainingNanos(deadlineNanos), RESOLVER_ADMISSION_TIMEOUT_NANOS),
                    TimeUnit.NANOSECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("SMTP host resolution was interrupted", exception);
        }
        if (!acquired) {
            throw new IllegalStateException(expired(deadlineNanos)
                    ? "SMTP deadline expired during host resolution"
                    : "SMTP resolver is saturated");
        }
    }

    private void releaseResolverSlot(boolean admissionControlled) {
        if (admissionControlled) {
            resolverSlots.release();
        }
    }

    private void cancelResolution(
            Future<ResolvedDestination> resolution, AtomicBoolean taskStarted,
            boolean admissionControlled) {
        resolution.cancel(true);
        if (taskStarted.compareAndSet(false, true)) {
            releaseResolverSlot(admissionControlled);
        }
    }

    private long remainingNanos(long deadlineNanos) {
        long remaining = deadlineNanos - nanoTimeSource.getAsLong();
        if (remaining <= 0) {
            throw new IllegalStateException("SMTP deadline expired before egress");
        }
        return remaining;
    }

    private boolean expired(long deadlineNanos) {
        return deadlineNanos - nanoTimeSource.getAsLong() <= 0;
    }

    private static String messageId(String dedupeKey) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(dedupeKey.getBytes(StandardCharsets.UTF_8));
            return "<" + HexFormat.of().formatHex(digest) + "@delivery.connex.invalid>";
        } catch (Exception exception) {
            throw new IllegalStateException("Could not derive the SMTP correlation Message-ID", exception);
        }
    }

    private static ScheduledThreadPoolExecutor deadlineExecutor() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(
                2,
                Thread.ofPlatform().daemon().name("delivery-smtp-deadline-", 0).factory());
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }

    private static ExecutorService resolverExecutor() {
        return Executors.newFixedThreadPool(
                RESOLVER_THREADS,
                Thread.ofPlatform().daemon().name("delivery-smtp-resolver-", 0).factory());
    }

    private static final class Cancellation {

        private final AtomicBoolean triggered = new AtomicBoolean();
        private volatile Future<?> resolution;
        private volatile Runnable transportAbort;

        private void registerResolution(Future<?> value) {
            resolution = value;
            if (triggered.get()) {
                value.cancel(true);
            }
        }

        private void clearResolution() {
            resolution = null;
        }

        private void register(Runnable value) {
            transportAbort = value;
            if (triggered.get()) {
                value.run();
            }
        }

        private void abort() {
            triggered.set(true);
            Future<?> currentResolution = resolution;
            if (currentResolution != null) {
                currentResolution.cancel(true);
            }
            Runnable currentTransportAbort = transportAbort;
            if (currentTransportAbort != null) {
                currentTransportAbort.run();
            }
        }

        private boolean triggered() {
            return triggered.get();
        }
    }

    private static String bounded(String message) {
        if (message == null || message.isBlank()) {
            return "smtp rejected";
        }
        String trimmed = message.trim();
        return trimmed.length() > DETAIL_LIMIT ? trimmed.substring(0, DETAIL_LIMIT) : trimmed;
    }

    @FunctionalInterface
    interface HostnameResolver {

        InetAddress resolve(String hostname) throws Exception;
    }

    private record ResolvedDestination(InetAddress address, boolean pinned) {

        private ResolvedDestination {
            Objects.requireNonNull(address, "address");
        }
    }
}
