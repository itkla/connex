"use client";

import {
    createContext,
    useCallback,
    useContext,
    useEffect,
    useId,
    useMemo,
    useState,
    type ReactNode,
} from "react";
import { usePathname } from "next/navigation";
import { useTranslations } from "next-intl";

import { useWorkspace } from "@/app/hooks/useWorkspace";
import {
    resolveBreadcrumbRoute,
    type BreadcrumbCrumb,
    type BreadcrumbMessageKey,
} from "@/app/lib/breadcrumbRoutes";
import type { NavAccess } from "@/app/lib/navAccess";

export type Crumb = BreadcrumbCrumb;

type LabelRegistration = {
    pathname: string;
    registrationId: string;
    label: string;
};

type LabelRegistry = {
    scope: string;
    labels: readonly LabelRegistration[];
};

type NavTrailContextValue = {
    scope: string;
    trail: Crumb[];
    registerLabel: (
        scope: string,
        pathname: string,
        registrationId: string,
        label: string,
    ) => () => void;
};

const NavTrailContext = createContext<NavTrailContextValue | null>(null);

function resolvedLabels(registrations: readonly LabelRegistration[]): ReadonlyMap<string, string> {
    const labels = new Map<string, string>();
    for (const registration of registrations) labels.set(registration.pathname, registration.label);
    return labels;
}

/** Provides deterministic breadcrumbs scoped to the current user and active workspace. */
export function NavTrailProvider({
    userId,
    navAccess,
    children,
}: {
    userId: number;
    navAccess: NavAccess;
    children: ReactNode;
}) {
    const pathname = usePathname();
    const { activeWorkspace, activeWorkspaceId } = useWorkspace();
    const t = useTranslations("CommonBreadcrumb");
    const tMessage = useTranslations();
    const scope = `${userId}:${activeWorkspaceId ?? "none"}`;
    const [registry, setRegistry] = useState<LabelRegistry>({
        scope,
        labels: [],
    });
    if (registry.scope !== scope) setRegistry({ scope, labels: [] });
    const dynamicLabels = useMemo(
        () => resolvedLabels(registry.scope === scope ? registry.labels : []),
        [registry, scope],
    );

    const registerLabel = useCallback((
        registrationScope: string,
        registeredPathname: string,
        registrationId: string,
        label: string,
    ) => {
        const cleanPathname = registeredPathname.trim();
        const cleanLabel = label.trim();
        if (
            !cleanPathname.startsWith("/")
            || cleanPathname.includes("?")
            || cleanPathname.includes("#")
            || !cleanLabel
        ) {
            return () => undefined;
        }
        setRegistry((current) => {
            if (current.scope !== registrationScope) return current;
            const existing = current.labels.find(
                (registration) => registration.registrationId === registrationId,
            );
            if (existing?.pathname === cleanPathname && existing.label === cleanLabel) return current;
            return {
                scope: registrationScope,
                labels: [
                    ...current.labels.filter(
                        (registration) => registration.registrationId !== registrationId,
                    ),
                    { pathname: cleanPathname, registrationId, label: cleanLabel },
                ],
            };
        });
        return () => {
            setRegistry((current) => {
                if (current.scope !== registrationScope) return current;
                const labels = current.labels.filter(
                    (registration) => registration.registrationId !== registrationId,
                );
                return labels.length === current.labels.length ? current : { scope: registrationScope, labels };
            });
        };
    }, []);

    const translate = useCallback(
        (key: BreadcrumbMessageKey) => t(key),
        [t],
    );
    const translateMessage = useCallback((key: string) => tMessage(key), [tMessage]);
    const trail = useMemo(
        () => resolveBreadcrumbRoute(pathname, {
            workspaceName: activeWorkspace?.name ?? null,
            organizationName: activeWorkspace?.orgName ?? null,
            organizationAccessible: activeWorkspace?.orgRole != null,
            navAccess,
            dynamicLabels,
            translate,
            translateMessage,
        }).crumbs,
        [activeWorkspace, dynamicLabels, navAccess, pathname, translate, translateMessage],
    );
    const value = useMemo<NavTrailContextValue>(
        () => ({ scope, trail, registerLabel }),
        [registerLabel, scope, trail],
    );

    return <NavTrailContext.Provider value={value}>{children}</NavTrailContext.Provider>;
}

/** Returns the canonical breadcrumb trail for the current authenticated route. */
export function useNavTrail(): Crumb[] {
    return useContext(NavTrailContext)?.trail ?? [];
}

/** Registers a dynamic entity label for the current route or an explicit parent route. */
export function useCrumbLabel(label: string, pathname?: string): void {
    const currentPathname = usePathname();
    const registrationId = useId();
    const context = useContext(NavTrailContext);
    const currentScope = context?.scope ?? null;
    const registerLabel = context?.registerLabel;
    const [registrationIdentity, setRegistrationIdentity] = useState({
        routePathname: currentPathname,
        scope: currentScope,
    });
    if (
        registrationIdentity.routePathname !== currentPathname
        || (registrationIdentity.scope === null && currentScope !== null)
    ) {
        setRegistrationIdentity({ routePathname: currentPathname, scope: currentScope });
    }
    const registeredPathname = pathname ?? currentPathname;
    useEffect(
        () => registrationIdentity.scope
            ? registerLabel?.(registrationIdentity.scope, registeredPathname, registrationId, label)
            : undefined,
        [label, registerLabel, registeredPathname, registrationId, registrationIdentity.scope],
    );
}

/** Registers a breadcrumb label from a server-rendered page without adding visible content. */
export function CrumbLabel({ value, pathname }: { value: string; pathname?: string }): null {
    useCrumbLabel(value, pathname);
    return null;
}
