import { VerifyEmailForm } from "@/app/auth/verify-email/VerifyEmailForm";

export default async function VerifyEmailPage({
    searchParams,
}: {
    searchParams: Promise<{ token?: string }>;
}) {
    const { token } = await searchParams;
    return <VerifyEmailForm token={token ?? null} />;
}
