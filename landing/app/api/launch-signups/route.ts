import { isIP } from "node:net";

import { getCloudflareContext } from "@opennextjs/cloudflare";

export const runtime = "nodejs";

type SignupError = "invalid_email" | "invalid_request" | "rate_limited" | "unavailable";
type SignupResponse = { status: "subscribed" } | { error: SignupError };

const REQUEST_BYTES = 4096;
const RESPONSE_BYTES = 65536;
const BODY_TIMEOUT_MS = 3000;
const PROVIDER_TIMEOUT_MS = 8000;
const PROVIDER_INTERVAL_MS = 550;
const UUID = /^[\da-f]{8}-[\da-f]{4}-[\da-f]{4}-[\da-f]{4}-[\da-f]{12}$/i;
const CONTACT_NOT_FOUND = Symbol("contact-not-found");

function json(body: SignupResponse, status: number, retryAfter?: number): Response {
    const headers = new Headers({
        "Cache-Control": "no-store",
        "X-Content-Type-Options": "nosniff",
    });
    if (retryAfter !== undefined) headers.set("Retry-After", String(retryAfter));
    return Response.json(body, { status, headers });
}

function invalidRequest(status = 400): Response {
    return json({ error: "invalid_request" }, status);
}

function unavailable(): Response {
    return json({ error: "unavailable" }, 503);
}

function isRecord(value: unknown): value is Record<string, unknown> {
    return typeof value === "object" && value !== null && !Array.isArray(value);
}

function sameOrigin(request: Request): boolean {
    const site = request.headers.get("sec-fetch-site");
    const mode = request.headers.get("sec-fetch-mode");
    if ((site && site !== "same-origin") || (mode && mode !== "cors" && mode !== "same-origin")) {
        return false;
    }
    try {
        const originHeader = request.headers.get("origin");
        if (!originHeader) return false;
        const origin = new URL(originHeader);
        const requestUrl = new URL(request.url);
        const forwardedProtocol = request.headers.get("x-forwarded-proto");
        if (forwardedProtocol && forwardedProtocol !== "http" && forwardedProtocol !== "https") {
            return false;
        }
        const protocol = forwardedProtocol ? `${forwardedProtocol}:` : requestUrl.protocol;
        const host = request.headers.get("host") ?? requestUrl.host;
        const expected = new URL(`${protocol}//${host}`);
        return (protocol === "https:" || protocol === "http:")
            && origin.origin === originHeader
            && expected.origin === origin.origin
            && !expected.username && !expected.password
            && expected.pathname === "/" && !expected.search && !expected.hash;
    } catch {
        return false;
    }
}

/**
 * Identifies the caller for rate limiting from `CF-Connecting-IP`, which Cloudflare sets on every
 * inbound request and a client cannot forge. Generic forwarded headers are not client identity.
 * @param request the incoming request
 * @returns the caller's normalised address, or null when it cannot be established
 */
function clientKey(request: Request): string | null {
    const forwarded = request.headers.get("cf-connecting-ip");
    if (forwarded) {
        const version = isIP(forwarded);
        if (!version) return null;
        if (version !== 6) return forwarded;
        const address = `http://[${forwarded}]`;
        return URL.canParse(address) ? new URL(address).hostname : null;
    }
    return process.env.NODE_ENV === "production" ? null : "direct-development";
}

