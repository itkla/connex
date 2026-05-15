import { AuthForm } from "@/app/components/AuthForm";

export default async function LoginPage({
    searchParams,
}: {
    searchParams: Promise<{ redirect: string }>;
}) {
    const { redirect } = await searchParams;
    return <AuthForm mode="login" redirectUrl={redirect ?? null} />;
}
