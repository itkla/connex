import type { Metadata } from "next";
import { headers } from "next/headers";
import { getTranslations } from "next-intl/server";

import OrganizationIdentity from "@/app/components/settings/OrganizationIdentity";
import WorkspaceUnavailablePage from "@/app/components/WorkspaceUnavailablePage";
import { getCapabilitiesResultFromCookie, getCurrentUserResultFromCookie } from "@/app/lib/api";

export async function generateMetadata(): Promise<Metadata> {
    const [tNav, t] = await Promise.all([
        getTranslations("SettingsNav"),
        getTranslations("SettingsOrgIdentity"),
    ]);
    return {
        title: tNav("groupIdentityAdministrators"),
        description: t("metaDescription"),
    };
}

/**
 * The canonical Identity & administrators destination (#1340 PR 6).
 *
 * Single sign-on is resolved here rather than in the client, matching how `/organization/sso`
 * already resolves it, so the section knows on first paint whether there is anything to configure.
 * A failed capability read is not a deployment without single sign-on: it yields `null`, which the
 * section reports as a retryable state rather than telling an administrator their instance lacks a
 * feature it may well have.
 *
 * The viewer is read for the roster, which marks their own row. That read comes first and alone:
 * authentication settles before any other read starts, so a page whose session has gone does not
 * fire requests on its way to saying so. A capability read that then fails takes only its own
 * section's certainty with it.
 */
export default async function OrganizationIdentityPage() {
    const cookie = (await headers()).get("cookie");
    const userResult = await getCurrentUserResultFromCookie(cookie);
    if (!userResult.ok) {
        return <WorkspaceUnavailablePage />;
    }
    const capabilities = await getCapabilitiesResultFromCookie(cookie);
    return (
        <OrganizationIdentity
            sso={capabilities.ok ? capabilities.data.sso : null}
            currentUserId={userResult.data?.id ?? null}
        />
    );
}
