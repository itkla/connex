import { SsoLinkForm } from "@/app/sso/link/SsoLinkForm";

export default async function SsoLinkPage({
    searchParams,
}: {
    searchParams: Promise<{ token?: string }>;
}) {
    const { token } = await searchParams;
    return <SsoLinkForm token={token ?? null} />;
}
