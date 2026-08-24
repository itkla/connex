import type { Metadata } from "next";
import { headers } from "next/headers";
import { redirect } from "next/navigation";
import { getTranslations } from "next-intl/server";

import PersonalConnectedAccounts from "@/app/components/settings/PersonalConnectedAccounts";
import {
    DEFAULT_CAPABILITIES,
    getCapabilitiesResultFromCookie,
    getEffectivePermissionsResultFromCookie,
} from "@/app/lib/api";
import { capabilityAvailability } from "@/app/lib/capabilityAvailability";
import { CONNECTED_ACCOUNTS_ROUTE } from "@/app/lib/connectedAccountsSections";
import {
    capturePanelRequiresCapture,
    captureConnectionsHref,
    parseCaptureRouteState,
    providerCaptureEnabled,
    providerJourneyEnabled,
} from "@/app/lib/connectedCapture";
import { checkPermission, type PermissionsStatus } from "@/app/lib/permissionState";

export async function generateMetadata(): Promise<Metadata> {
    const [tConnections, t] = await Promise.all([
        getTranslations("AccountConnections"),
        getTranslations("SettingsPersonalConnections"),
    ]);
    return {
        title: tConnections("title"),
        description: t("metaDescription"),
    };
}

function toSearchParams(values: Record<string, string | string[] | undefined>): URLSearchParams {
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

/**
 * The canonical Connected accounts destination (#1340 WS4.2): the reader's own provider
 * authorizations, and the workspace capture policy for the members who administer it.
 *
 * Everything below is what `/account/connections` did, moved rather than rewritten — the journey
 * itself belongs to #60 and this epic owns only where it lives.
 *
 * Canonicalizing the query string is how a route state the viewer may not reach is refused, and it
 * is destructive: the deep link is rewritten away, so the reader has nothing left to retry. That is
 * only an honest answer to a permission check that actually returned one. When the effective
 * permissions could not be read at all, the deep link is preserved and the panel reports the failed
 * lookup instead — the administrator who followed a link to the policy panel during a blip can retry
 * it where they are, rather than going back to find the link again. Access still fails closed either
 * way: the permission list stays empty, so every gated affordance stays hidden.
 */
export default async function PersonalConnectedAccountsPage({
    searchParams,
}: {
    searchParams: Promise<Record<string, string | string[] | undefined>>;
}) {
    const cookie = (await headers()).get("cookie");
    const capabilitiesResult = await getCapabilitiesResultFromCookie(cookie);
    const capabilities = capabilitiesResult.ok ? capabilitiesResult.data : DEFAULT_CAPABILITIES;
    const capabilitiesAvailability = capabilityAvailability(capabilitiesResult.ok
        ? capabilities.connectedAccounts.google
            || capabilities.connectedAccounts.microsoft
            || capabilities.connectedCapture.google
            || capabilities.connectedCapture.microsoft
        : null);
    const captureEnabled =
        capabilities.connectedCapture.google || capabilities.connectedCapture.microsoft;
    const permissionsResult = captureEnabled
        ? await getEffectivePermissionsResultFromCookie(cookie)
        : { ok: true as const, data: [] };
    const effectivePermissions = permissionsResult.ok ? permissionsResult.data : [];
    const permissionsStatus: PermissionsStatus = permissionsResult.ok ? "resolved" : "unavailable";
    const currentSearchParams = toSearchParams(await searchParams);
    const routeState = parseCaptureRouteState(currentSearchParams);
    const providerUnavailable = capabilitiesResult.ok && routeState.provider
        && !providerJourneyEnabled(capabilities, routeState.provider);
    const panelUnavailable = capabilitiesResult.ok && routeState.provider != null
        && routeState.panel != null
        && capturePanelRequiresCapture(routeState.panel)
        && !providerCaptureEnabled(capabilities, routeState.provider);
    const workspacePolicyForbidden = capabilitiesResult.ok
        && routeState.panel === "workspace-policy"
        && checkPermission(permissionsStatus, new Set(effectivePermissions), "WORKSPACE_SETTINGS")
            === "denied";
    const canonicalHref = captureConnectionsHref(
        currentSearchParams,
        providerUnavailable
            ? { provider: null, panel: null, reviewId: null, page: 1 }
            : panelUnavailable || workspacePolicyForbidden
                ? { panel: null, reviewId: null, page: 1 }
                : routeState,
    );
    const currentQuery = currentSearchParams.toString();
    const currentHref = currentQuery
        ? `${CONNECTED_ACCOUNTS_ROUTE}?${currentQuery}`
        : CONNECTED_ACCOUNTS_ROUTE;
    if (canonicalHref !== currentHref) {
        redirect(canonicalHref);
    }

    return (
        <PersonalConnectedAccounts
            capabilities={capabilities}
            capabilitiesAvailability={capabilitiesAvailability}
            effectivePermissions={effectivePermissions}
            permissionsStatus={permissionsStatus}
        />
    );
}
