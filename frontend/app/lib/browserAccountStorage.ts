const ACCOUNT_KEY = "connex:browser-account";
const DATA_PREFIXES = [
    "connex:recents:",
    "connex:view:",
    "connex:business-card-import-recovery:",
    "connex:columns:",
    "connex:density:",
    "connex:sidebar-mode:",
    "connex:sidebar-sections:",
];
const SESSION_DATA_KEYS = [
    "connex:record-return-context",
    "connex:record-return-selection",
];

/** How an authenticated account resolved against the account this browser last confirmed. */
export type AccountAdoption = "unchanged" | "adopted" | "replaced";

/** The account a mounted reader acquired its data under, and the revocation counter it holds. */
export type AccountBinding = {
    readonly generation: number;
    readonly identity: string | null;
};

let accountGeneration = 0;
let markerIdentity = readAccountMarker();
let accountIdentity = markerIdentity;
const generationListeners = new Set<(binding: AccountBinding) => void>();

if (typeof window !== "undefined") {
    window.addEventListener("storage", (event) => {
        if (event.key !== null && event.key !== ACCOUNT_KEY) return;
        reconcileBrowserAccount();
    });
}

function readAccountMarker(): string | null {
    if (typeof window === "undefined") return null;
    try {
        return window.localStorage.getItem(ACCOUNT_KEY);
    } catch {
        return null;
    }
}

function writeAccountMarker(identity: string): boolean {
    try {
        window.localStorage.setItem(ACCOUNT_KEY, identity);
        markerIdentity = identity;
        return true;
    } catch {
        return false;
    }
}

/** Captures the account and generation under which a mounted reader or operation acquired data. */
export function currentAccountBinding(): AccountBinding {
    return { generation: accountGeneration, identity: accountIdentity };
}

/** Refuses writers whose account binding has been revoked, even when navigation was cancelled. */
export function isCurrentBinding(binding: AccountBinding): boolean {
    return binding.generation === accountGeneration && binding.identity === accountIdentity;
}

/** Subscribes mounted caches to revocation, delivering the binding that replaced theirs. */
export function subscribeAccountGeneration(listener: (binding: AccountBinding) => void): () => void {
    generationListeners.add(listener);
    return () => { generationListeners.delete(listener); };
}

/** Revokes mounted writers and removes tenant data without forgetting the last confirmed account. */
export function invalidateAccountStorage(): void {
    accountGeneration += 1;
    clearTenantBrowserStorage();
    const binding = currentAccountBinding();
    for (const listener of [...generationListeners]) {
        try {
            listener(binding);
        } catch (error) {
            queueMicrotask(() => { throw error; });
        }
    }
}

/** Forgets the account only after a confirmed sign-out. */
export function resetBrowserAccount(): void {
    accountIdentity = null;
    markerIdentity = null;
    if (typeof window !== "undefined") {
        try { window.localStorage.removeItem(ACCOUNT_KEY); } catch {}
    }
    invalidateAccountStorage();
}

/** Removes account-scoped browser data while preserving browser-wide preferences and identity events. */
export function clearTenantBrowserStorage(): void {
    if (typeof window === "undefined") return;
    try {
        const storage = window.localStorage;
        const keys: string[] = [];
        for (let index = 0; index < storage.length; index += 1) {
            const key = storage.key(index);
            if (key !== null && DATA_PREFIXES.some((prefix) => key.startsWith(prefix))) {
                keys.push(key);
            }
        }
        for (const key of keys) storage.removeItem(key);
    } catch {}
    try {
        for (const key of SESSION_DATA_KEYS) window.sessionStorage.removeItem(key);
    } catch {}
}

/**
 * Follows the account marker without waiting for this tab to navigate. It adopts an account another
 * tab confirmed, and treats a removed marker as the revocation a sign-out performs, so the marker
 * alone revokes this tab's writers when the best-effort logout broadcast never arrives.
 */
export function reconcileBrowserAccount(): void {
    const marker = readAccountMarker();
    markerIdentity = marker;
    if (marker === null) {
        if (accountIdentity === null) return;
        accountIdentity = null;
        invalidateAccountStorage();
        return;
    }
    if (marker === accountIdentity) return;
    accountIdentity = marker;
    invalidateAccountStorage();
}

/**
 * Adopts a server-authenticated account, including full-navigation sign-ins that bypass the API
 * client. Only a different account replaces the confirmed one, so a re-authenticated session or a
 * missing marker keeps the signed-in user's own data. A browser that holds neither an in-memory
 * identity nor a marker cannot tell whose leftovers it holds — data written by an account whose
 * marker write was refused survives here, and the unauthenticated sweep is the backstop that
 * removes it before the next account signs in.
 * @param userId the account the server authenticated for this document
 * @returns how the account resolved against the last confirmed one
 */
export function adoptBrowserAccount(userId: number): AccountAdoption {
    if (typeof window === "undefined") return "unchanged";
    const current = String(userId);
    const marker = readAccountMarker();
    markerIdentity = marker;
    const previous = accountIdentity ?? marker;
    if (previous !== null && previous !== current) {
        accountIdentity = current;
        invalidateAccountStorage();
        writeAccountMarker(current);
        return "replaced";
    }
    if (accountIdentity === current && marker === current) return "unchanged";
    const firstConfirmation = accountIdentity !== current;
    accountIdentity = current;
    return writeAccountMarker(current) && firstConfirmation ? "adopted" : "unchanged";
}
