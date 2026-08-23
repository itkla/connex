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
 * The viewer is read for the roster, which marks their own row. Both reads fail softly: a broken
 * session takes the whole page down, since the roster is what this destination is named for, but a
 * capability read that fails takes only its own section's certainty with it.
 */
export default async function OrganizationIdentityPage() {
    const cookie = (await headers()).get("cookie");
    const [userResult, capabilities] = await Promise.all([
        getCurrentUserResultFromCookie(cookie),
        getCapabilitiesResultFromCookie(cookie),
    ]);
    if (!userResult.ok) {
        return <WorkspaceUnavailablePage />;
    }
    return (
        <OrganizationIdentity
            sso={capabilities.ok ? capabilities.data.sso : null}
            currentUserId={userResult.data?.id ?? null}
        />
    );
}
