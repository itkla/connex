import { documentAcceptanceFailureKind } from "@/app/lib/api";
import type { DocumentAcceptanceFailureKind } from "@/app/lib/types";

/** Resolves a view failure unless a terminal decision has already produced the receipt. */
export function documentAcceptanceViewFailure(
    hasTerminalReceipt: boolean,
    error: unknown,
): DocumentAcceptanceFailureKind | null {
    if (hasTerminalReceipt) return null;
    return documentAcceptanceFailureKind(error) ?? "service-unavailable";
}
