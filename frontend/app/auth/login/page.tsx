import { AuthForm } from "@/app/components/AuthForm";
import { DEFAULT_CAPABILITIES, getCapabilities } from "@/app/lib/api";

export default async function LoginPage({
    searchParams,
}: {
    searchParams: Promise<{ redirect?: string; sso_error?: string }>;
}) {
    const { redirect, sso_error } = await searchParams;
    const capabilities = await getCapabilities().catch(() => DEFAULT_CAPABILITIES);
    return (
        <AuthForm
            mode="login"
            redirectUrl={redirect ?? null}
            ssoError={sso_error === "1"}
            ssoEnabled={capabilities.sso}
            socialProviders={capabilities.socialLogin}
        />
    );
}
