import { afterEach, describe, expect, it, vi } from "vitest";

import {
    classifyProtectedMediaSource,
    ProtectedMediaCache,
    type ProtectedMediaFetcher,
} from "@/app/lib/protectedMedia";
import { fetchProtectedMediaResponse } from "@/app/lib/api";

const ORIGIN = "https://connex.test";

function mediaPath(id: number): string {
    return `/api/persons/${id}/profile-picture/00000000-0000-4000-8000-${String(id).padStart(12, "0")}.png`;
}

function imageResponse(body = "image", status = 200, headers?: HeadersInit): Response {
    return new Response(body, {
        status,
        headers: {
            "Content-Type": "image/png",
            ...Object.fromEntries(new Headers(headers)),
        },
    });
}

async function waitFor(predicate: () => boolean): Promise<void> {
    for (let attempt = 0; attempt < 40; attempt += 1) {
        if (predicate()) return;
        await new Promise((resolve) => setTimeout(resolve, 0));
    }
    throw new Error("Condition was not reached");
}

function cacheFor(
    fetcher: ProtectedMediaFetcher,
    overrides: Partial<ConstructorParameters<typeof ProtectedMediaCache>[0]> = {},
) {
    const revoked: string[] = [];
    let objectUrlSequence = 0;
    const cache = new ProtectedMediaCache({
        identity: "7:11",
        origin: ORIGIN,
        workspaceId: 11,
        fetcher,
        createObjectUrl: () => `blob:protected-${++objectUrlSequence}`,
        revokeObjectUrl: (url) => revoked.push(url),
        ...overrides,
    });
    return { cache, revoked };
}

describe("protected media source classification", () => {
    it.each([
        mediaPath(1),
        "/api/users/7/profile-picture/00000000-0000-4000-8000-000000000007.webp",
        "/api/companies/9/logo/00000000-0000-4000-8000-000000000009.jpg",
        `${ORIGIN}${mediaPath(2)}`,
    ])("recognizes canonical managed source %s", (source) => {
        expect(classifyProtectedMediaSource(source, ORIGIN)).toEqual({
            kind: "protected",
            path: new URL(source, ORIGIN).pathname,
        });
    });

    it.each([
        "https://images.example/avatar.png",
        "data:image/png;base64,AA==",
        "blob:https://connex.test/7f1ab3b0",
        "/assets/avatar.png",
    ])("leaves safe public source %s direct", (source) => {
        expect(classifyProtectedMediaSource(source, ORIGIN)).toEqual({ kind: "direct", source });
    });

    it.each([
        `${ORIGIN}${mediaPath(3)}?token=secret`,
        `/api/users/7/profile-picture/not-canonical.png`,
        "/api/private/export",
        `/API/persons/7/profile-picture/00000000-0000-4000-8000-000000000007.png`,
        `/%61pi/persons/7/profile-picture/00000000-0000-4000-8000-000000000007.png`,
        "javascript:alert(1)",
        "//connex.test/api/users/7/profile-picture/00000000-0000-4000-8000-000000000007.png",
        "https://user:password@images.example/avatar.png",
    ])("rejects unsafe source %s", (source) => {
        expect(classifyProtectedMediaSource(source, ORIGIN)).toEqual({ kind: "invalid" });
    });

    it("never upgrades a cross-origin managed-looking URL", () => {
        const source = `https://cdn.example${mediaPath(4)}`;
        expect(classifyProtectedMediaSource(source, ORIGIN)).toEqual({ kind: "direct", source });
    });
});

