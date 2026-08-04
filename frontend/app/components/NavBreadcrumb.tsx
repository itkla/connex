"use client";

import { Fragment } from "react";
import Link from "next/link";
import { useSearchParams } from "next/navigation";
import { useTranslations } from "next-intl";

import RecordReturnLink from "@/app/components/records/RecordReturnLink";
import { useNavTrail, type Crumb } from "@/app/hooks/useNavTrail";
import {
    buildBreadcrumbNodes,
    type BreadcrumbDisplayMode,
} from "@/app/lib/breadcrumbRoutes";
import { resolveRecordReturnPath } from "@/app/lib/recordReturnPath";
import {
    Breadcrumb,
    BreadcrumbEllipsis,
    BreadcrumbItem,
    BreadcrumbLink,
    BreadcrumbList,
    BreadcrumbPage,
    BreadcrumbSeparator,
} from "@/components/ui/breadcrumb";
import {
    DropdownMenu,
    DropdownMenuContent,
    DropdownMenuItem,
    DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";

function ResolvedCrumbLink({
    crumb,
    dropdown = false,
}: {
    crumb: Crumb;
    dropdown?: boolean;
}) {
    const searchParams = useSearchParams();
    const returnTargets = searchParams.getAll("returnTo");
    const returnTarget = returnTargets.length === 0
        ? undefined
        : returnTargets.length === 1
            ? returnTargets[0]
            : returnTargets;
    const href = crumb.returnCollection
        ? resolveRecordReturnPath(crumb.returnCollection, returnTarget)
        : crumb.pathname;
    const className = dropdown ? undefined : "max-w-[12rem] truncate";
    const link = crumb.returnCollection && !dropdown ? (
        <RecordReturnLink href={href} className={className}>
            {crumb.label}
        </RecordReturnLink>
    ) : (
        <Link href={href} className={className}>{crumb.label}</Link>
    );

    return dropdown
        ? <DropdownMenuItem asChild>{link}</DropdownMenuItem>
        : <BreadcrumbLink asChild>{link}</BreadcrumbLink>;
}

/** Renders the deterministic route hierarchy in a desktop or compact mobile form. */
export default function NavBreadcrumb({ mode = "desktop" }: { mode?: BreadcrumbDisplayMode }) {
    const trail = useNavTrail();
    const t = useTranslations("CommonBreadcrumb");
    if (trail.length <= 1 || !trail.some((crumb) => crumb.current)) return null;

    const nodes = buildBreadcrumbNodes(trail, mode);

    const breadcrumb = (
        <Breadcrumb aria-label={t("ariaLabel")} className="min-w-0">
            <BreadcrumbList className="flex-nowrap overflow-hidden">
                {nodes.map((node, index) => (
                    <Fragment key={node.kind === "crumb" ? node.crumb.pathname : "ellipsis"}>
                        {index > 0 ? <BreadcrumbSeparator /> : null}
                        <BreadcrumbItem className="min-w-0">
                            {node.kind === "ellipsis" ? (
                                <DropdownMenu>
                                    <DropdownMenuTrigger
                                        aria-label={t("showHidden")}
                                        className="flex items-center rounded-sm outline-none transition-colors hover:text-foreground focus-visible:text-foreground"
                                    >
                                        <BreadcrumbEllipsis />
                                    </DropdownMenuTrigger>
                                    <DropdownMenuContent align="start">
                                        {node.hidden.map((crumb) => (
                                            <ResolvedCrumbLink key={crumb.pathname} crumb={crumb} dropdown />
                                        ))}
                                    </DropdownMenuContent>
                                </DropdownMenu>
                            ) : node.crumb.current ? (
                                <BreadcrumbPage className="max-w-[16rem] truncate">
                                    {node.crumb.label}
                                </BreadcrumbPage>
                            ) : (
                                <ResolvedCrumbLink crumb={node.crumb} />
                            )}
                        </BreadcrumbItem>
                    </Fragment>
                ))}
            </BreadcrumbList>
        </Breadcrumb>
    );
    return mode === "mobile"
        ? <div className="min-w-0 shrink-0 px-6 pb-4 lg:hidden">{breadcrumb}</div>
        : breadcrumb;
}
