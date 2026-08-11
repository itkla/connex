import { AuthForm } from "@/app/components/AuthForm";
import { getCapabilities, toResult } from "@/app/lib/api";
import { capabilityAvailability } from "@/app/lib/capabilityAvailability";

export default async function LoginPage({
    searchParams,
}: {
    searchParams: Promise<{ redirect?: string; sso_error?: string }>;
}) {
    const { redirect, sso_error } = await searchParams;
    const capabilitiesResult = await toResult(getCapabilities());
    const capabilities = capabilitiesResult.ok ? capabilitiesResult.data : null;
    return (
        <AuthForm
            mode="login"
            redirectUrl={redirect ?? null}
            ssoError={sso_error === "1"}
            ssoEnabled={capabilities?.sso ?? false}
            socialProviders={capabilities?.socialLogin ?? {}}
            ssoAvailability={capabilityAvailability(capabilities?.sso ?? null)}
        />
    );
}
