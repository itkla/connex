/** @vitest-environment jsdom */
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const EVENT_KEY = "connex:client-request-identity";
const ACCOUNT_KEY = "connex:browser-account";
const DATA_KEYS = [
    "connex:recents:7:11",
    "connex:recents:7:12",
    "connex:view:7:11:ask-connex:pinned",
    "connex:view:7:11:ask-connex:session",
    "connex:view:7:11:ask-connex:turn",
    "connex:business-card-import-recovery:v2:old-account:request",
    "connex:columns:persons:7:11",
    "connex:density:7:11",
    "connex:sidebar-mode:7:11",
    "connex:sidebar-sections:7:11",
];
const RETURN_KEYS = [
    "connex:record-return-context",
    "connex:record-return-selection",
];
const DRAFT_KEY = "connex:draft:7:11:note:new";
const storage = window.localStorage;
const drafts = window.sessionStorage;
const reload = vi.fn();
const replace = vi.fn();
let onStorage: (event: { key: string; newValue: string }) => void;
let api: typeof import("@/app/lib/api");
let fetcher: ReturnType<typeof vi.fn<typeof fetch>>;

function seedData() {
    for (const key of DATA_KEYS) storage.setItem(key, "Unique private contact");
    for (const key of RETURN_KEYS) drafts.setItem(key, "/records/persons/42");
    storage.setItem(ACCOUNT_KEY, "7");
    storage.setItem("theme", "dark");
    storage.setItem("calendar:view", "month");
    drafts.setItem(DRAFT_KEY, "Recoverable private draft");
}

function expectCleared() {
    for (const key of DATA_KEYS) expect(storage.getItem(key), key).toBeNull();
    for (const key of RETURN_KEYS) expect(drafts.getItem(key), key).toBeNull();
    expect(storage.getItem("theme")).toBe("dark");
    expect(storage.getItem("calendar:view")).toBe("month");
}

function expectRetained() {
    for (const key of DATA_KEYS) expect(storage.getItem(key), key).not.toBeNull();
    for (const key of RETURN_KEYS) expect(drafts.getItem(key), key).not.toBeNull();
}

function markerRejectingStorage(): Storage {
    return {
        get length() { return storage.length; },
        key: (index: number) => storage.key(index),
        getItem: (key: string) => storage.getItem(key),
        removeItem: (key: string) => storage.removeItem(key),
        clear: () => storage.clear(),
        setItem: (key: string, value: string) => {
            if (key === ACCOUNT_KEY) throw new DOMException("Storage is full", "QuotaExceededError");
            storage.setItem(key, value);
        },
    };
}

beforeEach(async () => {
    vi.resetModules();
    vi.clearAllMocks();
    storage.clear();
    drafts.clear();
    seedData();
    vi.stubGlobal("window", {
        localStorage: storage,
        sessionStorage: drafts,
        crypto: globalThis.crypto,
        location: { pathname: "/dashboard", reload, replace },
        addEventListener: (type: string, listener: typeof onStorage) => {
            if (type === "storage") onStorage = listener;
        },
    });
    fetcher = vi.fn<typeof fetch>(async (input) => String(input).endsWith("/csrf")
        ? Response.json({ token: "csrf-test", headerName: "X-CSRF-TOKEN", requestIdentity: "identity-test" })
        : new Response(null, { status: 204 }));
    vi.stubGlobal("fetch", fetcher);
    api = await import("@/app/lib/api");
});

afterEach(() => {
    storage.clear();
    drafts.clear();
    vi.unstubAllGlobals();
});

