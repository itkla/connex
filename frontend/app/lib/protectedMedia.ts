const MANAGED_MEDIA_PATH = /^\/api\/(?:users\/[1-9]\d*\/profile-picture|persons\/[1-9]\d*\/profile-picture|companies\/[1-9]\d*\/logo)\/[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}\.(?:jpg|png|webp)$/;
const SAFE_IMAGE_TYPES = new Set(["image/jpeg", "image/png", "image/webp"]);
const DEFAULT_TTL_MS = 5 * 60 * 1000;
const DEFAULT_FAILURE_TTL_MS = 30 * 1000;
const DEFAULT_MAX_BYTES = 32 * 1024 * 1024;
const DEFAULT_MAX_ENTRIES = 96;
const DEFAULT_MAX_RESPONSE_BYTES = 25 * 1024 * 1024;
const DEFAULT_MAX_CONCURRENT = 3;
const DEFAULT_MAX_RETRIES = 2;
const MAX_RETRY_DELAY_MS = 1500;

export type ProtectedMediaClassification =
    | { kind: "protected"; path: string }
    | { kind: "direct"; source: string }
    | { kind: "invalid" };

export type ProtectedMediaFetcher = (
    path: string,
    workspaceId: number,
    signal: AbortSignal,
) => Promise<Response>;

export type ProtectedMediaCacheOptions = {
    identity: string;
    origin: string;
    workspaceId: number | null;
    fetcher: ProtectedMediaFetcher;
    createObjectUrl?: (blob: Blob) => string;
    revokeObjectUrl?: (url: string) => void;
    now?: () => number;
    random?: () => number;
    sleep?: (delayMs: number, signal: AbortSignal) => Promise<void>;
    maxConcurrent?: number;
    maxEntries?: number;
    maxBytes?: number;
    maxResponseBytes?: number;
    maxRetries?: number;
    ttlMs?: number;
    failureTtlMs?: number;
};

/** Returns whether a path is one of the canonical authenticated image resources. */
export function isProtectedMediaPath(path: string): boolean {
    return MANAGED_MEDIA_PATH.test(path);
}

/** Returns whether a URL path could cross the application's authenticated API boundary. */
export function isApplicationApiBoundaryPath(pathname: string): boolean {
    try {
        return decodeURIComponent(pathname).toLowerCase().startsWith("/api/");
    } catch {
        return true;
    }
}

type MediaStatus = "idle" | "queued" | "loading" | "ready" | "failed" | "deferred";

type MediaEntry = {
    key: string;
    path: string;
    status: MediaStatus;
    subscribers: Set<() => void>;
    controller: AbortController | null;
    objectUrl: string | null;
    size: number;
    requiredSize: number;
    expiresAt: number;
    lastAccessedAt: number;
};

type WaitingMediaEntry = {
    key: string;
    path: string;
    subscribers: Set<() => void>;
    lastAccessedAt: number;
};

class MediaResponseError extends Error {
    constructor(readonly status: number) {
        super(`Protected media request failed with status ${status}`);
    }
}

class MediaCapacityError extends Error {
    constructor(readonly requiredSize: number) {
        super("Protected media cache capacity is unavailable");
    }
}

function safeDirectSource(source: string): boolean {
    if (source.startsWith("/") && !source.startsWith("//")) return true;
    try {
        const parsed = new URL(source);
        return !parsed.username
            && !parsed.password
            && (parsed.protocol === "http:"
                || parsed.protocol === "https:"
                || parsed.protocol === "blob:"
                || parsed.protocol === "data:");
    } catch {
        return false;
    }
}

function isCanonicalManagedSource(source: string, parsed: URL): boolean {
    if (parsed.search || parsed.hash || parsed.username || parsed.password) return false;
    if (source.startsWith("/")) return source === parsed.pathname;
    return source === parsed.href;
}