describe("protected media request scope", () => {
    afterEach(() => {
        vi.restoreAllMocks();
        vi.unstubAllGlobals();
    });

    it("sends credentials and the captured workspace header", async () => {
        const fetchMock = vi.fn<typeof fetch>(async () => imageResponse());
        vi.stubGlobal("fetch", fetchMock);
        const controller = new AbortController();

        await fetchProtectedMediaResponse(mediaPath(10), 11, controller.signal);

        expect(fetchMock).toHaveBeenCalledTimes(1);
        const [url, init] = fetchMock.mock.calls[0];
        expect(String(url).endsWith(mediaPath(10))).toBe(true);
        expect(init).toMatchObject({
            credentials: "same-origin",
            cache: "no-store",
            mode: "same-origin",
            redirect: "error",
            signal: controller.signal,
        });
        expect(new Headers(init?.headers).get("X-Workspace-Id")).toBe("11");
    });

    it.each([
        ["/api/private/export", 11],
        [mediaPath(10), 0],
        [mediaPath(10), 2_147_483_648],
    ] as const)("rejects invalid request scope %s", async (path, workspaceId) => {
        const fetchMock = vi.fn<typeof fetch>(async () => imageResponse());
        vi.stubGlobal("fetch", fetchMock);

        await expect(fetchProtectedMediaResponse(
            path,
            workspaceId,
            new AbortController().signal,
        )).rejects.toThrow(RangeError);
        expect(fetchMock).not.toHaveBeenCalled();
    });
});

