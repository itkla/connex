import type { Viewport } from "next";
import { Suspense } from "react";
import Sidebar from "@/app/components/Sidebar";
import SidebarFallback from "@/app/components/SidebarFallback";
import ContentShell from "@/app/components/ContentShell";
import WorkspaceUnavailablePage from "@/app/components/WorkspaceUnavailablePage";
import {
    getCapabilitiesResultFromCookie,
    getCurrentUserResultFromCookie,
    getEffectivePermissionsResultFromCookie,
    getMyWorkspacesResultFromCookie,
} from "@/app/lib/api";
import { headers } from "next/headers";
import { redirect } from "next/navigation";
import { NotificationProvider } from "@/app/hooks/useNotifications";
import { NowProvider } from "@/app/hooks/useNow";
import { PermissionsProvider } from "@/app/hooks/usePermissions";
import { WorkspaceProvider } from "@/app/hooks/useWorkspace";
import { ProtectedMediaProvider } from "@/app/hooks/useProtectedMedia";
import { NavTrailProvider } from "@/app/hooks/useNavTrail";
import { ActionProvider } from "@/app/hooks/useActions";
import { PinnedViewsProvider } from "@/app/hooks/usePinnedViews";
import { RecentRecordsProvider } from "@/app/hooks/useRecentRecords";
import NotificationActionsBridge from "@/app/components/actions/NotificationActionsBridge";
import PreferenceActionsBridge from "@/app/components/actions/PreferenceActionsBridge";
import PinnedViewsActionsBridge from "@/app/components/actions/PinnedViewsActionsBridge";
import RecentRecordsActionsBridge from "@/app/components/actions/RecentRecordsActionsBridge";
import DraftResumeBridge from "@/app/components/DraftResumeBridge";
import { SidebarModeProvider } from "@/app/hooks/useSidebarMode";
import NavActionsBridge from "@/app/components/actions/NavActionsBridge";
import { resolveNavAccess } from "@/app/lib/navAccess";
import { requestNow } from "@/app/lib/requestClock";
import { localePreferenceFromCookieHeader, resolveLocale } from "@/i18n/config";
import AskConnexProvider from "@/app/components/ask-connex/AskConnexProvider";

const SIDEBAR_SURFACE_CLASS = "bg-sidebar h-full rounded-xl border border-sidebar-border shadow-xl";

/** `viewportFit: cover` lets `env(safe-area-inset-*)` resolve to real values on notched devices, which the mobile bottom bar relies on. Scoped to the app shell so marketing/auth pages keep the default. */
export const viewport: Viewport = {
    viewportFit: "cover",
};

export default async function AppLayout({
    children,
}: Readonly<{
    children: React.ReactNode;
}>) {

    const headerList = await headers();
    const cookie = headerList.get('cookie');
    const signInPath = `/auth/login?redirect=${encodeURIComponent(headerList.get('x-pathname') ?? '/dashboard')}`;
    const userResult = await getCurrentUserResultFromCookie(cookie);

    if (!userResult.ok) {
        return <WorkspaceUnavailablePage />;
    }
    const user = userResult.data;
    if (!user) {
        redirect(signInPath);
    }

    const workspacesResult = await getMyWorkspacesResultFromCookie(cookie);
    if (!workspacesResult.ok) {
        if (workspacesResult.unauthenticated) {
            redirect(signInPath);
        }
        return <WorkspaceUnavailablePage />;
    }
    const { workspaces, activeWorkspaceId } = workspacesResult.data;
    if (workspaces.length === 0) {
        redirect('/onboarding');
    }

    const [capabilitiesResult, permissionsResult] = await Promise.all([
        getCapabilitiesResultFromCookie(cookie),
        getEffectivePermissionsResultFromCookie(cookie),
    ]);
    const effectivePermissions = permissionsResult.ok ? permissionsResult.data : [];
    const permissionsStatus = permissionsResult.ok ? "resolved" : "unavailable";
    const navAccess = resolveNavAccess(
        capabilitiesResult.ok ? capabilitiesResult.data : null,
        effectivePermissions,
    );

    return (
        <NowProvider value={requestNow()}>
            <PermissionsProvider permissions={effectivePermissions} status={permissionsStatus}>
                <WorkspaceProvider initialWorkspaces={workspaces} initialActiveId={activeWorkspaceId}>
                    <ProtectedMediaProvider userId={user.id}>
                        <NotificationProvider key={user.id} recipientId={user.id}>
                            <NavTrailProvider userId={user.id} navAccess={navAccess}>
                                <ActionProvider user={user}>
                                    <NotificationActionsBridge />
                                    <PreferenceActionsBridge
                                        userLocale={resolveLocale(user.locale)}
                                        cookieLocale={localePreferenceFromCookieHeader(cookie)}
                                    />
                                    <DraftResumeBridge />
                                    <NavActionsBridge navAccess={navAccess} />
                                    <PinnedViewsProvider>
                                        <PinnedViewsActionsBridge />
                                        <RecentRecordsProvider>
                                            <RecentRecordsActionsBridge />
                                            <SidebarModeProvider>
                                                <AskConnexProvider>
                                                    <ContentShell
                                                        sidebar={
                                                            <Suspense fallback={<SidebarFallback className={SIDEBAR_SURFACE_CLASS} />}>
                                                                <Sidebar
                                                                    user={user}
                                                                    navAccess={navAccess}
                                                                    className={SIDEBAR_SURFACE_CLASS}
                                                                />
                                                            </Suspense>
                                                        }
                                                    >
                                                        {children}
                                                    </ContentShell>
                                                </AskConnexProvider>
                                            </SidebarModeProvider>
                                        </RecentRecordsProvider>
                                    </PinnedViewsProvider>
                                </ActionProvider>
                            </NavTrailProvider>
                        </NotificationProvider>
                    </ProtectedMediaProvider>
                </WorkspaceProvider>
            </PermissionsProvider>
        </NowProvider>
    );
}
