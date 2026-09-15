/** @vitest-environment jsdom */
import { act, useEffect } from "react";
import { createRoot, type Root } from "react-dom/client";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

vi.mock("@/app/hooks/useActions", () => ({ useActions: () => ({ context: { user: { id: 7 } } }) }));
vi.mock("@/app/hooks/useWorkspace", () => ({ useWorkspace: () => ({ activeWorkspaceId: 11 }) }));

import { RecentRecordsProvider, useRecentRecords } from "@/app/hooks/useRecentRecords";
import { login, logout, synchronizeBrowserAccount } from "@/app/lib/api";

const EVENT_KEY = "connex:client-request-identity";
const ACCOUNT_KEY = "connex:browser-account";
const RECENTS_KEY = "connex:recents:7:11";
const storage = window.localStorage;
const reload = vi.fn();
const fetcher = vi.fn<typeof fetch>();
let container: HTMLDivElement;
let root: Root;
let probe: ReturnType<typeof useRecentRecords> | null = null;
let restoreLocation: (() => void) | null = null;

function Probe() {
    const value = useRecentRecords();
    useEffect(() => { probe = value; });
    return null;
}

function recents(): ReturnType<typeof useRecentRecords> {
    if (probe === null) throw new Error("Recent records provider is not mounted");
    return probe;
}

function stubLocation() {
    const real = window.location;
    Object.defineProperty(window, "location", {
        configurable: true,
        value: {
            get href() { return real.href; },
            get origin() { return real.origin; },
            get pathname() { return real.pathname; },
            reload,
        },
    });
    restoreLocation = () => Object.defineProperty(window, "location", { configurable: true, value: real });
}

function snapshot(): Record<string, string> {
    const entries: Record<string, string> = {};
    for (let index = 0; index < storage.length; index += 1) {
        const key = storage.key(index);
        if (key !== null) entries[key] = storage.getItem(key) ?? "";
    }
    return entries;
}

async function mountProvider() {
    await act(async () => root.render(<RecentRecordsProvider><Probe /></RecentRecordsProvider>));
}

async function viewRecord(label: string) {
    await act(async () => recents().record({ type: "person", id: 42, label }));
}

beforeEach(() => {
    vi.clearAllMocks();
    vi.stubGlobal("IS_REACT_ACT_ENVIRONMENT", true);
    fetcher.mockImplementation(async (input) => String(input).endsWith("/csrf")
        ? Response.json({ token: "csrf-test", headerName: "X-CSRF-TOKEN", requestIdentity: "identity-test" })
        : new Response(null, { status: 204 }));
    vi.stubGlobal("fetch", fetcher);
    stubLocation();
    storage.clear();
    window.sessionStorage.clear();
    probe = null;
    container = document.createElement("div");
    document.body.append(container);
    root = createRoot(container);
});

afterEach(async () => {
    await act(async () => root.unmount());
    container.remove();
    storage.clear();
    window.sessionStorage.clear();
    restoreLocation?.();
    restoreLocation = null;
    vi.unstubAllGlobals();
});