describe("protected media cache", () => {
    const caches: ProtectedMediaCache[] = [];

    afterEach(() => {
        for (const cache of caches.splice(0)) cache.dispose();
        vi.restoreAllMocks();
    });

    it("deduplicates subscribers and keeps a warm object URL", async () => {
        const fetcher = vi.fn<ProtectedMediaFetcher>(async () => imageResponse());
        const setup = cacheFor(fetcher);
        caches.push(setup.cache);
        const listenerOne = vi.fn();
        const listenerTwo = vi.fn();
        const releaseOne = setup.cache.subscribe(mediaPath(1), listenerOne);
        const releaseTwo = setup.cache.subscribe(mediaPath(1), listenerTwo);

        await waitFor(() => setup.cache.getSnapshot(mediaPath(1)) !== null);
        expect(fetcher).toHaveBeenCalledTimes(1);
        expect(setup.cache.getSnapshot(mediaPath(1))).toBe("blob:protected-1");
        expect(listenerOne).toHaveBeenCalledTimes(1);
        expect(listenerTwo).toHaveBeenCalledTimes(1);

        releaseOne();
        releaseTwo();
        const releaseWarm = setup.cache.subscribe(mediaPath(1), vi.fn());
        expect(setup.cache.getSnapshot(mediaPath(1))).toBe("blob:protected-1");
        expect(fetcher).toHaveBeenCalledTimes(1);
        releaseWarm();
    });

    it("limits the queue to three concurrent requests", async () => {
        const pending: Array<(response: Response) => void> = [];
        const fetcher = vi.fn<ProtectedMediaFetcher>(() => new Promise((resolve) => pending.push(resolve)));
        const setup = cacheFor(fetcher);
        caches.push(setup.cache);
        const releases = [1, 2, 3, 4].map((id) => setup.cache.subscribe(mediaPath(id), vi.fn()));

        expect(fetcher).toHaveBeenCalledTimes(3);
        pending.shift()?.(imageResponse());
        await waitFor(() => fetcher.mock.calls.length === 4);
        expect(fetcher).toHaveBeenCalledTimes(4);

        for (const resolve of pending) resolve(imageResponse());
        await waitFor(() => setup.cache.getSnapshot(mediaPath(4)) !== null);
        for (const release of releases) release();
    });

    it("passes the captured workspace id to every fetch", async () => {
        const observed: number[] = [];
        const fetcher: ProtectedMediaFetcher = async (_path, workspaceId) => {
            observed.push(workspaceId);
            return imageResponse();
        };
        const setup = cacheFor(fetcher);
        caches.push(setup.cache);
        const release = setup.cache.subscribe(mediaPath(8), vi.fn());

        await waitFor(() => setup.cache.getSnapshot(mediaPath(8)) !== null);
        expect(observed).toEqual([11]);
        release();
    });

    it("hides absolute API aliases from the server snapshot", () => {
        const setup = cacheFor(async () => imageResponse());
        caches.push(setup.cache);

        expect(setup.cache.getServerSnapshot(
            `https://images.example/API/persons/8/profile-picture/00000000-0000-4000-8000-000000000008.png`,
        )).toBeNull();
    });

    it("retries only throttling and transient-unavailable responses", async () => {
        const statuses = [429, 503, 200];
        const sleeps: number[] = [];
        const fetcher = vi.fn<ProtectedMediaFetcher>(async () => imageResponse("image", statuses.shift() ?? 500));
        const setup = cacheFor(fetcher, {
            random: () => 0,
            sleep: async (delayMs) => {
                sleeps.push(delayMs);
            },
        });
        caches.push(setup.cache);
        const release = setup.cache.subscribe(mediaPath(9), vi.fn());

        await waitFor(() => setup.cache.getSnapshot(mediaPath(9)) !== null);
        expect(fetcher).toHaveBeenCalledTimes(3);
        expect(sleeps).toEqual([200, 400]);
        release();
    });

    it.each([401, 403, 404, 500])("does not retry terminal status %s", async (status) => {
        const fetcher = vi.fn<ProtectedMediaFetcher>(async () => imageResponse("", status));
        const setup = cacheFor(fetcher, { failureTtlMs: 1, sleep: async () => undefined });
        caches.push(setup.cache);
        const listener = vi.fn();
        const release = setup.cache.subscribe(mediaPath(status), listener);

        await waitFor(() => listener.mock.calls.length > 0);
        await new Promise((resolve) => setTimeout(resolve, 5));
        expect(fetcher).toHaveBeenCalledTimes(1);
        expect(setup.cache.getSnapshot(mediaPath(status))).toBeNull();
        release();
    });

    it.each([
        ["text/plain", "image", 25],
        ["image/png", "oversized", 4],
    ])("rejects unsafe response %s", async (contentType, body, maxResponseBytes) => {
        const fetcher = vi.fn<ProtectedMediaFetcher>(async () => new Response(body, {
            headers: { "Content-Type": contentType },
        }));
        const setup = cacheFor(fetcher, { maxResponseBytes });
        caches.push(setup.cache);
        const listener = vi.fn();
        const release = setup.cache.subscribe(mediaPath(12), listener);

        await waitFor(() => listener.mock.calls.length > 0);
        expect(setup.cache.getSnapshot(mediaPath(12))).toBeNull();
        expect(fetcher).toHaveBeenCalledTimes(1);
        release();
    });

    it("treats a response larger than the total cache budget as terminal", async () => {
        const fetcher = vi.fn<ProtectedMediaFetcher>(async () => imageResponse("oversized"));
        const setup = cacheFor(fetcher, { failureTtlMs: 1, maxBytes: 4 });
        caches.push(setup.cache);
        const listener = vi.fn();
        const release = setup.cache.subscribe(mediaPath(28), listener);

        await waitFor(() => listener.mock.calls.length > 0);
        await new Promise((resolve) => setTimeout(resolve, 5));
        expect(fetcher).toHaveBeenCalledTimes(1);
        expect(setup.cache.getSnapshot(mediaPath(28))).toBeNull();
        release();
    });

    it.each([
        [new Headers({ "Content-Type": "text/plain" }), 64],
        [new Headers({ "Content-Type": "image/png", "Content-Length": "65" }), 64],
    ])("cancels streamed bodies rejected from response headers", async (headers, maxResponseBytes) => {
        const cancel = vi.fn();
        const response = new Response(new ReadableStream<Uint8Array>({ cancel }), { headers });
        const setup = cacheFor(async () => response, { maxResponseBytes });
        caches.push(setup.cache);
        const listener = vi.fn();
        const release = setup.cache.subscribe(mediaPath(15), listener);

        await waitFor(() => listener.mock.calls.length > 0);
        expect(cancel).toHaveBeenCalledTimes(1);
        expect(setup.cache.getSnapshot(mediaPath(15))).toBeNull();
        release();
    });

    it("retries a still-mounted failed source after the failure TTL", async () => {
        const statuses = [503, 503, 503, 200];
        const fetcher = vi.fn<ProtectedMediaFetcher>(async () => imageResponse(
            "image",
            statuses.shift() ?? 500,
        ));
        const setup = cacheFor(fetcher, {
            failureTtlMs: 1,
            sleep: async () => undefined,
        });
        caches.push(setup.cache);
        const release = setup.cache.subscribe(mediaPath(16), vi.fn());

        await waitFor(() => setup.cache.getSnapshot(mediaPath(16)) !== null);
        expect(fetcher).toHaveBeenCalledTimes(4);
        release();
    });

    it("admits a waiting mounted source when entry capacity frees", async () => {
        const fetcher = vi.fn<ProtectedMediaFetcher>(async () => imageResponse());
        const setup = cacheFor(fetcher, { maxEntries: 1 });
        caches.push(setup.cache);
        const releaseOne = setup.cache.subscribe(mediaPath(17), vi.fn());
        await waitFor(() => setup.cache.getSnapshot(mediaPath(17)) !== null);
        const releaseTwo = setup.cache.subscribe(mediaPath(18), vi.fn());

        expect(fetcher).toHaveBeenCalledTimes(1);
        releaseOne();
        await waitFor(() => setup.cache.getSnapshot(mediaPath(18)) !== null);
        expect(fetcher).toHaveBeenCalledTimes(2);
        expect(setup.revoked).toContain("blob:protected-1");
        releaseTwo();
    });

    it("waits for mounted byte capacity without polling the response", async () => {
        const fetcher = vi.fn<ProtectedMediaFetcher>(async () => imageResponse("image"));
        const setup = cacheFor(fetcher, { failureTtlMs: 1, maxBytes: 5 });
        caches.push(setup.cache);
        const releaseOne = setup.cache.subscribe(mediaPath(19), vi.fn());
        await waitFor(() => setup.cache.getSnapshot(mediaPath(19)) !== null);
        const listenerTwo = vi.fn();
        const releaseTwo = setup.cache.subscribe(mediaPath(20), listenerTwo);

        await waitFor(() => listenerTwo.mock.calls.length > 0);
        expect(setup.cache.getSnapshot(mediaPath(19))).toBe("blob:protected-1");
        expect(setup.cache.getSnapshot(mediaPath(20))).toBeNull();
        expect(setup.revoked).toEqual([]);
        await new Promise((resolve) => setTimeout(resolve, 5));
        expect(fetcher).toHaveBeenCalledTimes(2);
        releaseOne();
        await waitFor(() => setup.cache.getSnapshot(mediaPath(20)) === "blob:protected-2");
        expect(fetcher).toHaveBeenCalledTimes(3);
        expect(setup.revoked).toContain("blob:protected-1");
        releaseTwo();
    });

    it("wakes only one deferred source for each byte-capacity release", async () => {
        const fetcher = vi.fn<ProtectedMediaFetcher>(async () => imageResponse("image"));
        const setup = cacheFor(fetcher, { maxBytes: 5 });
        caches.push(setup.cache);
        const releaseOne = setup.cache.subscribe(mediaPath(22), vi.fn());
        await waitFor(() => setup.cache.getSnapshot(mediaPath(22)) !== null);
        const listenerTwo = vi.fn();
        const listenerThree = vi.fn();
        const releaseTwo = setup.cache.subscribe(mediaPath(23), listenerTwo);
        const releaseThree = setup.cache.subscribe(mediaPath(24), listenerThree);

        await waitFor(() => listenerTwo.mock.calls.length > 0 && listenerThree.mock.calls.length > 0);
        expect(fetcher).toHaveBeenCalledTimes(3);
        releaseOne();
        await waitFor(() => setup.cache.getSnapshot(mediaPath(23)) === "blob:protected-2");
        await new Promise((resolve) => setTimeout(resolve, 5));
        expect(fetcher).toHaveBeenCalledTimes(4);
        expect(setup.cache.getSnapshot(mediaPath(24))).toBeNull();
        releaseTwo();
        releaseThree();
    });

    it("does not wake a byte waiter when an unrelated transient retry expires", async () => {
        const transientPath = mediaPath(27);
        const transientStatuses = [503, 503, 503, 200];
        const pathCalls = new Map<string, number>();
        const fetcher = vi.fn<ProtectedMediaFetcher>(async (path) => {
            pathCalls.set(path, (pathCalls.get(path) ?? 0) + 1);
            if (path === transientPath) {
                return imageResponse("image", transientStatuses.shift() ?? 500);
            }
            return imageResponse("image");
        });
        const setup = cacheFor(fetcher, {
            failureTtlMs: 1,
            maxBytes: 5,
            sleep: async () => undefined,
        });
        caches.push(setup.cache);
        const releaseReady = setup.cache.subscribe(mediaPath(25), vi.fn());
        await waitFor(() => setup.cache.getSnapshot(mediaPath(25)) !== null);
        const byteWaiter = vi.fn();
        const releaseWaiter = setup.cache.subscribe(mediaPath(26), byteWaiter);
        await waitFor(() => byteWaiter.mock.calls.length > 0);
        const transientListener = vi.fn();
        const releaseTransient = setup.cache.subscribe(transientPath, transientListener);

        await waitFor(() => (pathCalls.get(transientPath) ?? 0) === 4);
        expect(pathCalls.get(mediaPath(26))).toBe(1);
        expect(setup.cache.getSnapshot(transientPath)).toBeNull();
        releaseReady();
        releaseWaiter();
        releaseTransient();
    });

    it("evicts the least-recently-used unretained URL and revokes it", async () => {
        const fetcher: ProtectedMediaFetcher = async () => imageResponse();
        const setup = cacheFor(fetcher, { maxEntries: 1 });
        caches.push(setup.cache);
        const releaseOne = setup.cache.subscribe(mediaPath(1), vi.fn());
        await waitFor(() => setup.cache.getSnapshot(mediaPath(1)) !== null);
        releaseOne();

        const releaseTwo = setup.cache.subscribe(mediaPath(2), vi.fn());
        await waitFor(() => setup.cache.getSnapshot(mediaPath(2)) !== null);
        expect(setup.revoked).toContain("blob:protected-1");
        expect(setup.cache.getSnapshot(mediaPath(1))).toBeNull();
        releaseTwo();
    });

    it("expires an unretained URL before loading it again", async () => {
        let now = 100;
        const fetcher = vi.fn<ProtectedMediaFetcher>(async () => imageResponse());
        const setup = cacheFor(fetcher, { now: () => now, ttlMs: 50 });
        caches.push(setup.cache);
        const releaseOne = setup.cache.subscribe(mediaPath(5), vi.fn());
        await waitFor(() => setup.cache.getSnapshot(mediaPath(5)) !== null);
        releaseOne();
        now = 151;

        const releaseTwo = setup.cache.subscribe(mediaPath(5), vi.fn());
        await waitFor(() => fetcher.mock.calls.length === 2 && setup.cache.getSnapshot(mediaPath(5)) !== null);
        expect(setup.revoked).toContain("blob:protected-1");
        expect(setup.cache.getSnapshot(mediaPath(5))).toBe("blob:protected-2");
        releaseTwo();
    });

    it("revokes ready URLs and reloads visible sources on identity invalidation", async () => {
        const fetcher = vi.fn<ProtectedMediaFetcher>(async () => imageResponse());
        const setup = cacheFor(fetcher);
        caches.push(setup.cache);
        const listener = vi.fn();
        const release = setup.cache.subscribe(mediaPath(6), listener);
        await waitFor(() => setup.cache.getSnapshot(mediaPath(6)) !== null);

        setup.cache.invalidate();
        expect(setup.revoked).toContain("blob:protected-1");
        expect(setup.cache.getSnapshot(mediaPath(6))).toBeNull();
        await waitFor(() => setup.cache.getSnapshot(mediaPath(6)) === "blob:protected-2");
        expect(fetcher).toHaveBeenCalledTimes(2);
        release();
    });

    it("aborts an in-flight identity snapshot before reloading it", async () => {
        const signals: AbortSignal[] = [];
        let call = 0;
        const fetcher: ProtectedMediaFetcher = (_path, _workspaceId, signal) => {
            signals.push(signal);
            call += 1;
            if (call > 1) return Promise.resolve(imageResponse());
            return new Promise((_resolve, reject) => {
                signal.addEventListener("abort", () => reject(new DOMException("Aborted", "AbortError")), { once: true });
            });
        };
        const setup = cacheFor(fetcher);
        caches.push(setup.cache);
        const release = setup.cache.subscribe(mediaPath(13), vi.fn());

        setup.cache.invalidate();
        expect(signals[0]?.aborted).toBe(true);
        await waitFor(() => setup.cache.getSnapshot(mediaPath(13)) !== null);
        expect(signals).toHaveLength(2);
        release();
    });

    it("revokes bytes the browser reports as undecodable", async () => {
        const fetcher = vi.fn<ProtectedMediaFetcher>(async () => imageResponse());
        const setup = cacheFor(fetcher, { failureTtlMs: 1 });
        caches.push(setup.cache);
        const release = setup.cache.subscribe(mediaPath(14), vi.fn());
        await waitFor(() => setup.cache.getSnapshot(mediaPath(14)) !== null);

        setup.cache.reject(mediaPath(14), "blob:protected-1");
        await new Promise((resolve) => setTimeout(resolve, 5));
        expect(setup.cache.getSnapshot(mediaPath(14))).toBeNull();
        expect(setup.revoked).toContain("blob:protected-1");
        expect(fetcher).toHaveBeenCalledTimes(1);
        release();
    });

    it("ignores a late decode error from a revoked object URL", async () => {
        const setup = cacheFor(async () => imageResponse());
        caches.push(setup.cache);
        const release = setup.cache.subscribe(mediaPath(21), vi.fn());
        await waitFor(() => setup.cache.getSnapshot(mediaPath(21)) === "blob:protected-1");

        setup.cache.invalidate();
        await waitFor(() => setup.cache.getSnapshot(mediaPath(21)) === "blob:protected-2");
        setup.cache.reject(mediaPath(21), "blob:protected-1");

        expect(setup.cache.getSnapshot(mediaPath(21))).toBe("blob:protected-2");
        expect(setup.revoked).not.toContain("blob:protected-2");
        release();
    });

    it("aborts active work and revokes all URLs on disposal", async () => {
        const observedSignals: AbortSignal[] = [];
        const fetcher: ProtectedMediaFetcher = (_path, _workspaceId, signal) => {
            observedSignals.push(signal);
            return new Promise((_resolve, reject) => {
                signal.addEventListener("abort", () => reject(new DOMException("Aborted", "AbortError")), { once: true });
            });
        };
        const setup = cacheFor(fetcher);
        const release = setup.cache.subscribe(mediaPath(7), vi.fn());
        setup.cache.dispose();

        expect(observedSignals).toHaveLength(1);
        expect(observedSignals[0]?.aborted).toBe(true);
        expect(setup.cache.getSnapshot(mediaPath(7))).toBeNull();
        release();
    });
});
