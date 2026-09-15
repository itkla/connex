import type { Metadata } from "next";
import { headers } from "next/headers";
import { getTranslations } from "next-intl/server";

import { AuthForm } from "@/app/components/AuthForm";
import BrowserAccountBridge from "@/app/components/BrowserAccountBridge";
import SignedOutStorageCleanup from "@/app/components/SignedOutStorageCleanup";
import { getCapabilities, getCurrentUserResultFromCookie, toResult } from "@/app/lib/api";
import { capabilityAvailability } from "@/app/lib/capabilityAvailability";

export async function generateMetadata(): Promise<Metadata> {
    const t = await getTranslations("AuthLogin");
    return { title: t("title"), robots: { index: false, follow: false } };
}

/**
 * Sign-in entry. The session is resolved server-side because this route also renders for a
 * signed-in visitor — a `redirect` parameter keeps the proxy from bouncing them — so the
 * unauthenticated storage sweep runs only when the server confirms there is no session, and an
 * authentication check that could not be completed removes nothing.
 * @param searchParams the post-login destination and SSO failure marker
 */
export default async function LoginPage({
    searchParams,
}: {
    searchParams: Promise<{ redirect?: string; sso_error?: string }>;
}) {
    const cookie = (await headers()).get("cookie");
    const [{ redirect, sso_error }, capabilitiesResult, sessionResult] = await Promise.all([
        searchParams,
        toResult(getCapabilities()),
        getCurrentUserResultFromCookie(cookie),
    ]);
    const capabilities = capabilitiesResult.ok ? capabilitiesResult.data : null;
    const signedInUser = sessionResult.ok ? sessionResult.data : null;
    const signedOut = sessionResult.ok && sessionResult.data === null;
    return (
        <>
            {signedOut ? <SignedOutStorageCleanup /> : null}
            {signedInUser === null ? null : <BrowserAccountBridge userId={signedInUser.id} />}
            <AuthForm
                mode="login"
                redirectUrl={redirect ?? null}
                ssoError={sso_error === "1"}
                ssoEnabled={capabilities?.sso ?? false}
                socialProviders={capabilities?.socialLogin ?? {}}
                ssoAvailability={capabilityAvailability(capabilities?.sso ?? null)}
            />
        </>
    );
}
