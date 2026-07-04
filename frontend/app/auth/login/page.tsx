import { AuthForm } from "@/app/components/AuthForm";
import { getSocialLoginProviders, getSsoInstanceEnabled } from "@/app/lib/api";

export default async function LoginPage({
    searchParams,
}: {
    searchParams: Promise<{ redirect?: string; sso_error?: string }>;
}) {
    const { redirect, sso_error } = await searchParams;
    const ssoEnabled = await getSsoInstanceEnabled()
        .then((r) => r.enabled)
        .catch(() => false);
    const socialProviders = await getSocialLoginProviders().catch(() => ({
        google: false,
        microsoft: false,
    }));
    return (
        <AuthForm
            mode="login"
            redirectUrl={redirect ?? null}
            ssoError={sso_error === "1"}
            ssoEnabled={ssoEnabled}
            socialProviders={socialProviders}
        />
    );
}
