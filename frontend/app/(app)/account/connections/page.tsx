import { Suspense } from "react";
import { headers } from "next/headers";
import { redirect } from "next/navigation";

import {
    DEFAULT_CAPABILITIES,
    getCapabilities,
    getEffectivePermissionsResultFromCookie,
} from "@/app/lib/api";
import ConnectionsPanel from "@/app/components/account/ConnectionsPanel";
import {
    captureConnectionsHref,
    parseCaptureRouteState,
    providerCaptureEnabled,
} from "@/app/lib/connectedCapture";
import { Skeleton } from "@/components/ui/skeleton";

function toSearchParams(
    values: Record<string, string | string[] | undefined>,
): URLSearchParams {
    const params = new URLSearchParams();
    for (const [key, value] of Object.entries(values)) {
        if (Array.isArray(value)) {
            value.forEach((entry) => params.append(key, entry));
        } else if (value !== undefined) {
            params.set(key, value);
        }
    }
    return params;
}

export default async function AccountConnectionsPage({
    searchParams,
}: {
    searchParams: Promise<Record<string, string | string[] | undefined>>;
}) {
    const cookie = (await headers()).get("cookie");
    const capabilities = await getCapabilities(cookie ? { headers: { cookie } } : {})
        .catch(() => DEFAULT_CAPABILITIES);
    const captureEnabled =
        capabilities.connectedCapture.google || capabilities.connectedCapture.microsoft;
    const permissionsResult = captureEnabled
        ? await getEffectivePermissionsResultFromCookie(cookie)
        : { ok: true as const, data: [] };
    const effectivePermissions = permissionsResult.ok ? permissionsResult.data : [];
    const currentSearchParams = toSearchParams(await searchParams);
    const routeState = parseCaptureRouteState(currentSearchParams);
    const routeUnavailable = routeState.provider
        && !providerCaptureEnabled(capabilities, routeState.provider);
    const workspacePolicyForbidden = routeState.panel === "workspace-policy"
        && !effectivePermissions.includes("WORKSPACE_SETTINGS");
    const canonicalHref = captureConnectionsHref(
        currentSearchParams,
        routeUnavailable || workspacePolicyForbidden
            ? { provider: null, panel: null, reviewId: null, page: 1 }
            : routeState,
    );
    const currentQuery = currentSearchParams.toString();
    const currentHref = currentQuery
        ? `/account/connections?${currentQuery}`
        : "/account/connections";
    if (canonicalHref !== currentHref) {
        redirect(canonicalHref);
    }

    return (
        <Suspense
            fallback={(
                <div className="grid gap-3" role="status">
                    <Skeleton className="h-28 w-full rounded-2xl" />
                    <Skeleton className="h-28 w-full rounded-2xl" />
                </div>
            )}
        >
            <ConnectionsPanel
                capabilities={capabilities}
                effectivePermissions={effectivePermissions}
            />
        </Suspense>
    );
}