/** Classifies a media source without ever upgrading a cross-origin URL into an authenticated request. */
export function classifyProtectedMediaSource(
    source: string | null | undefined,
    origin: string,
): ProtectedMediaClassification {
    if (!source || source.length > 2048 || /[\u0000-\u001f\u007f\\]/.test(source)) {
        return { kind: "invalid" };
    }
    let parsed: URL;
    try {
        parsed = new URL(source, origin);
    } catch {
        return { kind: "invalid" };
    }
    if (parsed.protocol === "blob:" || parsed.protocol === "data:") {
        return safeDirectSource(source) ? { kind: "direct", source } : { kind: "invalid" };
    }
    if (parsed.protocol !== "http:" && parsed.protocol !== "https:") {
        return { kind: "invalid" };
    }
    if (parsed.origin !== origin) {
        return safeDirectSource(source) ? { kind: "direct", source } : { kind: "invalid" };
    }
    if (isProtectedMediaPath(parsed.pathname)) {
        return isCanonicalManagedSource(source, parsed)
            ? { kind: "protected", path: parsed.pathname }
            : { kind: "invalid" };
    }
    if (isApplicationApiBoundaryPath(parsed.pathname)) return { kind: "invalid" };
    return safeDirectSource(source) ? { kind: "direct", source } : { kind: "invalid" };
}

async function cancelResponseBody(response: Response): Promise<void> {
    try {
        await response.body?.cancel();
    } catch {}
}

function defaultSleep(delayMs: number, signal: AbortSignal): Promise<void> {
    return new Promise((resolve, reject) => {
        if (signal.aborted) {
            reject(signal.reason instanceof Error ? signal.reason : new DOMException("Aborted", "AbortError"));
            return;
        }
        const handleAbort = () => {
            clearTimeout(timeout);
            reject(signal.reason instanceof Error ? signal.reason : new DOMException("Aborted", "AbortError"));
        };
        const timeout = setTimeout(() => {
            signal.removeEventListener("abort", handleAbort);
            resolve();
        }, delayMs);
        signal.addEventListener("abort", handleAbort, { once: true });
    });
}

function retryAfterDelay(response: Response, now: number): number | null {
    const value = response.headers.get("Retry-After");
    if (!value) return null;
    const seconds = Number(value);
    if (Number.isFinite(seconds) && seconds >= 0) {
        return Math.min(seconds * 1000, MAX_RETRY_DELAY_MS);
    }
    const date = Date.parse(value);
    if (!Number.isFinite(date)) return null;
    return Math.min(Math.max(date - now, 0), MAX_RETRY_DELAY_MS);
}

function abortError(error: unknown): boolean {
    return error instanceof Error && error.name === "AbortError";
}

async function boundedImageBlob(
    response: Response,
    contentType: string,
    maxBytes: number,
    signal: AbortSignal,
): Promise<Blob> {
    const contentLength = response.headers.get("Content-Length");
    if (contentLength && /^\d+$/.test(contentLength) && Number(contentLength) > maxBytes) {
        await cancelResponseBody(response);
        throw new MediaResponseError(413);
    }
    if (!response.body) {
        const blob = await response.blob();
        if (blob.size <= 0 || blob.size > maxBytes) throw new MediaResponseError(413);
        return blob;
    }
    const reader = response.body.getReader();
    const chunks: Uint8Array<ArrayBuffer>[] = [];
    let size = 0;
    try {
        for (;;) {
            if (signal.aborted) {
                throw signal.reason instanceof Error
                    ? signal.reason
                    : new DOMException("Aborted", "AbortError");
            }
            const { done, value } = await reader.read();
            if (done) break;
            size += value.byteLength;
            if (size > maxBytes) {
                await reader.cancel();
                throw new MediaResponseError(413);
            }
            chunks.push(new Uint8Array(value));
        }
    } finally {
        reader.releaseLock();
    }
    if (size === 0) throw new MediaResponseError(413);
    return new Blob(chunks, { type: contentType });
}

/**
 * Owns the bounded protected-image queue and object-URL lifecycle for one authenticated
 * user/workspace identity.
 */
export class ProtectedMediaCache {
    private readonly identity: string;
    private readonly origin: string;
    private readonly workspaceId: number | null;
    private readonly fetcher: ProtectedMediaFetcher;
    private readonly createObjectUrl: (blob: Blob) => string;
    private readonly revokeObjectUrl: (url: string) => void;
    private readonly now: () => number;
    private readonly random: () => number;
    private readonly sleep: (delayMs: number, signal: AbortSignal) => Promise<void>;
    private readonly maxConcurrent: number;
    private readonly maxEntries: number;
    private readonly maxBytes: number;
    private readonly maxResponseBytes: number;
    private readonly maxRetries: number;
    private readonly ttlMs: number;
    private readonly failureTtlMs: number;
    private readonly entries = new Map<string, MediaEntry>();
    private readonly waitingEntries = new Map<string, WaitingMediaEntry>();
    private queue: MediaEntry[] = [];
    private activeRequests = 0;
    private cleanupTimer: ReturnType<typeof setTimeout> | null = null;
    private disposed = false;

