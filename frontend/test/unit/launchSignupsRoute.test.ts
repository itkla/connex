import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const SEGMENT_ID = "78261eea-8f8b-4381-83c6-79fa7120f1cf";
const CONTACT_ID = "479e3145-dd38-476b-932c-529ceb705947";
const ORIGIN = "https://connex.example";
const EMAIL = "launch@example.com";

type Post = typeof import("@/app/api/launch-signups/route").POST;
let post: Post;
let fetcher: ReturnType<typeof vi.fn<typeof fetch>>;

function request(body: unknown = { email: EMAIL }, headers: HeadersInit = {}): Request {
    const mergedHeaders = new Headers({
        "Content-Type": "application/json",
        Origin: ORIGIN,
        "Sec-Fetch-Site": "same-origin",
        "X-Connex-Client-IP": "192.0.2.1",
    });
    new Headers(headers).forEach((value, name) => mergedHeaders.set(name, value));
    return new Request(`${ORIGIN}/api/launch-signups`, {
        method: "POST",
        headers: mergedHeaders,
        body: JSON.stringify(body),
    });
}

function providerSuccess(email = EMAIL, existing = false): void {
    fetcher.mockImplementation(async (input) => {
        const url = String(input);
        if (url.endsWith("/segments")) {
            return Response.json({ object: "list", data: [{ id: SEGMENT_ID }], has_more: false });
        }
        if (url.endsWith(`/${encodeURIComponent(email)}`)) {
            return existing
                ? Response.json({ object: "contact", id: CONTACT_ID, email, unsubscribed: false })
                : new Response(null, { status: 404 });
        }
        if (url.endsWith(`/${CONTACT_ID}`)) {
            return Response.json({ object: "contact", id: CONTACT_ID, email, unsubscribed: false });
        }
        return Response.json({ object: "contact", id: CONTACT_ID });
    });
}

async function submit(input = request()): Promise<Response> {
    const result = post(input);
    await vi.runAllTimersAsync();
    return result;
}

beforeEach(async () => {
    vi.resetModules();
    vi.useFakeTimers();
    vi.setSystemTime(new Date("2026-09-12T03:00:00Z"));
    vi.stubEnv("RESEND_API_KEY", "re_test_only_never_sent");
    vi.stubEnv("RESEND_LAUNCH_SEGMENT_ID", SEGMENT_ID);
    vi.stubEnv("CONNEX_LANDING_MODE", "prelaunch");
    vi.stubEnv("NODE_ENV", "production");
    fetcher = vi.fn<typeof fetch>();
    vi.stubGlobal("fetch", fetcher);
    post = (await import("@/app/api/launch-signups/route")).POST;
});

afterEach(() => {
    vi.useRealTimers();
    vi.unstubAllGlobals();
    vi.unstubAllEnvs();
});

