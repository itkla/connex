'use client';

import { Fragment } from 'react';
import Link from 'next/link';
import {
    Breadcrumb,
    BreadcrumbEllipsis,
    BreadcrumbItem,
    BreadcrumbLink,
    BreadcrumbList,
    BreadcrumbPage,
    BreadcrumbSeparator,
} from '@/components/ui/breadcrumb';
import {
    DropdownMenu,
    DropdownMenuContent,
    DropdownMenuItem,
    DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';
import { useNavTrail, type Crumb } from '@/app/hooks/useNavTrail';

type Node = { kind: 'crumb'; crumb: Crumb; current: boolean } | { kind: 'ellipsis'; hidden: Crumb[] };

function buildNodes(trail: Crumb[]): Node[] {
    if (trail.length <= 3) {
        return trail.map((crumb, i) => ({ kind: 'crumb', crumb, current: i === trail.length - 1 }));
    }
    return [
        { kind: 'crumb', crumb: trail[0], current: false },
        { kind: 'ellipsis', hidden: trail.slice(1, trail.length - 2) },
        { kind: 'crumb', crumb: trail[trail.length - 2], current: false },
        { kind: 'crumb', crumb: trail[trail.length - 1], current: true },
    ];
}

/**
 * User-aware breadcrumb: renders the trail of pages the user actually traversed to reach the
 * current one. Hidden on top-level pages (nothing to trace back to). Long trails collapse to the
 * origin, an expandable ellipsis, and the last two steps.
 */
export default function NavBreadcrumb() {
    const trail = useNavTrail();
    if (trail.length <= 1) return null;

    const nodes = buildNodes(trail);

    return (
        <Breadcrumb className="mb-5">
            <BreadcrumbList>
                {nodes.map((node, i) => (
                    <Fragment key={node.kind === 'crumb' ? node.crumb.pathname : 'ellipsis'}>
                        {i > 0 && <BreadcrumbSeparator />}
                        <BreadcrumbItem className="min-w-0">
                            {node.kind === 'ellipsis' ? (
                                <DropdownMenu>
                                    <DropdownMenuTrigger
                                        aria-label="Show hidden steps"
                                        className="flex items-center rounded-sm outline-none transition-colors hover:text-foreground focus-visible:text-foreground"
                                    >
                                        <BreadcrumbEllipsis />
                                    </DropdownMenuTrigger>
                                    <DropdownMenuContent align="start">
                                        {node.hidden.map((c) => (
                                            <DropdownMenuItem key={c.pathname} asChild>
                                                <Link href={c.pathname}>{c.label}</Link>
                                            </DropdownMenuItem>
                                        ))}
                                    </DropdownMenuContent>
                                </DropdownMenu>
                            ) : node.current ? (
                                <BreadcrumbPage className="max-w-[16rem] truncate">{node.crumb.label}</BreadcrumbPage>
                            ) : (
                                <BreadcrumbLink asChild className="max-w-[12rem] truncate">
                                    <Link href={node.crumb.pathname}>{node.crumb.label}</Link>
                                </BreadcrumbLink>
                            )}
                        </BreadcrumbItem>
                    </Fragment>
                ))}
            </BreadcrumbList>
        </Breadcrumb>
    );
}