    constructor(options: ProtectedMediaCacheOptions) {
        this.identity = options.identity;
        this.origin = options.origin;
        this.workspaceId = options.workspaceId;
        this.fetcher = options.fetcher;
        this.createObjectUrl = options.createObjectUrl ?? URL.createObjectURL.bind(URL);
        this.revokeObjectUrl = options.revokeObjectUrl ?? URL.revokeObjectURL.bind(URL);
        this.now = options.now ?? Date.now;
        this.random = options.random ?? Math.random;
        this.sleep = options.sleep ?? defaultSleep;
        this.maxConcurrent = options.maxConcurrent ?? DEFAULT_MAX_CONCURRENT;
        this.maxEntries = options.maxEntries ?? DEFAULT_MAX_ENTRIES;
        this.maxBytes = options.maxBytes ?? DEFAULT_MAX_BYTES;
        this.maxResponseBytes = options.maxResponseBytes ?? DEFAULT_MAX_RESPONSE_BYTES;
        this.maxRetries = options.maxRetries ?? DEFAULT_MAX_RETRIES;
        this.ttlMs = options.ttlMs ?? DEFAULT_TTL_MS;
        this.failureTtlMs = options.failureTtlMs ?? DEFAULT_FAILURE_TTL_MS;
    }

    /** Reads the current renderable URL for a source, or null while protected media is unavailable. */
    getSnapshot = (source: string | null | undefined): string | null => {
        const classification = classifyProtectedMediaSource(source, this.origin);
        if (classification.kind === "direct") return classification.source;
        if (classification.kind !== "protected") return null;
        const entry = this.entries.get(this.entryKey(classification.path));
        return entry?.status === "ready" ? entry.objectUrl : null;
    };

    /** Returns the hydration-safe initial value without starting browser I/O. */
    getServerSnapshot = (source: string | null | undefined): string | null => {
        const classification = classifyProtectedMediaSource(source, this.origin);
        if (classification.kind !== "direct") return null;
        try {
            if (isApplicationApiBoundaryPath(new URL(classification.source).pathname)) return null;
        } catch {}
        return classification.source;
    };

    /** Retains a source, starts a single-flight load when needed, and subscribes to URL changes. */
    subscribe(source: string | null | undefined, listener: () => void): () => void {
        const classification = classifyProtectedMediaSource(source, this.origin);
        if (classification.kind !== "protected" || this.disposed || this.workspaceId === null) {
            return () => undefined;
        }
        const key = this.entryKey(classification.path);
        let entry = this.entries.get(key);
        const currentTime = this.now();
        if (
            entry
            && entry.subscribers.size === 0
            && entry.expiresAt > 0
            && entry.expiresAt <= currentTime
        ) {
            this.evict(entry);
            entry = undefined;
        }
        if (!entry) {
            const waitingEntry = this.waitingEntries.get(key);
            if (waitingEntry) {
                waitingEntry.subscribers.add(listener);
                waitingEntry.lastAccessedAt = currentTime;
                return () => this.unsubscribe(key, listener);
            }
            if (!this.ensureEntryCapacity()) {
                if (this.waitingEntries.size >= this.maxEntries) return () => undefined;
                this.waitingEntries.set(key, {
                    key,
                    path: classification.path,
                    subscribers: new Set([listener]),
                    lastAccessedAt: currentTime,
                });
                return () => this.unsubscribe(key, listener);
            }
            entry = {
                key,
                path: classification.path,
                status: "idle",
                subscribers: new Set(),
                controller: null,
                objectUrl: null,
                size: 0,
                requiredSize: 0,
                expiresAt: 0,
                lastAccessedAt: currentTime,
            };
            this.entries.set(entry.key, entry);
        }
        entry.subscribers.add(listener);
        entry.lastAccessedAt = currentTime;
        if (
            entry.status === "failed"
            && entry.expiresAt > 0
            && entry.expiresAt <= currentTime
        ) entry.status = "idle";
        if (entry.status === "idle") this.enqueue(entry);
        return () => this.unsubscribe(key, listener);
    }