describe("anonymous launch signup persistence", () => {
    it("confirms the contact and launch membership before returning success, without forwarding credentials", async () => {
        providerSuccess();
        const response = await submit(request({ email: ` ${EMAIL} `, website: "" }, {
            Cookie: "JSESSIONID=browser-only",
            Authorization: "Bearer browser-only",
        }));

        expect(response.status).toBe(200);
        expect(await response.json()).toEqual({ status: "subscribed" });
        expect(response.headers.get("Cache-Control")).toBe("no-store");
        expect(fetcher).toHaveBeenCalledTimes(4);
        const [url, init] = fetcher.mock.calls[1];
        expect(url).toBe("https://api.resend.com/contacts");
        expect(JSON.parse(String(init?.body))).toEqual({ email: EMAIL, segments: [{ id: SEGMENT_ID }] });
        for (const [providerUrl, options] of fetcher.mock.calls) {
            expect(new URL(String(providerUrl)).origin).toBe("https://api.resend.com");
            expect(String(providerUrl)).not.toContain(EMAIL);
            expect(options).toMatchObject({ cache: "no-store", redirect: "error", credentials: "omit" });
            const headers = new Headers(options?.headers);
            expect(headers.get("Authorization")).toBe("Bearer re_test_only_never_sent");
            expect(headers.get("Cookie")).toBeNull();
            expect(headers.get("X-Connex-Client-IP")).toBeNull();
            expect(String(options?.body ?? "")).not.toContain("unsubscribed");
        }
        expect(fetcher.mock.calls.map(([providerUrl]) => providerUrl)).toEqual([
            `https://api.resend.com/contacts/${encodeURIComponent(EMAIL)}`,
            "https://api.resend.com/contacts",
            `https://api.resend.com/contacts/${CONTACT_ID}`,
            `https://api.resend.com/contacts/${CONTACT_ID}/segments`,
        ]);
    });

    it("defaults to prelaunch and accepts the trusted external origin behind Caddy", async () => {
        delete process.env.CONNEX_LANDING_MODE;
        providerSuccess();
        const input = new Request("http://localhost:3000/api/launch-signups", {
            method: "POST",
            headers: {
                "Content-Type": "application/json; charset=utf-8",
                Origin: ORIGIN,
                Host: "connex.example",
                "X-Forwarded-Proto": "https",
                "X-Connex-Client-IP": "2001:db8::1",
            },
            body: JSON.stringify({ email: EMAIL }),
        });
        expect((await submit(input)).status).toBe(200);
    });

    it("percent-encodes the complete email only in the documented fixed-host lookup", async () => {
        const email = "launch+alpha/?#%&@example.com";
        providerSuccess(email);
        const response = await submit(request({ email }));
        expect(await response.json()).toEqual({ status: "subscribed" });
        const lookup = new URL(String(fetcher.mock.calls[0][0]));
        expect(lookup.origin).toBe("https://api.resend.com");
        expect(lookup.pathname).toBe(`/contacts/${encodeURIComponent(email)}`);
        expect(lookup.search).toBe("");
        expect(lookup.hash).toBe("");
    });

    it("accepts a repeat signup only when the returned contact is subscribed and in the launch segment", async () => {
        providerSuccess();
        expect((await submit()).status).toBe(200);
        providerSuccess(EMAIL, true);
        expect((await submit()).status).toBe(200);
        expect(fetcher).toHaveBeenCalledTimes(6);
        expect(fetcher.mock.calls.slice(4).map(([, init]) => init?.method)).toEqual(["GET", "GET"]);
    });

    it("does not treat a duplicate conflict as proof of signup", async () => {
        fetcher
            .mockResolvedValueOnce(new Response(null, { status: 404 }))
            .mockResolvedValueOnce(Response.json({ name: "conflict", message: "Contact already exists", id: CONTACT_ID }, { status: 409 }));
        const response = await submit();
        expect(response.status).toBe(503);
        expect(await response.json()).toEqual({ error: "unavailable" });
        expect(fetcher).toHaveBeenCalledTimes(2);
    });

    it("adds an existing opted-in contact to the launch segment and verifies persistence", async () => {
        fetcher
            .mockResolvedValueOnce(Response.json({ object: "contact", id: CONTACT_ID, email: EMAIL, unsubscribed: false }))
            .mockResolvedValueOnce(Response.json({ object: "list", data: [], has_more: false }))
            .mockResolvedValueOnce(Response.json({ id: SEGMENT_ID }))
            .mockResolvedValueOnce(Response.json({ object: "list", data: [{ id: SEGMENT_ID }], has_more: false }));
        const response = await submit();
        expect(await response.json()).toEqual({ status: "subscribed" });
        expect(fetcher).toHaveBeenCalledTimes(4);
        expect(fetcher.mock.calls[2][0]).toBe(`https://api.resend.com/contacts/${CONTACT_ID}/segments/${SEGMENT_ID}`);
        expect(fetcher.mock.calls[2][1]).toMatchObject({ method: "POST" });
        expect(fetcher.mock.calls[2][1]?.body).toBeUndefined();
    });

    it("does not claim success when segment membership is still absent after an accepted add", async () => {
        fetcher
            .mockResolvedValueOnce(Response.json({ object: "contact", id: CONTACT_ID, email: EMAIL, unsubscribed: false }))
            .mockResolvedValueOnce(Response.json({ object: "list", data: [], has_more: false }))
            .mockResolvedValueOnce(Response.json({ id: SEGMENT_ID }))
            .mockResolvedValueOnce(Response.json({ object: "list", data: [], has_more: true }));
        const response = await submit();
        expect(await response.json()).toEqual({ error: "unavailable" });
        expect(fetcher).toHaveBeenCalledTimes(4);
    });

    it("does not claim success after a segment-add failure", async () => {
        fetcher
            .mockResolvedValueOnce(Response.json({ object: "contact", id: CONTACT_ID, email: EMAIL, unsubscribed: false }))
            .mockResolvedValueOnce(Response.json({ object: "list", data: [], has_more: false }))
            .mockResolvedValueOnce(Response.json({ error: "Provider unavailable" }, { status: 503 }));
        const response = await submit();
        expect(await response.json()).toEqual({ error: "unavailable" });
        expect(fetcher).toHaveBeenCalledTimes(3);
    });

    it("does not add membership after an invalid segment lookup response", async () => {
        fetcher
            .mockResolvedValueOnce(Response.json({ object: "contact", id: CONTACT_ID, email: EMAIL, unsubscribed: false }))
            .mockResolvedValueOnce(Response.json({ object: "unknown", data: [] }));
        expect((await submit()).status).toBe(503);
        expect(fetcher.mock.calls.map(([, init]) => init?.method)).toEqual(["GET", "GET"]);
    });

    it("preserves an existing global opt-out without exposing its state or sending an update", async () => {
        fetcher
            .mockResolvedValueOnce(Response.json({ object: "contact", id: CONTACT_ID, email: EMAIL, unsubscribed: true }));
        const response = await submit();
        expect(await response.json()).toEqual({ error: "unavailable" });
        expect(fetcher.mock.calls.map(([, init]) => init?.method)).toEqual(["GET"]);
        expect(fetcher.mock.calls[0][1]?.body).toBeUndefined();
    });

    it.each([
        {},
        { object: "contact", id: "../../another-resource" },
        { object: "contact", id: EMAIL },
    ])("rejects an invalid contact creation response: %j", async (body) => {
        fetcher
            .mockResolvedValueOnce(new Response(null, { status: 404 }))
            .mockResolvedValueOnce(Response.json(body));
        expect((await submit()).status).toBe(503);
        expect(fetcher).toHaveBeenCalledTimes(2);
    });

    it.each([
        { id: CONTACT_ID, email: "different@example.com", unsubscribed: false },
        { id: CONTACT_ID, email: EMAIL },
        { id: SEGMENT_ID, email: EMAIL, unsubscribed: false },
    ])("rejects a contact that does not confirm the submitted address and preferences: %j", async (body) => {
        fetcher
            .mockResolvedValueOnce(new Response(null, { status: 404 }))
            .mockResolvedValueOnce(Response.json({ object: "contact", id: CONTACT_ID }))
            .mockResolvedValueOnce(Response.json(body));
        expect((await submit()).status).toBe(503);
        expect(fetcher).toHaveBeenCalledTimes(3);
    });
});

