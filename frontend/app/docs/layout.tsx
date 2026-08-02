import { headers } from "next/headers";
import { getPublicPageUserFromCookie } from "@/app/lib/api";
import DocsTopBar from "@/app/components/docs/DocsTopBar";
import DocsNav from "@/app/components/docs/DocsNav";
import LandingFooter from "@/app/components/landing/LandingFooter";

/**
 * Docs shell. Sits outside the `(app)` group and the route matcher in
 * `proxy.ts`, so it renders for both signed-out and signed-in visitors; the
 * session is detected server-side only to adapt the header's call to action.
 * Because that is the session's only role here, an unreachable backend falls
 * back to the signed-out call to action rather than failing the documentation.
 */
export default async function DocsLayout({ children }: Readonly<{ children: React.ReactNode }>) {
    const cookie = (await headers()).get("cookie");
    const user = await getPublicPageUserFromCookie(cookie);

    return (
        <div className="font-body flex min-h-screen flex-col bg-background text-foreground">
            <DocsTopBar authed={Boolean(user)} />

            <div className="mx-auto flex w-full max-w-7xl flex-1 gap-8 px-6 lg:px-8">
                <aside className="hidden w-60 shrink-0 lg:block">
                    <div className="sticky top-16 max-h-[calc(100dvh-4rem)] overflow-y-auto py-10 pr-2">
                        <DocsNav />
                    </div>
                </aside>

                <main className="min-w-0 flex-1 py-10">{children}</main>
            </div>

            <LandingFooter />
        </div>
    );
}