    /** Drops a source whose bytes could not be decoded by the browser and exposes its fallback. */
    reject(source: string | null | undefined, expectedObjectUrl: string): void {
        const classification = classifyProtectedMediaSource(source, this.origin);
        if (classification.kind !== "protected") return;
        const entry = this.entries.get(this.entryKey(classification.path));
        if (
            !entry
            || entry.status !== "ready"
            || entry.objectUrl !== expectedObjectUrl
        ) return;
        this.abortEntry(entry);
        this.releaseObjectUrl(entry);
        entry.status = "failed";
        entry.requiredSize = 0;
        entry.expiresAt = 0;
        this.notify(entry);
        this.wakeOneCapacityWaiter();
    }

    /** Aborts and revokes the current identity snapshot, then reloads still-visible sources. */
    invalidate(): void {
        if (this.disposed) return;
        this.queue = [];
        for (const entry of this.entries.values()) {
            this.abortEntry(entry);
            this.releaseObjectUrl(entry);
            entry.status = "idle";
            entry.requiredSize = 0;
            entry.expiresAt = 0;
            this.notify(entry);
        }
        for (const entry of this.entries.values()) {
            if (entry.subscribers.size > 0) this.enqueue(entry);
        }
    }

    /** Permanently releases every request, timer, listener, and object URL owned by this cache. */
    dispose(): void {
        if (this.disposed) return;
        this.disposed = true;
        this.queue = [];
        if (this.cleanupTimer) clearTimeout(this.cleanupTimer);
        this.cleanupTimer = null;
        for (const entry of this.entries.values()) {
            this.abortEntry(entry);
            this.releaseObjectUrl(entry);
            this.notify(entry);
            entry.subscribers.clear();
        }
        for (const waitingEntry of this.waitingEntries.values()) {
            for (const subscriber of waitingEntry.subscribers) {
                try {
                    subscriber();
                } catch {}
            }
            waitingEntry.subscribers.clear();
        }
        this.entries.clear();
        this.waitingEntries.clear();
    }

    private enqueue(entry: MediaEntry): void {
        if (this.disposed || this.workspaceId === null || entry.status !== "idle") return;
        entry.status = "queued";
        this.queue.push(entry);
        this.drain();
    }

    private drain(): void {
        while (!this.disposed && this.activeRequests < this.maxConcurrent && this.queue.length > 0) {
            const entry = this.queue.shift();
            if (!entry || entry.status !== "queued") continue;
            if (entry.subscribers.size === 0) {
                entry.status = "idle";
                this.evict(entry);
                continue;
            }
            void this.load(entry);
        }
    }

    private async load(entry: MediaEntry): Promise<void> {
        if (this.workspaceId === null) return;
        const controller = new AbortController();
        entry.status = "loading";
        entry.controller = controller;
        this.activeRequests += 1;
        try {
            const response = await this.fetchWithRetry(entry.path, this.workspaceId, controller.signal);
            const contentType = response.headers.get("Content-Type")?.split(";", 1)[0].trim().toLowerCase();
            if (!contentType || !SAFE_IMAGE_TYPES.has(contentType)) {
                await cancelResponseBody(response);
                throw new MediaResponseError(415);
            }
            const blob = await boundedImageBlob(
                response,
                contentType,
                this.maxResponseBytes,
                controller.signal,
            );
            if (this.disposed || entry.controller !== controller || controller.signal.aborted) return;
            if (blob.size > this.maxBytes) throw new MediaResponseError(413);
            if (!this.ensureByteCapacity(blob.size, entry)) throw new MediaCapacityError(blob.size);
            const objectUrl = this.createObjectUrl(blob);
            this.releaseObjectUrl(entry);
            entry.objectUrl = objectUrl;
            entry.size = blob.size;
            entry.requiredSize = 0;
            entry.status = "ready";
            entry.expiresAt = this.now() + this.ttlMs;
            entry.lastAccessedAt = this.now();
            entry.controller = null;
            this.notify(entry);
            this.trim();
            this.wakeOneCapacityWaiter();
        } catch (error: unknown) {
            if (!this.disposed && entry.controller === controller && !abortError(error)) {
                entry.controller = null;
                if (error instanceof MediaCapacityError) {
                    entry.status = "deferred";
                    entry.requiredSize = error.requiredSize;
                    entry.expiresAt = 0;
                    entry.lastAccessedAt = this.now();
                } else {
                    const releasedReservation = entry.requiredSize > 0;
                    const transient = !(error instanceof MediaResponseError)
                        || error.status === 429
                        || error.status === 503;
                    entry.status = "failed";
                    entry.requiredSize = 0;
                    entry.expiresAt = transient ? this.now() + this.failureTtlMs : 0;
                    if (releasedReservation) this.wakeOneCapacityWaiter();
                }
                this.notify(entry);
                if (entry.expiresAt > 0) this.scheduleCleanup();
            }
        } finally {
            this.activeRequests -= 1;
            this.drain();
        }
    }