describe("request validation and availability", () => {
    it.each(["", "bad", "a@b", "two@@example.com", "a..b@example.com", "a\nb@example.com", "a@-example.com", `${"a".repeat(65)}@example.com`, 42, null])("rejects invalid email %j without provider calls", async (email) => {
        const response = await submit(request({ email }));
        expect(response.status).toBe(400);
        expect(await response.json()).toEqual({ error: "invalid_email" });
        expect(fetcher).not.toHaveBeenCalled();
    });

    it.each([null, [], { email: EMAIL, website: "https://spam.example" }, { email: EMAIL, website: 1 }, { email: EMAIL, segments: [SEGMENT_ID] }])("rejects malformed or honeypot input %j", async (body) => {
        const response = await submit(request(body));
        expect(await response.json()).toEqual({ error: "invalid_request" });
        expect(fetcher).not.toHaveBeenCalled();
    });

    it.each<HeadersInit>([
        { Origin: "https://other.example" },
        { Origin: "null" },
        { Origin: "" },
        { "Sec-Fetch-Site": "cross-site" },
        { "Sec-Fetch-Site": "same-site" },
        { "Sec-Fetch-Mode": "navigate" },
        { "X-Forwarded-Proto": "https,http" },
        { Host: "connex.example@other.example" },
    ])("rejects cross-origin or ambiguous request metadata: %j", async (headers) => {
        const response = await submit(request(undefined, headers));
        expect(response.status).toBe(403);
        expect(fetcher).not.toHaveBeenCalled();
    });

    it.each<HeadersInit>([
        { "Content-Type": "text/plain" },
        { "Content-Type": "application/x-www-form-urlencoded" },
        { "Content-Encoding": "gzip" },
    ])("rejects unsupported representations: %j", async (headers) => {
        expect((await submit(request(undefined, headers))).status).toBe(415);
        expect(fetcher).not.toHaveBeenCalled();
    });

    it("rejects non-POST methods before provider calls", async () => {
        expect((await post(new Request(`${ORIGIN}/api/launch-signups`))).status).toBe(405);
        expect(fetcher).not.toHaveBeenCalled();
    });

    it.each(["4097", "invalid", "-1"])("rejects unsafe content lengths: %s", async (length) => {
        expect((await submit(request(undefined, { "Content-Length": length }))).status).toBe(400);
        expect(fetcher).not.toHaveBeenCalled();
    });

    it("bounds the actual body when content length is absent or understated", async () => {
        expect((await submit(request({ email: EMAIL, website: " ".repeat(5000) }, { "Content-Length": "20" }))).status).toBe(400);
        expect(fetcher).not.toHaveBeenCalled();
    });

    it("rejects malformed JSON without contacting the provider", async () => {
        const input = new Request(request(), { body: "{\"email\":" });
        expect((await submit(input)).status).toBe(400);
        expect(fetcher).not.toHaveBeenCalled();
    });

    it("cancels a stalled request body within its deadline", async () => {
        const cancel = vi.fn();
        const stream = new ReadableStream<Uint8Array>({ cancel });
        const init: RequestInit & { duplex: "half" } = { body: stream, duplex: "half" };
        const input = new Request(request(), init);
        expect((await submit(input)).status).toBe(400);
        expect(cancel).toHaveBeenCalledOnce();
        expect(fetcher).not.toHaveBeenCalled();
    });

    it.each([
        ["RESEND_API_KEY", ""],
        ["RESEND_LAUNCH_SEGMENT_ID", ""],
        ["RESEND_LAUNCH_SEGMENT_ID", "../segment"],
        ["CONNEX_LANDING_MODE", "product"],
        ["CONNEX_LANDING_MODE", "unknown"],
    ])("fails closed when %s is %j", async (name, value) => {
        vi.stubEnv(name, value);
        const response = await submit();
        expect(await response.json()).toEqual({ error: "unavailable" });
        expect(fetcher).not.toHaveBeenCalled();
    });

    it("requires the dedicated proxy identity in production, ignoring generic forwarded identities", async () => {
        const response = await submit(request(undefined, { "X-Connex-Client-IP": "", "X-Forwarded-For": "192.0.2.2" }));
        expect(response.status).toBe(503);
        expect(fetcher).not.toHaveBeenCalled();
    });

    it.each(["not-an-ip", "fe80::1%eth0"])("rejects an unusable proxy identity %s without throwing or contacting the provider", async (identity) => {
        const response = await submit(request(undefined, { "X-Connex-Client-IP": identity }));
        expect(response.status).toBe(503);
        expect(await response.json()).toEqual({ error: "unavailable" });
        expect(fetcher).not.toHaveBeenCalled();
    });
});