describe("tenant-data writers mounted across an account transition", () => {
    it("refuses writes from a provider that outlived logout and an account replacement", async () => {
        act(() => synchronizeBrowserAccount(7));
        storage.setItem(RECENTS_KEY, JSON.stringify({ v: 1, items: [{ t: "person", id: 42, label: "Kaori Tanabe", ts: 1 }] }));
        await mountProvider();
        expect(recents().recents.map((entry) => entry.label)).toEqual(["Kaori Tanabe"]);
        await viewRecord("Second private contact");
        expect(storage.getItem(RECENTS_KEY)).toContain("Second private contact");

        await act(async () => { await logout(); });
        expect(storage.getItem(RECENTS_KEY)).toBeNull();
        expect(recents().recents).toEqual([]);

        act(() => synchronizeBrowserAccount(8));
        const before = snapshot();
        await viewRecord("Kaori Tanabe");
        expect(storage.getItem(RECENTS_KEY)).toBeNull();
        expect(snapshot()).toEqual(before);
        expect(recents().recents).toEqual([]);
    });

    it("persists again once the same account survives a failed logout", async () => {
        act(() => synchronizeBrowserAccount(7));
        storage.setItem(RECENTS_KEY, JSON.stringify({ v: 1, items: [{ t: "person", id: 42, label: "Kaori Tanabe", ts: 1 }] }));
        await mountProvider();
        expect(recents().recents.map((entry) => entry.label)).toEqual(["Kaori Tanabe"]);

        fetcher.mockImplementation(async (input) => {
            if (String(input).endsWith("/csrf")) {
                return Response.json({ token: "csrf-test", headerName: "X-CSRF-TOKEN", requestIdentity: "identity-test" });
            }
            throw new TypeError("Network unavailable");
        });
        await act(async () => { await expect(logout()).rejects.toThrow(); });
        expect(storage.getItem(RECENTS_KEY)).toBeNull();
        expect(recents().recents).toEqual([]);
        expect(storage.getItem(ACCOUNT_KEY)).toBe("7");

        await viewRecord("Still the signed-in user");
        expect(storage.getItem(RECENTS_KEY)).toContain("Still the signed-in user");
        expect(recents().recents.map((entry) => entry.label)).toEqual(["Still the signed-in user"]);
    });

    it("stops persisting when the account marker is removed without a logout broadcast", async () => {
        act(() => synchronizeBrowserAccount(7));
        storage.setItem(RECENTS_KEY, JSON.stringify({ v: 1, items: [{ t: "person", id: 42, label: "Kaori Tanabe", ts: 1 }] }));
        await mountProvider();
        expect(recents().recents.map((entry) => entry.label)).toEqual(["Kaori Tanabe"]);

        storage.removeItem(ACCOUNT_KEY);
        await act(async () => {
            window.dispatchEvent(new StorageEvent("storage", { key: ACCOUNT_KEY, newValue: null }));
        });

        expect(storage.getItem(RECENTS_KEY)).toBeNull();
        expect(reload).not.toHaveBeenCalled();
        const before = snapshot();
        await viewRecord("Kaori Tanabe");
        expect(storage.getItem(RECENTS_KEY)).toBeNull();
        expect(snapshot()).toEqual(before);
        expect(recents().recents).toEqual([]);
    });

    it("keeps persisting after a failed login and stops once another tab adopts a different account", async () => {
        act(() => synchronizeBrowserAccount(7));
        storage.setItem(RECENTS_KEY, JSON.stringify({ v: 1, items: [{ t: "person", id: 42, label: "Kaori Tanabe", ts: 1 }] }));
        await mountProvider();

        fetcher.mockImplementation(async (input) => {
            if (String(input).endsWith("/csrf")) {
                return Response.json({ token: "csrf-test", headerName: "X-CSRF-TOKEN", requestIdentity: "identity-test" });
            }
            throw new TypeError("Network unavailable");
        });
        await expect(login({ username: "other-user", password: "test-password" })).rejects.toThrow();
        expect(storage.getItem(ACCOUNT_KEY)).toBe("7");
        await viewRecord("Still the signed-in user");
        expect(storage.getItem(RECENTS_KEY)).toContain("Still the signed-in user");

        storage.setItem(ACCOUNT_KEY, "8");
        await act(async () => {
            window.dispatchEvent(new StorageEvent("storage", { key: EVENT_KEY, newValue: "refresh:other-tab" }));
        });
        expect(reload).toHaveBeenCalled();
        expect(storage.getItem(RECENTS_KEY)).toBeNull();

        const before = snapshot();
        await viewRecord("Kaori Tanabe");
        expect(storage.getItem(RECENTS_KEY)).toBeNull();
        expect(snapshot()).toEqual(before);
        expect(recents().recents).toEqual([]);
    });
});
