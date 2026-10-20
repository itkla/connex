import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

function json(body: unknown, status = 200): Response {
    return new Response(JSON.stringify(body), {
        status,
        headers: { "Content-Type": "application/json" },
    });
}

function bootstrap(token = "csrf-token", requestIdentity = "session-identity"): Response {
    return json({ token, headerName: "X-XSRF-TOKEN", requestIdentity });
}

function backend(csrf: () => Promise<Response>) {
    const fetchMock = vi.fn(async (input: string | URL | Request, init?: RequestInit) => {
        const url = input instanceof Request ? input.url : String(input);
        if (url.endsWith("/api/auth/csrf")) return csrf();
        if (url.endsWith("/api/auth/logout")) return new Response(null, { status: 204 });
        return json({ method: init?.method });
    });
    vi.stubGlobal("fetch", fetchMock);
    return fetchMock;
}

describe("API client CSRF bootstrap", () => {
    let storageListeners: Array<(event: Pick<StorageEvent, "key" | "newValue">) => void>;

    function changeIdentity() {
        for (const listener of storageListeners) {
            listener({ key: "connex:client-request-identity", newValue: "account:other-tab" });
        }
    }

    beforeEach(() => {
        storageListeners = [];
        vi.stubGlobal("window", {
            addEventListener: (type: string, listener: (event: Pick<StorageEvent, "key" | "newValue">) => void) => {
                if (type === "storage") storageListeners.push(listener);
            },
            crypto: { randomUUID: () => "event-id" },
            localStorage: { getItem: () => null, setItem: vi.fn(), removeItem: vi.fn(), length: 0 },
            sessionStorage: { removeItem: vi.fn() },
            location: { pathname: "/ask-connex/7", search: "" },
        });
        vi.stubGlobal("document", { cookie: "connex_workspace=5; NEXT_LOCALE=en" });
    });

    afterEach(() => {
        vi.resetModules();
        vi.unstubAllGlobals();
    });

    it("shares one fetch across N concurrent header requests and caches the result", async () => {
        const pending = Promise.withResolvers<Response>();
        const csrf = vi.fn(() => pending.promise);
        const fetchMock = backend(csrf);
        const { csrfHeader } = await import("@/app/lib/api");

        const headers = Array.from({ length: 12 }, () => csrfHeader());

        expect(fetchMock).toHaveBeenCalledExactlyOnceWith("/api/auth/csrf", {
            credentials: "include", cache: "no-store",
        });
        pending.resolve(bootstrap());
        expect(await Promise.all(headers)).toEqual(Array.from({ length: 12 }, () => ({ "X-XSRF-TOKEN": "csrf-token" })));
        await expect(csrfHeader()).resolves.toEqual({ "X-XSRF-TOKEN": "csrf-token" });
        expect(csrf).toHaveBeenCalledOnce();
    });

    it("coalesces concurrent forced refreshes with ordinary callers", async () => {
        const pending = Promise.withResolvers<Response>();
        const csrf = vi.fn<() => Promise<Response>>()
            .mockResolvedValueOnce(bootstrap("old-token"))
            .mockImplementation(() => pending.promise);
        backend(csrf);
        const { csrfHeader } = await import("@/app/lib/api");
        await csrfHeader();

        const headers = Array.from({ length: 12 }, (_, index) => csrfHeader(index % 2 === 0));

        expect(csrf).toHaveBeenCalledTimes(2);
        pending.resolve(bootstrap("fresh-token"));
        expect(await Promise.all(headers)).toEqual(Array.from({ length: 12 }, () => ({ "X-XSRF-TOKEN": "fresh-token" })));
        expect(csrf).toHaveBeenCalledTimes(2);
    });

    it.each(["conflict", "network"] as const)("releases a failed %s bootstrap so the next caller can retry", async (failure) => {
        const pending = Promise.withResolvers<Response>();
        const csrf = vi.fn<() => Promise<Response>>()
            .mockImplementationOnce(() => pending.promise)
            .mockResolvedValueOnce(bootstrap());
        backend(csrf);
        const { csrfHeader } = await import("@/app/lib/api");
        const headers = Array.from({ length: 4 }, () => csrfHeader());

        if (failure === "conflict") pending.resolve(json({}, 409));
        else pending.reject(new TypeError("Network unavailable"));

        expect(await Promise.all(headers)).toEqual([{}, {}, {}, {}]);
        await expect(csrfHeader()).resolves.toEqual({ "X-XSRF-TOKEN": "csrf-token" });
        expect(csrf).toHaveBeenCalledTimes(2);
    });

    it("clears the token cache on sign-out, including the bootstrap used for logout", async () => {
        const csrf = vi.fn<() => Promise<Response>>()
            .mockResolvedValueOnce(bootstrap("signed-in"))
            .mockResolvedValueOnce(bootstrap("logout-token"))
            .mockResolvedValueOnce(bootstrap("signed-out", "anonymous-identity"));
        const fetchMock = backend(csrf);
        const { csrfHeader, logout } = await import("@/app/lib/api");
        await csrfHeader();

        await logout();

        const logoutRequest = fetchMock.mock.calls.find(([input]) => String(input).endsWith("/api/auth/logout"));
        expect(new Headers(logoutRequest?.[1]?.headers).get("X-XSRF-TOKEN")).toBe("logout-token");
        await expect(csrfHeader()).resolves.toEqual({ "X-XSRF-TOKEN": "signed-out" });
        expect(csrf).toHaveBeenCalledTimes(3);
    });

    it.each(["login", "register"] as const)("clears the previous session token across %s", async (action) => {
        const csrf = vi.fn<() => Promise<Response>>()
            .mockResolvedValueOnce(bootstrap("previous-session"))
            .mockResolvedValueOnce(bootstrap("auth-request"))
            .mockResolvedValueOnce(bootstrap("new-session", "new-identity"));
        backend(csrf);
        const api = await import("@/app/lib/api");
        await api.csrfHeader();

        await api[action]({ username: "new-user", password: "test-password", displayName: "Test", email: "test@example.com" });

        await expect(api.csrfHeader()).resolves.toEqual({ "X-XSRF-TOKEN": "new-session" });
        expect(csrf).toHaveBeenCalledTimes(3);
    });

    it("clears a cached token when another tab changes identity", async () => {
        const csrf = vi.fn<() => Promise<Response>>()
            .mockResolvedValueOnce(bootstrap("old-token"))
            .mockResolvedValueOnce(bootstrap("new-token", "new-identity"));
        backend(csrf);
        const { csrfHeader } = await import("@/app/lib/api");
        await csrfHeader();

        changeIdentity();

        await expect(csrfHeader()).resolves.toEqual({ "X-XSRF-TOKEN": "new-token" });
        expect(csrf).toHaveBeenCalledTimes(2);
    });

    it("discards an old identity's late response without clearing the new in-flight request", async () => {
        const previous = Promise.withResolvers<Response>();
        const current = Promise.withResolvers<Response>();
        const csrf = vi.fn<() => Promise<Response>>()
            .mockImplementationOnce(() => previous.promise)
            .mockImplementationOnce(() => current.promise);
        backend(csrf);
        const { csrfHeader } = await import("@/app/lib/api");
        const oldHeader = csrfHeader();
        changeIdentity();
        const newHeader = csrfHeader();

        previous.resolve(bootstrap("old-token"));
        await expect(oldHeader).resolves.toEqual({});
        const concurrentHeader = csrfHeader();
        expect(csrf).toHaveBeenCalledTimes(2);

        current.resolve(bootstrap("new-token", "new-identity"));
        expect(await Promise.all([newHeader, concurrentHeader])).toEqual([
            { "X-XSRF-TOKEN": "new-token" }, { "X-XSRF-TOKEN": "new-token" },
        ]);
        await expect(csrfHeader()).resolves.toEqual({ "X-XSRF-TOKEN": "new-token" });
        expect(csrf).toHaveBeenCalledTimes(2);
    });

    it("shares bootstraps between realtime headers and AI identity checks while keeping the completion check fresh", async () => {
        const pending = Promise.withResolvers<Response>();
        const csrf = vi.fn<() => Promise<Response>>()
            .mockImplementationOnce(() => pending.promise)
            .mockResolvedValueOnce(bootstrap());
        const fetchMock = backend(csrf);
        const { csrfHeader, startAiChatTurn } = await import("@/app/lib/api");

        const header = csrfHeader();
        const turn = startAiChatTurn(7, { content: "Review this record" });
        expect(csrf).toHaveBeenCalledOnce();

        pending.resolve(bootstrap());
        await header;
        await turn;

        const mutation = fetchMock.mock.calls.find(([input]) => String(input).endsWith("/sessions/7/turns"));
        expect(new Headers(mutation?.[1]?.headers).get("X-XSRF-TOKEN")).toBe("csrf-token");
        expect(csrf).toHaveBeenCalledTimes(2);
    });

    it("refuses a pending AI identity check after a transition without caching its old token", async () => {
        const pending = Promise.withResolvers<Response>();
        const csrf = vi.fn<() => Promise<Response>>()
            .mockImplementationOnce(() => pending.promise)
            .mockResolvedValueOnce(bootstrap("new-token", "new-identity"));
        const fetchMock = backend(csrf);
        const { csrfHeader, startAiChatTurn } = await import("@/app/lib/api");
        const turn = startAiChatTurn(7, { content: "Review this record" });
        const refused = expect(turn).rejects.toThrow("The workspace changed before this request was sent");

        changeIdentity();
        pending.resolve(bootstrap("old-token"));
        await refused;

        expect(fetchMock.mock.calls.every(([input]) => String(input).endsWith("/api/auth/csrf"))).toBe(true);
        await expect(csrfHeader()).resolves.toEqual({ "X-XSRF-TOKEN": "new-token" });
        expect(csrf).toHaveBeenCalledTimes(2);
    });
});
