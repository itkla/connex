import { documentAcceptanceFailureKind } from "@/app/lib/api";
import type { DocumentAcceptanceFailureKind } from "@/app/lib/types";

const DOCUMENT_ACCEPTANCE_PATH = /^\/document-acceptance\/(w\d+-[a-f0-9]{64})\/?$/;

/** Returns the current document bearer only when the browser is on its exact recipient route. */
export function documentAcceptanceTokenFromLocation(): string | null {
    if (typeof window === "undefined") return null;
    return DOCUMENT_ACCEPTANCE_PATH.exec(window.location.pathname)?.[1] ?? null;
}

/** Resolves a view failure unless a terminal decision has already produced the receipt. */
export function documentAcceptanceViewFailure(
    hasTerminalReceipt: boolean,
    error: unknown,
): DocumentAcceptanceFailureKind | null {
    if (hasTerminalReceipt) return null;
    return documentAcceptanceFailureKind(error) ?? "service-unavailable";
}