describe("account browser storage cleanup", () => {
    it("clears every account scope on logout and broadcasts the transition", async () => {
        await api.logout();
        expectCleared();
        expect(storage.getItem(ACCOUNT_KEY)).toBeNull();
        expect(drafts.getItem(DRAFT_KEY)).toBeNull();
        expect(storage.getItem(EVENT_KEY)).toMatch(/^logout:/);
        expect(fetcher.mock.calls.some(([url, init]) => url === "/api/auth/logout" && init?.method === "POST")).toBe(true);
    });

    it("clears account data even if the logout request fails", async () => {
        fetcher.mockRejectedValue(new TypeError("Network unavailable"));
        await expect(api.logout()).rejects.toThrow();
        expectCleared();
    });

    it("keeps the signed-in user's own data when the same account authenticates again", async () => {
        const pending = api.login({ username: "same-user", password: "test-password" });
        expect(storage.getItem(ACCOUNT_KEY)).toBe("7");
        await pending;
        api.synchronizeBrowserAccount(7);
        expectRetained();
        expect(storage.getItem(ACCOUNT_KEY)).toBe("7");
        expect(drafts.getItem(DRAFT_KEY)).toBe("Recoverable private draft");
    });

    it("clears the previous account's data once a different account authenticates", async () => {
        await api.login({ username: "next-user", password: "test-password" });
        expectRetained();
        api.synchronizeBrowserAccount(8);
        expectCleared();
        expect(storage.getItem(ACCOUNT_KEY)).toBe("8");
        expect(drafts.getItem(DRAFT_KEY)).toBeNull();
        expect(storage.getItem(EVENT_KEY)).toMatch(/^refresh:/);
    });

    it("adopts a browser that has no marker yet without discarding the signed-in user's data", async () => {
        storage.removeItem(ACCOUNT_KEY);
        storage.removeItem(EVENT_KEY);
        vi.resetModules();
        const firstRun = await import("@/app/lib/api");

        firstRun.synchronizeBrowserAccount(7);

        expectRetained();
        expect(storage.getItem(ACCOUNT_KEY)).toBe("7");
        expect(drafts.getItem(DRAFT_KEY)).toBe("Recoverable private draft");
        expect(storage.getItem(EVENT_KEY)).toBeNull();
    });

    it("keeps a failed marker write from repeating the account transition on every mount", () => {
        Object.defineProperty(window, "localStorage", { configurable: true, value: markerRejectingStorage() });
        api.synchronizeBrowserAccount(8);
        expectCleared();
        expect(drafts.getItem(DRAFT_KEY)).toBeNull();
        expect(storage.getItem(ACCOUNT_KEY)).toBe("7");

        storage.setItem(EVENT_KEY, "");
        seedData();
        api.synchronizeBrowserAccount(8);
        expectRetained();
        expect(drafts.getItem(DRAFT_KEY)).toBe("Recoverable private draft");
        expect(storage.getItem(EVENT_KEY)).toBe("");
    });

    it("retains the confirmed account and its data when a login attempt fails", async () => {
        fetcher.mockImplementation(async (input) => {
            if (String(input).endsWith("/csrf")) {
                return Response.json({ token: "csrf-test", headerName: "X-CSRF-TOKEN", requestIdentity: "identity-test" });
            }
            throw new TypeError("Network unavailable");
        });
        await expect(api.login({ username: "next-user", password: "test-password" })).rejects.toThrow();
        for (const key of DATA_KEYS) expect(storage.getItem(key), key).not.toBeNull();
        expect(storage.getItem(ACCOUNT_KEY)).toBe("7");
    });

    it("broadcasts the transition when a different account is adopted without a stored marker", () => {
        storage.removeItem(ACCOUNT_KEY);
        storage.removeItem(EVENT_KEY);
        api.synchronizeBrowserAccount(8);
        expectCleared();
        expect(storage.getItem(ACCOUNT_KEY)).toBe("8");
        expect(storage.getItem(EVENT_KEY)).toMatch(/^refresh:/);
    });

    it.each(["account", "logout"])("clears account data on a %s broadcast from another tab", (transition) => {
        onStorage({ key: EVENT_KEY, newValue: `${transition}:other-tab` });
        expectCleared();
        if (transition === "logout") {
            expect(drafts.getItem(DRAFT_KEY)).toBeNull();
            expect(reload).toHaveBeenCalledOnce();
        }
    });

    it("keeps account data on a refresh broadcast that confirms the same account", () => {
        onStorage({ key: EVENT_KEY, newValue: "refresh:other-tab" });
        expectRetained();
        expect(storage.getItem(ACCOUNT_KEY)).toBe("7");
        expect(reload).toHaveBeenCalledOnce();
    });

    it("clears account data on a refresh broadcast after another tab confirmed a different account", () => {
        storage.setItem(ACCOUNT_KEY, "8");
        onStorage({ key: EVENT_KEY, newValue: "refresh:other-tab" });
        expectCleared();
        expect(storage.getItem(ACCOUNT_KEY)).toBe("8");
        expect(reload).toHaveBeenCalledOnce();
    });

    it("preserves data on workspace-only transitions and unrelated storage events", () => {
        onStorage({ key: EVENT_KEY, newValue: "workspace:other-tab" });
        onStorage({ key: "theme", newValue: "light" });
        for (const key of DATA_KEYS) expect(storage.getItem(key)).not.toBeNull();
    });

    it("clears stale account data when a server-rendered account identity changes", () => {
        api.synchronizeBrowserAccount(8);
        expectCleared();
        expect(storage.getItem(ACCOUNT_KEY)).toBe("8");
        expect(drafts.getItem(DRAFT_KEY)).toBeNull();
        expect(storage.getItem(EVENT_KEY)).toMatch(/^refresh:/);
    });

    it("clears legacy account data on first authenticated adoption and retains the same account on reload", () => {
        storage.removeItem(ACCOUNT_KEY);
        api.synchronizeBrowserAccount(8);
        expectCleared();
        storage.setItem("connex:recents:8:20", "Current account contact");
        api.synchronizeBrowserAccount(8);
        expect(storage.getItem("connex:recents:8:20")).toBe("Current account contact");
    });

    it("does not prevent logout when browser storage is unavailable", async () => {
        Object.defineProperty(window, "localStorage", { get: () => { throw new Error("Storage unavailable"); } });
        await expect(api.logout()).resolves.toBeUndefined();
        expect(fetcher.mock.calls.some(([url]) => url === "/api/auth/logout")).toBe(true);
    });
});