function validEmail(value: unknown): value is string {
    if (typeof value !== "string" || value.length > 254) return false;
    const [local, domain, extra] = value.split("@");
    return extra === undefined && Boolean(local) && Boolean(domain)
        && local.length <= 64 && !local.startsWith(".") && !local.endsWith(".") && !local.includes("..")
        && /^[a-z\d.!#$%&'*+/=?^_`{|}~-]+$/i.test(local)
        && domain.includes(".")
        && domain.split(".").every((label) => /^[a-z\d](?:[a-z\d-]{0,61}[a-z\d])?$/i.test(label));
}

async function boundedJson(body: Request | Response, maxBytes: number, signal: AbortSignal): Promise<unknown> {
    const length = body.headers.get("content-length");
    if (length !== null && (!/^\d+$/.test(length) || Number(length) > maxBytes)) {
        void body.body?.cancel().catch(() => {});
        throw new Error("Invalid body size");
    }
    if (!body.body) throw new Error("Missing body");
    const reader = body.body.getReader();
    const cancel = () => { void reader.cancel().catch(() => {}); };
    signal.addEventListener("abort", cancel, { once: true });
    let size = 0;
    const chunks: Uint8Array[] = [];
    try {
        for (;;) {
            signal.throwIfAborted();
            const { done, value } = await reader.read();
            signal.throwIfAborted();
            if (done) break;
            size += value.byteLength;
            if (size > maxBytes) throw new Error("Body too large");
            chunks.push(value);
        }
        const bytes = new Uint8Array(size);
        let offset = 0;
        for (const chunk of chunks) {
            bytes.set(chunk, offset);
            offset += chunk.byteLength;
        }
        return JSON.parse(new TextDecoder("utf-8", { fatal: true }).decode(bytes));
    } finally {
        signal.removeEventListener("abort", cancel);
        cancel();
        reader.releaseLock();
    }
}

/** Spacing state for one request's sequence of provider calls. */
type Pacer = { nextAt: number };

async function waitForProvider(pacer: Pacer, signal: AbortSignal): Promise<void> {
    const delay = pacer.nextAt - Date.now();
    if (delay > 0) {
        await new Promise<void>((resolve, reject) => {
            const onAbort = () => {
                clearTimeout(timer);
                reject(new Error("Signup interrupted"));
            };
            const timer = setTimeout(() => {
                signal.removeEventListener("abort", onAbort);
                resolve();
            }, delay);
            signal.addEventListener("abort", onAbort, { once: true });
            if (signal.aborted) onAbort();
        });
    }
    signal.throwIfAborted();
    pacer.nextAt = Date.now() + PROVIDER_INTERVAL_MS;
}

async function resend(
    path: string,
    key: string,
    signal: AbortSignal,
    pacer: Pacer,
    init?: { method?: "POST"; body?: object; allowNotFound?: boolean },
): Promise<unknown> {
    await waitForProvider(pacer, signal);
    const response = await fetch(`https://api.resend.com${path}`, {
        method: init?.method ?? "GET",
        headers: {
            Authorization: `Bearer ${key}`,
            Accept: "application/json",
            ...(init?.body ? { "Content-Type": "application/json" } : {}),
        },
        ...(init?.body ? { body: JSON.stringify(init.body) } : {}),
        cache: "no-store",
        credentials: "omit",
        redirect: "manual",
        signal,
    });
    if (response.status === 404 && init?.allowNotFound) {
        void response.body?.cancel().catch(() => {});
        return CONTACT_NOT_FOUND;
    }
    if (!response.ok || response.headers.get("content-type")?.split(";")[0].trim() !== "application/json") {
        void response.body?.cancel().catch(() => {});
        throw new Error("Signup provider unavailable");
    }
    return boundedJson(response, RESPONSE_BYTES, signal);
}

function isSegmentList(segments: unknown): segments is { object: "list"; data: unknown[] } {
    return isRecord(segments) && segments.object === "list" && Array.isArray(segments.data)
        && segments.data.length <= 100;
}

function includesLaunchSegment(segments: unknown, segment: string): boolean {
    return isSegmentList(segments) && segments.data.some((entry) => isRecord(entry) && entry.id === segment);
}

/**
 * Records a signup in the launch segment. The contact is looked up before anything is written,
 * because creating an existing address has no documented merge contract. A returned contact ID alone
 * does not establish segment membership for a repeat signup, so membership is read back and added
 * only when missing.
 * @param email validated address
 * @param segment launch segment ID
 * @param key Resend API key
 * @param signal aborts the provider calls
 * @param pacer spacing state for this request's provider calls
 * @returns whether the contact is confirmed subscribed and in the segment
 */
async function persistSignup(email: string, segment: string, key: string, signal: AbortSignal, pacer: Pacer): Promise<boolean> {
    let contact = await resend(`/contacts/${encodeURIComponent(email)}`, key, signal, pacer, { allowNotFound: true });
    if (contact === CONTACT_NOT_FOUND) {
        const created = await resend("/contacts", key, signal, pacer, {
            method: "POST",
            body: { email, segments: [{ id: segment }] },
        });
        if (!isRecord(created) || created.object !== "contact" || typeof created.id !== "string" || !UUID.test(created.id)) {
            return false;
        }
        contact = await resend(`/contacts/${created.id}`, key, signal, pacer);
        if (!isRecord(contact) || contact.id !== created.id) return false;
    }
    if (!isRecord(contact) || typeof contact.id !== "string" || !UUID.test(contact.id) || contact.unsubscribed !== false
        || typeof contact.email !== "string" || contact.email.toLowerCase() !== email.toLowerCase()) {
        return false;
    }
    const segments = await resend(`/contacts/${contact.id}/segments`, key, signal, pacer);
    if (!isSegmentList(segments)) return false;
    if (includesLaunchSegment(segments, segment)) return true;
    const added = await resend(`/contacts/${contact.id}/segments/${segment}`, key, signal, pacer, { method: "POST" });
    if (!isRecord(added) || added.id !== segment) return false;
    return includesLaunchSegment(await resend(`/contacts/${contact.id}/segments`, key, signal, pacer), segment);
}

/**
 * Accepts anonymous launch signups only after Resend confirms the contact and launch membership.
 *
 * The rate-limit reservation is claimed after validation. The limiter holds each caller for the
 * provider spacing interval, so admitting unvalidated requests would let malformed traffic occupy it.
 * @param request the signup submission
 * @returns the signup outcome
 */
export async function POST(request: Request): Promise<Response> {
    if (request.method !== "POST") return invalidRequest(405);
    if (!sameOrigin(request)) return invalidRequest(403);
    if (request.headers.get("content-type")?.split(";")[0].trim().toLowerCase() !== "application/json"
        || ![null, "identity"].includes(request.headers.get("content-encoding"))) {
        return invalidRequest(415);
    }
    const key = process.env.RESEND_API_KEY?.trim();
    const segment = process.env.RESEND_LAUNCH_SEGMENT_ID?.trim();
    if (!key || /\s/.test(key) || !segment || !UUID.test(segment)) return unavailable();
    const client = clientKey(request);
    if (!client) return unavailable();

    const bodyController = new AbortController();
    const bodyTimer = setTimeout(() => bodyController.abort(), BODY_TIMEOUT_MS);
    let body: unknown;
    try {
        body = await boundedJson(request, REQUEST_BYTES, AbortSignal.any([request.signal, bodyController.signal]));
    } catch {
        return invalidRequest();
    } finally {
        clearTimeout(bodyTimer);
    }
    if (!isRecord(body) || Object.keys(body).some((name) => name !== "email" && name !== "website")
        || (body.website !== undefined && (typeof body.website !== "string" || body.website.trim() !== ""))) {
        return invalidRequest();
    }
    const email = typeof body.email === "string" ? body.email.trim() : body.email;
    if (!validEmail(email)) return json({ error: "invalid_email" }, 400);

    const limiter = getCloudflareContext().env.SIGNUP_LIMITER;
    const reservation = await limiter.get(limiter.idFromName("launch")).reserve(client);
    if (!reservation.allowed) return json({ error: "rate_limited" }, 429, reservation.retryAfter);

    const pacer: Pacer = { nextAt: 0 };
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), PROVIDER_TIMEOUT_MS);
    try {
        const saved = await persistSignup(email, segment, key, AbortSignal.any([request.signal, controller.signal]), pacer);
        return saved ? json({ status: "subscribed" }, 200) : unavailable();
    } catch {
        return unavailable();
    } finally {
        clearTimeout(timer);
    }
}
