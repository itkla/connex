/**
 * Client for the anonymous launch-signup endpoint.
 *
 * This lives apart from `api.ts` because the prelaunch landing deployment bundles the signup form
 * without the rest of the product, and `api.ts` carries the whole authenticated API surface.
 */

export type LaunchSignupResult = "success" | "invalid" | "error" | "rateLimited";

/** Uses the public Next.js endpoint without application cookies, CSRF, or workspace context. */
export async function subscribeToLaunch(email: string, website = ""): Promise<LaunchSignupResult> {
    const response = await fetch("/api/launch-signups", {
        method: "POST",
        credentials: "omit",
        cache: "no-store",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ email, website }),
        signal: AbortSignal.timeout(15_000),
    });
    if (response.status === 429) return "rateLimited";
    const body: unknown = await response.json();
    if (typeof body !== "object" || body === null) return "error";
    if (response.ok && "status" in body && (body.status === "subscribed" || body.status === "accepted")) return "success";
    if ("error" in body && body.error === "invalid_email") return "invalid";
    return "error";
}
