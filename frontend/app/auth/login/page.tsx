import { AuthForm } from "@/app/components/AuthForm";

export default async function LoginPage({
    searchParams,
}: {
    searchParams: Promise<{ redirect?: string; sso_error?: string }>;
}) {
    const { redirect, sso_error } = await searchParams;
    return <AuthForm mode="login" redirectUrl={redirect ?? null} ssoError={sso_error === "1"} />;
}
