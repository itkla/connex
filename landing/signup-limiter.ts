import { DurableObject } from "cloudflare:workers";

const CLIENT_WINDOW_MS = 15 * 60 * 1000;
const CLIENT_REQUESTS = 5;
const GLOBAL_WINDOW_MS = 60 * 1000;
const GLOBAL_SIGNUPS = 30;
const PROVIDER_INTERVAL_MS = 550;
const GLOBAL_KEY = "global";
const PROVIDER_KEY = "providerNextAt";
const CLIENT_PREFIX = "client:";

type Window = { count: number; expiresAt: number };

/** A granted signup slot, or a refusal carrying the seconds until the caller may retry. */
export type Reservation = { allowed: true } | { allowed: false; retryAfter: number };

function sleep(ms: number): Promise<void> {
    return new Promise((resolve) => setTimeout(resolve, ms));
}

function retryAfter(expiresAt: number, now: number): number {
    return Math.max(1, Math.ceil((expiresAt - now) / 1000));
}

/**
 * Serializes launch signups across the whole deployment.
 *
 * The product application keeps these windows in module memory, which a Worker cannot: isolates are
 * per-location and short-lived, so a module-level counter would reset constantly and enforce nothing.
 * A single Durable Object instance is the coordination point that restores the original semantics.
 */
export class SignupLimiter extends DurableObject {
    /**
     * Claims one signup attempt for a client.
     * @param client the caller's address
     * @returns whether the attempt may proceed, and when to retry if not
     */
    async reserve(client: string): Promise<Reservation> {
        const now = Date.now();

        const storedGlobal = await this.ctx.storage.get<Window>(GLOBAL_KEY);
        const globalWindow = !storedGlobal || storedGlobal.expiresAt <= now
            ? { count: 0, expiresAt: now + GLOBAL_WINDOW_MS }
            : storedGlobal;
        if (globalWindow.count >= GLOBAL_SIGNUPS) {
            return { allowed: false, retryAfter: retryAfter(globalWindow.expiresAt, now) };
        }

        const clientKey = `${CLIENT_PREFIX}${client}`;
        const storedClient = await this.ctx.storage.get<Window>(clientKey);
        const clientWindow = !storedClient || storedClient.expiresAt <= now
            ? { count: 0, expiresAt: now + CLIENT_WINDOW_MS }
            : storedClient;
        if (clientWindow.count >= CLIENT_REQUESTS) {
            return { allowed: false, retryAfter: retryAfter(clientWindow.expiresAt, now) };
        }

        globalWindow.count += 1;
        clientWindow.count += 1;
        await this.ctx.storage.put({ [GLOBAL_KEY]: globalWindow, [clientKey]: clientWindow });

        // Holding the object here is what spaces provider traffic: the instance is single-threaded,
        // so concurrent signups queue behind this wait rather than reaching Resend together.
        const nextAt = (await this.ctx.storage.get<number>(PROVIDER_KEY)) ?? 0;
        const delay = nextAt - Date.now();
        if (delay > 0) await sleep(delay);
        await this.ctx.storage.put(PROVIDER_KEY, Date.now() + PROVIDER_INTERVAL_MS);

        if ((await this.ctx.storage.getAlarm()) === null) {
            await this.ctx.storage.setAlarm(clientWindow.expiresAt);
        }
        return { allowed: true };
    }

    /** Drops expired client windows so storage tracks live callers rather than every visitor. */
    async alarm(): Promise<void> {
        const now = Date.now();
        const windows = await this.ctx.storage.list<Window>({ prefix: CLIENT_PREFIX });
        let earliest = Number.POSITIVE_INFINITY;
        const expired: string[] = [];
        for (const [key, window] of windows) {
            if (window.expiresAt <= now) expired.push(key);
            else earliest = Math.min(earliest, window.expiresAt);
        }
        if (expired.length > 0) await this.ctx.storage.delete(expired);
        if (earliest !== Number.POSITIVE_INFINITY) await this.ctx.storage.setAlarm(earliest);
    }
}