    private async fetchWithRetry(
        path: string,
        workspaceId: number,
        signal: AbortSignal,
    ): Promise<Response> {
        for (let attempt = 0; ; attempt += 1) {
            const response = await this.fetcher(path, workspaceId, signal);
            if (response.ok) return response;
            const retryable = response.status === 429 || response.status === 503;
            await cancelResponseBody(response);
            if (!retryable || attempt >= this.maxRetries) throw new MediaResponseError(response.status);
            const headerDelay = retryAfterDelay(response, this.now());
            const jitteredDelay = Math.min(200 * 2 ** attempt + this.random() * 100, MAX_RETRY_DELAY_MS);
            await this.sleep(headerDelay ?? jitteredDelay, signal);
        }
    }

    private abortEntry(entry: MediaEntry): void {
        entry.controller?.abort();
        entry.controller = null;
    }

    private releaseObjectUrl(entry: MediaEntry): void {
        if (entry.objectUrl) this.revokeObjectUrl(entry.objectUrl);
        entry.objectUrl = null;
        entry.size = 0;
    }

    private notify(entry: MediaEntry): void {
        for (const subscriber of entry.subscribers) {
            try {
                subscriber();
            } catch {}
        }
    }

    private entryKey(path: string): string {
        return `${this.identity}\u0000${path}`;
    }

    private unsubscribe(key: string, listener: () => void): void {
        const waitingEntry = this.waitingEntries.get(key);
        if (waitingEntry) {
            waitingEntry.subscribers.delete(listener);
            if (waitingEntry.subscribers.size === 0) this.waitingEntries.delete(key);
            return;
        }
        const entry = this.entries.get(key);
        if (!entry) return;
        entry.subscribers.delete(listener);
        entry.lastAccessedAt = this.now();
        if (
            entry.subscribers.size === 0
            && (
                entry.status === "idle"
                || entry.status === "queued"
                || entry.status === "loading"
                || entry.status === "deferred"
            )
        ) {
            this.queue = this.queue.filter((queued) => queued !== entry);
            entry.status = "idle";
            this.evict(entry);
        }
        this.trim();
        this.wakeOneCapacityWaiter();
    }

    private wakeOneCapacityWaiter(): void {
        if (this.disposed) return;
        const availableBytes = this.availableByteCapacity();
        const deferredEntry = [...this.entries.values()]
            .filter((entry) => (
                entry.status === "deferred"
                && entry.subscribers.size > 0
                && entry.requiredSize > 0
                && entry.requiredSize <= availableBytes
            ))
            .sort((left, right) => left.lastAccessedAt - right.lastAccessedAt)[0];
        if (deferredEntry) {
            deferredEntry.status = "idle";
            this.enqueue(deferredEntry);
            return;
        }
        if (availableBytes > 0) this.promoteOneWaitingEntry();
    }

    private promoteOneWaitingEntry(): void {
        const waitingEntries = [...this.waitingEntries.values()]
            .sort((left, right) => left.lastAccessedAt - right.lastAccessedAt);
        for (const waitingEntry of waitingEntries) {
            if (this.disposed) return;
            if (waitingEntry.subscribers.size === 0) {
                this.waitingEntries.delete(waitingEntry.key);
                continue;
            }
            if (!this.ensureEntryCapacity()) return;
            this.waitingEntries.delete(waitingEntry.key);
            const entry: MediaEntry = {
                key: waitingEntry.key,
                path: waitingEntry.path,
                status: "idle",
                subscribers: waitingEntry.subscribers,
                controller: null,
                objectUrl: null,
                size: 0,
                requiredSize: 0,
                expiresAt: 0,
                lastAccessedAt: waitingEntry.lastAccessedAt,
            };
            this.entries.set(entry.key, entry);
            this.enqueue(entry);
            return;
        }
    }