describe("bounded provider calls and throttles", () => {
    it.each([401, 409, 429, 500, 503])("keeps provider failure %s generic without leaking its body", async (status) => {
        fetcher.mockResolvedValue(Response.json({ message: `Provider detail for ${EMAIL}` }, { status }));
        const response = await submit();
        expect(response.status).toBe(503);
        expect(await response.json()).toEqual({ error: "unavailable" });
        expect(fetcher.mock.calls.map(([, init]) => init?.method)).toEqual(["GET"]);
    });

    it("cancels a timed-out provider request and frees its active slot", async () => {
        fetcher.mockImplementationOnce(async (_url, options) => new Promise<Response>((_resolve, reject) => {
            options?.signal?.addEventListener("abort", () => reject(new Error("Provider timed out")), { once: true });
        }));
        expect((await submit()).status).toBe(503);
        expect(fetcher.mock.calls[0][1]?.signal?.aborted).toBe(true);
        providerSuccess();
        expect((await submit()).status).toBe(200);
    });

    it("bounds provider response bytes and does not read error pages as success", async () => {
        fetcher.mockResolvedValue(new Response("x".repeat(65537), { headers: { "Content-Type": "application/json" } }));
        expect((await submit()).status).toBe(503);
        expect(fetcher).toHaveBeenCalledOnce();
    });

    it("cancels a stalled provider response body within the same overall deadline", async () => {
        const cancel = vi.fn();
        fetcher.mockResolvedValue(new Response(new ReadableStream<Uint8Array>({ cancel }), {
            headers: { "Content-Type": "application/json" },
        }));
        expect((await submit()).status).toBe(503);
        expect(cancel).toHaveBeenCalledOnce();
    });

    it("limits repeated attempts independently per trusted client, and resets the window", async () => {
        for (let attempt = 0; attempt < 5; attempt += 1) {
            expect((await submit(request({ email: "invalid" }))).status).toBe(400);
        }
        const limited = await submit(request({ email: "invalid" }));
        expect(limited.status).toBe(429);
        expect(await limited.json()).toEqual({ error: "rate_limited" });
        expect(Number(limited.headers.get("Retry-After"))).toBeGreaterThan(0);
        expect((await submit(request({ email: "invalid" }, { "X-Connex-Client-IP": "192.0.2.2" }))).status).toBe(400);
        await vi.advanceTimersByTimeAsync(15 * 60 * 1000);
        expect((await submit(request({ email: "invalid" }))).status).toBe(400);
        expect(fetcher).not.toHaveBeenCalled();
    });

    it("uses one shared development bucket when direct requests have no trusted client header", async () => {
        vi.stubEnv("NODE_ENV", "development");
        for (let attempt = 0; attempt < 5; attempt += 1) {
            expect((await submit(request({ email: "invalid" }, { "X-Connex-Client-IP": "", "X-Forwarded-For": `192.0.2.${attempt}` }))).status).toBe(400);
        }
        expect((await submit(request({ email: "invalid" }, { "X-Connex-Client-IP": "" }))).status).toBe(429);
    });

    it("admits new visitors when the bounded client table fills, retaining recently used limits", async () => {
        for (let attempt = 0; attempt < 5; attempt += 1) {
            expect((await submit(request({ email: "invalid" }))).status).toBe(400);
        }
        for (let client = 1; client < 2048; client += 1) {
            expect((await submit(request({ email: "invalid" }, { "X-Connex-Client-IP": `2001:db8::${client.toString(16)}` }))).status).toBe(400);
        }
        expect((await submit(request({ email: "invalid" }))).status).toBe(429);
        expect((await submit(request({ email: "invalid" }, { "X-Connex-Client-IP": "192.0.2.2" }))).status).toBe(400);
        expect((await submit(request({ email: "invalid" }))).status).toBe(429);
        expect(fetcher).not.toHaveBeenCalled();
    });

    it("rejects concurrent provider work without allocating an unbounded queue", async () => {
        fetcher.mockImplementationOnce(async (_url, options) => new Promise<Response>((_resolve, reject) => {
            options?.signal?.addEventListener("abort", () => reject(new Error("Provider timed out")), { once: true });
        }));
        const first = post(request());
        await vi.advanceTimersByTimeAsync(0);
        const second = await post(request(undefined, { "X-Connex-Client-IP": "192.0.2.2" }));
        expect(second.status).toBe(429);
        expect(second.headers.get("Retry-After")).toBe("8");
        await vi.runAllTimersAsync();
        expect((await first).status).toBe(503);
        expect(fetcher).toHaveBeenCalledOnce();
    });

    it("caps global provider load across distinct clients", async () => {
        fetcher.mockImplementation(async () => Response.json({ error: "Provider unavailable" }, { status: 503 }));
        for (let client = 1; client <= 30; client += 1) {
            expect((await submit(request(undefined, { "X-Connex-Client-IP": `192.0.2.${client}` }))).status).toBe(503);
        }
        const limited = await submit(request(undefined, { "X-Connex-Client-IP": "192.0.2.31" }));
        expect(limited.status).toBe(429);
        expect(fetcher).toHaveBeenCalledTimes(30);
    });
});