    private availableByteCapacity(): number {
        let retainedBytes = 0;
        let reservedBytes = 0;
        for (const entry of this.entries.values()) {
            if (entry.status === "ready" && entry.subscribers.size > 0) {
                retainedBytes += entry.size;
            }
            if (
                entry.requiredSize > 0
                && (entry.status === "queued" || entry.status === "loading")
            ) {
                reservedBytes += entry.requiredSize;
            }
        }
        return Math.max(this.maxBytes - retainedBytes - reservedBytes, 0);
    }

    private readyBytes(): number {
        return [...this.entries.values()].reduce(
            (total, entry) => total + (entry.status === "ready" ? entry.size : 0),
            0,
        );
    }

    private ensureEntryCapacity(): boolean {
        this.trim();
        if (this.entries.size < this.maxEntries) return true;
        const candidate = [...this.entries.values()]
            .filter((entry) => entry.subscribers.size === 0)
            .sort((left, right) => left.lastAccessedAt - right.lastAccessedAt)[0];
        if (candidate) this.evict(candidate);
        return this.entries.size < this.maxEntries;
    }

    private ensureByteCapacity(incomingSize: number, incomingEntry: MediaEntry): boolean {
        if (incomingSize > this.maxBytes) return false;
        let cachedBytes = [...this.entries.values()].reduce(
            (total, entry) => total + (entry.status === "ready" ? entry.size : 0),
            0,
        );
        const candidates = [...this.entries.values()]
            .filter((entry) => (
                entry !== incomingEntry
                && entry.status === "ready"
                && entry.subscribers.size === 0
            ))
            .sort((left, right) => left.lastAccessedAt - right.lastAccessedAt);
        for (const candidate of candidates) {
            if (cachedBytes + incomingSize <= this.maxBytes) break;
            cachedBytes -= candidate.size;
            this.evict(candidate);
        }
        return cachedBytes + incomingSize <= this.maxBytes;
    }

    private evict(entry: MediaEntry): void {
        if (entry.subscribers.size > 0) return;
        this.abortEntry(entry);
        this.releaseObjectUrl(entry);
        this.entries.delete(entry.key);
    }

    private trim(): void {
        const currentTime = this.now();
        for (const entry of this.entries.values()) {
            if (entry.expiresAt <= 0 || entry.expiresAt > currentTime) continue;
            if (entry.status === "failed" && entry.subscribers.size > 0) {
                entry.status = "idle";
                entry.expiresAt = 0;
                this.enqueue(entry);
            } else if (entry.subscribers.size === 0) {
                this.evict(entry);
            }
        }
        const retained = [...this.entries.values()]
            .filter((entry) => entry.subscribers.size === 0)
            .sort((left, right) => left.lastAccessedAt - right.lastAccessedAt);
        let cachedBytes = [...this.entries.values()].reduce(
            (total, entry) => total + (entry.status === "ready" ? entry.size : 0),
            0,
        );
        for (const entry of retained) {
            if (this.entries.size <= this.maxEntries && cachedBytes <= this.maxBytes) break;
            if (entry.status === "ready") cachedBytes -= entry.size;
            this.evict(entry);
        }
        this.scheduleCleanup();
    }

    private scheduleCleanup(): void {
        if (this.cleanupTimer) clearTimeout(this.cleanupTimer);
        this.cleanupTimer = null;
        if (this.disposed) return;
        const nextExpiry = [...this.entries.values()]
            .filter((entry) => (
                entry.expiresAt > 0
                && (entry.subscribers.size === 0 || entry.status === "failed")
            ))
            .reduce<number | null>(
                (earliest, entry) => earliest === null ? entry.expiresAt : Math.min(earliest, entry.expiresAt),
                null,
            );
        if (nextExpiry === null) return;
        this.cleanupTimer = setTimeout(() => {
            this.cleanupTimer = null;
            const entryCount = this.entries.size;
            const cachedBytes = this.readyBytes();
            this.trim();
            if (this.entries.size < entryCount || this.readyBytes() < cachedBytes) {
                this.wakeOneCapacityWaiter();
            }
        }, Math.max(nextExpiry - this.now(), 0));
    }
}
